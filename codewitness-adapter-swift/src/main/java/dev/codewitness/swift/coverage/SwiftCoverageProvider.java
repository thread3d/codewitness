package dev.codewitness.swift.coverage;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.swift.SwiftProjectDetector;
import dev.codewitness.swift.SwiftToolchain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Measures Swift coverage from the llvm-cov export SwiftPM writes.
 *
 * <p>Swift has no separate coverage tool to install and probe for: {@code swift test
 * --enable-code-coverage} links the package's targets against the LLVM profile runtime, and XCTest is
 * what drives the code under test. Coverage is therefore decided by the report the test run produces,
 * not by a probe. When no report appears the answer is "coverage was not measured", with the reason;
 * never zero, because a zero is indistinguishable from a project whose tests execute nothing.
 *
 * <p>Two failures shape this implementation. First, the export is written to a fixed location below
 * {@code .build}, so a report from an earlier run would otherwise be indistinguishable from this run's:
 * every {@code codecov} directory below the package's build tree is cleared before the suite starts, so
 * a run that produced nothing can never be read as a run that produced these numbers. Second, the
 * command that names the report has moved between toolchain versions - it was a separate operation,
 * then an option of {@code swift test}, then a separate operation again - so its printed path is only
 * the preferred route; when it is absent or unusable the report is found by scanning the standard
 * {@code codecov} directories below {@code .build}.
 *
 * <p>Both are the reason nothing here is treated as a score until the JSON has been parsed: the profile
 * file, the derived test runner and the merged data file all share the {@code codecov} directory, and
 * reading any of them as a measurement would report an artefact as the project's coverage.
 *
 * <p>Only the project's own Swift files are attributed per file. The export can name the generated
 * test runner (SwiftPM instruments it too), a checked-out dependency, or XCTest itself, and an entry
 * for any of those says nothing about the code under verification. The aggregate is the exporter's own
 * sum over every file it names; the per-file map is restricted to the project's own sources.
 */
public final class SwiftCoverageProvider implements VerificationTools.CoverageProvider {

    /**
     * The coverage tool as the report names it.
     *
     * <p>It is the LLVM exporter, not a Swift-specific tool: SwiftPM drives {@code llvm-cov} and writes
     * its JSON. Naming it {@code llvm-cov} is what lets a report distinguish a real measurement from the
     * "not measured" branches below, which are about the toolchain rather than the tool.
     */
    private static final String TOOL = "llvm-cov";

    /** Directories below the package root that never hold the project's own source. */
    private static final Set<String> NON_PROJECT_SEGMENTS = Set.of("DerivedSources", "XCTest", "XCTestCore");

    /**
     * File names SwiftPM or XCTest generate rather than the project authoring.
     *
     * <p>{@code runner.swift} is the test runner SwiftPM derives for every test target; the other two are
     * the Linux test manifests older toolchains derived. They are compiled and instrumented like source,
     * but they are not the project's code, and counting their lines would credit the project with a
     * scaffold it did not write. The generated runner normally sits below {@code .build} as well, so this
     * is the second line of defence rather than the only one.
     */
    private static final Set<String> GENERATED_FILE_NAMES =
            Set.of("runner.swift", "XCTestManifests.swift", "LinuxMain.swift");

    /** Build configurations whose {@code codecov} directory the fallback scan looks in. */
    private static final List<String> BUILD_CONFIGURATIONS = List.of("debug", "release");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String id() {
        return "swift-coverage";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project != null && LanguageId.SWIFT.equals(project.language());
    }

    @Override
    public CoverageReport measure(VerificationContext context) {
        try {
            ProjectInfo project = context.projectInfo();
            String probeFailure = project == null ? null
                    : project.attributes().get(SwiftToolchain.ATTR_PROBE_FAILURE);
            if (probeFailure != null && !probeFailure.isBlank()) {
                // The probe is what decided whether any toolchain exists; a missing one is reported as the
                // reason the measurement did not happen rather than as a measurement of zero.
                return CoverageReport.notMeasured(TOOL, "the Swift toolchain could not be probed, so the "
                        + "llvm-cov report swift test would write cannot be produced: " + probeFailure);
            }
            String swift = project == null ? null : project.attributes().get(SwiftToolchain.ATTR_SWIFT);
            if (swift == null || swift.isBlank()) {
                return CoverageReport.notMeasured(TOOL, "no swift executable was resolved for this run "
                        + "(not in SWIFT_EXECUTABLE, not a package-local .swift/swift, and not in a "
                        + "conventional toolchain location), so swift test --enable-code-coverage cannot be "
                        + "run and llvm-cov coverage was not measured");
            }
            if (!hasTestTarget(project)) {
                return CoverageReport.notMeasured(TOOL, "the package has no test target, and XCTest is what "
                        + "instruments the code that swift test --enable-code-coverage measures; with no "
                        + "suite to run there is nothing to instrument, so coverage was not measured rather "
                        + "than reported as zero");
            }

            // The two module caches live inside the run's artefact store but are created here as well:
            // SwiftPM creates its own cache, config and security trees, but the driver hands the module
            // cache variables to Clang, and a missing directory would send Clang back to its per-user
            // default, which is outside the sandbox. Best-effort, because SwiftPM reports its own failure.
            prepareModuleCaches(context);

            // Cleared before the suite starts so that the numbers this run is credited with are the ones it
            // wrote. The codecov directory is a fixed location below .build, so without this a suite that
            // produced nothing would be read as a suite that produced the previous run's report.
            clearStaleCoverage(context.projectRoot());

            context.emit("coverage", "measuring llvm-cov coverage through swift test --enable-code-coverage");
            ProcessResult result = context.runner().run(ProcessSpec.builder(
                            SwiftToolchain.command(swift, context, List.of("test", "--enable-code-coverage")),
                            context.projectRoot())
                    .environment(SwiftToolchain.environment(context))
                    .timeout(context.config().limits().timeout())
                    .maxOutputBytes(1_000_000)
                    .build());
            if (result.timedOut()) {
                // The killed run may have written a partial profile or a codecov JSON for the cases that
                // finished; a percentage describing an unfinished suite is not a measurement.
                return CoverageReport.notMeasured(TOOL, "swift test was killed after the "
                        + context.config().limits().timeout().toSeconds() + "s timeout, so the llvm-cov "
                        + "profile it was writing is partial and is not a measurement; last output: "
                        + result.tail(5));
            }

            Path report = codecovReport(swift, context);
            if (report == null || !Files.isRegularFile(report)) {
                return CoverageReport.notMeasured(TOOL, "swift test --enable-code-coverage wrote no llvm-cov "
                        + "codecov JSON and none was found below " + context.projectRoot().resolve(".build")
                        + " (" + result.describeOutcome() + "); a missing report is a reason, never a zero; "
                        + "last output: " + result.tail(5));
            }
            return parse(report, context.projectRoot(),
                    "written while swift test --enable-code-coverage ran the XCTest suite ("
                            + result.describeOutcome() + ")");
        } catch (IOException | RuntimeException e) {
            return CoverageReport.notMeasured(TOOL, "coverage could not be measured ("
                    + e.getClass().getSimpleName() + "): " + e.getMessage());
        }
    }

    /**
     * @return {@code true} when the package has a suite for {@code swift test --enable-code-coverage} to
     *     run
     *
     * <p>Both sources are consulted because the detector finds test targets two ways: a conventional
     * {@code Tests/} directory, and a {@code .testTarget} whose module name it records from the manifest.
     * A package that lays its tests out with an explicit {@code path:} has no conventional directory but
     * does declare a test target, and refusing to measure it would report "no tests" about a package that
     * plainly has them.
     */
    private static boolean hasTestTarget(ProjectInfo project) {
        if (project == null) {
            return false;
        }
        if (!project.testSourceDirs().isEmpty()) {
            return true;
        }
        String testModule = project.attributes().get(SwiftToolchain.ATTR_TEST_MODULE);
        return testModule != null && !testModule.isBlank();
    }

    /** Creates the module cache directories Clang and the Swift driver are pointed at, if possible. */
    private static void prepareModuleCaches(VerificationContext context) {
        try {
            for (String directory : SwiftToolchain.environment(context).values()) {
                Files.createDirectories(Path.of(directory));
            }
        } catch (IOException | RuntimeException _) {
            // Best-effort: a run that cannot create them fails at the first SwiftPM command with its own
            // message rather than here, and swallowing the failure keeps this phase from throwing.
        }
    }

    /**
     * Finds the llvm-cov codecov JSON this run wrote.
     *
     * <p>The preferred route is {@code swift test --show-codecov-path}: it prints the path the toolchain
     * would write, which on the toolchain this adapter was developed against is the JSON file itself. The
     * printed value is used only when it names something readable - the option's behaviour has changed
     * between releases, and a path printed by a different version, or printed before the file existed, is
     * not a report. When that route yields nothing the standard {@code codecov} directories below
     * {@code .build} are scanned, which is what makes the measurement survive that version churn.
     */
    static Path codecovReport(String swift, VerificationContext context) {
        ProcessResult printed = context.runner().run(ProcessSpec.builder(
                        SwiftToolchain.command(swift, context, List.of("test", "--show-codecov-path")),
                        context.projectRoot())
                .environment(SwiftToolchain.environment(context))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(64 * 1024)
                .build());
        Path candidate = codecovJson(lastNonBlankLine(printed.stdout()));
        return candidate != null ? candidate : codecovJsonUnderBuild(context.projectRoot());
    }

    /**
     * @param printed the last non-blank line {@code swift test --show-codecov-path} printed, or {@code null}
     * @return the JSON file the printed path names, when it names one
     */
    static Path codecovJson(String printed) {
        if (printed == null || printed.isBlank()) {
            return null;
        }
        Path path = pathOf(printed);
        if (path == null) {
            return null;
        }
        if (Files.isRegularFile(path) && isJson(path)) {
            return path;
        }
        if (Files.isDirectory(path)) {
            // Some toolchains print the codecov directory rather than the file inside it.
            return firstJson(path);
        }
        return null;
    }

    /**
     * @return a {@code codecov/*.json} file below {@code .build}, or {@code null} when there is none.
     *
     * <p>The scan is deliberately shallow rather than a full walk: {@code .build} contains a checkout of
     * every dependency, and walking it to look for one fixed subdirectory would read somebody else's whole
     * source tree. SwiftPM's two layouts - {@code .build/<config>/codecov} and
     * {@code .build/<triple>/<config>/codecov} - are both covered by looking at the direct children and
     * their build configurations.
     */
    static Path codecovJsonUnderBuild(Path projectRoot) {
        Path build = projectRoot.resolve(".build");
        if (!Files.isDirectory(build)) {
            return null;
        }
        List<Path> candidates = new ArrayList<>();
        candidates.add(build.resolve("codecov"));
        for (String configuration : BUILD_CONFIGURATIONS) {
            candidates.add(build.resolve(configuration).resolve("codecov"));
        }
        try (Stream<Path> entries = Files.list(build)) {
            for (Path directory : entries.filter(Files::isDirectory).sorted().toList()) {
                for (String configuration : BUILD_CONFIGURATIONS) {
                    candidates.add(directory.resolve(configuration).resolve("codecov"));
                }
            }
        } catch (IOException _) {
            // The candidates derived without listing are still tried; the printed path is the primary route.
        }
        for (Path candidate : candidates) {
            Path json = firstJson(candidate);
            if (json != null) {
                return json;
            }
        }
        return null;
    }

    /**
     * Deletes every file in the codecov directories below {@code .build}.
     *
     * <p>This is the step that makes a stale report impossible to mistake for this run's. It throws on a
     * file it cannot remove, because a report that may be from an earlier run is worse than no report: the
     * caller turns that into "not measured" rather than reading numbers it cannot attribute to this run.
     *
     * @throws IOException when the build tree cannot be listed or an output file cannot be removed
     */
    static void clearStaleCoverage(Path projectRoot) throws IOException {
        Path build = projectRoot.resolve(".build");
        if (!Files.isDirectory(build)) {
            return;
        }
        for (Path directory : codecovDirectories(build)) {
            try (Stream<Path> entries = Files.list(directory)) {
                for (Path entry : entries.toList()) {
                    deleteRecursively(entry);
                }
            }
        }
    }

    /** @return the existing {@code codecov} directories of the standard SwiftPM layouts. */
    static List<Path> codecovDirectories(Path build) throws IOException {
        List<Path> directories = new ArrayList<>();
        addDirectory(directories, build.resolve("codecov"));
        for (String configuration : BUILD_CONFIGURATIONS) {
            addDirectory(directories, build.resolve(configuration).resolve("codecov"));
        }
        try (Stream<Path> entries = Files.list(build)) {
            for (Path directory : entries.filter(Files::isDirectory).sorted().toList()) {
                for (String configuration : BUILD_CONFIGURATIONS) {
                    addDirectory(directories, directory.resolve(configuration).resolve("codecov"));
                }
            }
        }
        return directories;
    }

    private static void addDirectory(List<Path> directories, Path candidate) {
        if (Files.isDirectory(candidate) && !directories.contains(candidate)) {
            directories.add(candidate);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (Stream<Path> entries = Files.list(path)) {
                for (Path entry : entries.toList()) {
                    deleteRecursively(entry);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    /** @return the {@code *.json} file in a directory, in a stable order, or {@code null}. */
    static Path firstJson(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return null;
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(SwiftCoverageProvider::isJson)
                    .sorted()
                    .findFirst()
                    .orElse(null);
        } catch (IOException _) {
            return null;
        }
    }

    private static boolean isJson(Path path) {
        return path != null && path.getFileName() != null
                && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json");
    }

    /** @return the last non-blank line of an output, stripped, or {@code null} when there is none. */
    static String lastNonBlankLine(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        String[] lines = output.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].strip();
            if (!line.isEmpty()) {
                return line;
            }
        }
        return null;
    }

    /**
     * Parses an llvm-cov codecov export into a coverage summary.
     *
     * <p>The format is {@code data[].files[]}, each entry naming an absolute {@code filename} and carrying
     * a {@code summary} with {@code lines}, {@code branches}, {@code functions} and {@code regions}, each
     * with {@code count} and {@code covered}. Lines and branches are summed over every file the report
     * names; the per-file map is restricted to the project's own sources, so a dependency or the generated
     * runner that the exporter instrumented does not appear as if it were a class of the project.
     *
     * @param report      the codecov JSON file
     * @param projectRoot the project whose files may be attributed per file
     * @param runNote     what the test run was, carried into the note
     * @return the summary, or "not measured" when the report could not be read or names no project source
     */
    static CoverageReport parse(Path report, Path projectRoot, String runNote) {
        JsonNode root;
        try {
            root = MAPPER.readTree(Files.readString(report, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return CoverageReport.notMeasured(TOOL, "the llvm-cov codecov report " + report.getFileName()
                    + " could not be read (" + e.getClass().getSimpleName() + "): " + e.getMessage());
        }
        JsonNode data = root == null ? null : root.path("data");
        if (data == null || !data.isArray() || data.isEmpty()) {
            return CoverageReport.notMeasured(TOOL, "the llvm-cov codecov report " + report.getFileName()
                    + " carries no data section, so nothing was measured about the code under verification");
        }

        int lineCovered = 0;
        int lineTotal = 0;
        int branchCovered = 0;
        int branchTotal = 0;
        int countedFiles = 0;
        Map<String, Double> perClass = new LinkedHashMap<>();
        for (JsonNode block : data) {
            JsonNode files = block.path("files");
            if (!files.isArray()) {
                continue;
            }
            for (JsonNode file : files) {
                String name = projectSourceName(projectRoot, text(file.path("filename")));
                if (name == null) {
                    // A checked-out dependency, the generated test runner SwiftPM instruments for itself,
                    // or XCTest itself says nothing about the code under verification, so it is excluded
                    // from the totals as well as from the per-file map: a line nobody in this project wrote
                    // is not a line this project can cover.
                    continue;
                }
                JsonNode summary = file.path("summary");
                JsonNode lines = summary.path("lines");
                int total = lines.path("count").asInt(0);
                int covered = lines.path("covered").asInt(0);
                JsonNode branches = summary.path("branches");
                int totalBranches = branches.path("count").asInt(0);
                int coveredBranches = branches.path("covered").asInt(0);

                lineTotal += total;
                lineCovered += covered;
                branchTotal += totalBranches;
                branchCovered += coveredBranches;
                countedFiles++;
                perClass.put(name, total == 0 ? 0.0 : 100.0 * covered / total);
            }
        }
        if (countedFiles == 0) {
            return CoverageReport.notMeasured(TOOL, "the llvm-cov codecov report " + report.getFileName()
                    + " names no file of this project's own Swift sources (only build output, a dependency, "
                    + "a generated runner or XCTest itself), so nothing was measured about the code under "
                    + "verification");
        }
        String note = "llvm-cov codecov export; " + runNote + "; line and branch totals summed over the "
                + countedFiles + " file(s) of this project's own Swift sources the report names, so a line "
                + "in a dependency or a generated runner is not counted"
                + (branchTotal == 0
                        ? "; the toolchain instrumented no branch regions, so branch coverage is not measured "
                                + "rather than zero"
                        : "");
        return new CoverageReport(true, TOOL, note, lineCovered, lineTotal, branchCovered, branchTotal,
                perClass, List.of(report));
    }

    /**
     * @return the report's key for a file that is one of the project's own Swift sources, or {@code null}
     *     when the file is build output, a dependency, a generated runner or XCTest itself.
     *
     * <p>The key is relative to the project root where the file lies under it, which is what makes a
     * report readable, and the absolute name otherwise - the model expects a display name, not a path
     * that must resolve. {@code SwiftProjectDetector.isExcludedSource} is reused so that this class and
     * the detector cannot disagree about which directories are somebody else's code.
     */
    static String projectSourceName(Path projectRoot, String filename) {
        if (projectRoot == null || filename == null || filename.isBlank()) {
            return null;
        }
        Path path = pathOf(filename);
        if (path == null) {
            return null;
        }
        Path absolute = (path.isAbsolute() ? path : projectRoot.resolve(path)).toAbsolutePath().normalize();
        if (!SwiftProjectDetector.isSwiftSourceName(absolute)
                || SwiftProjectDetector.isExcludedSource(projectRoot, absolute)) {
            return null;
        }
        for (Path element : absolute) {
            if (NON_PROJECT_SEGMENTS.contains(element.toString())) {
                return null;
            }
        }
        Path fileName = absolute.getFileName();
        if (fileName != null && GENERATED_FILE_NAMES.contains(fileName.toString())) {
            return null;
        }
        Path root = projectRoot.toAbsolutePath().normalize();
        return absolute.startsWith(root) ? root.relativize(absolute).toString() : absolute.toString();
    }

    private static Path pathOf(String value) {
        try {
            return Path.of(value);
        } catch (InvalidPathException _) {
            return null;
        }
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isTextual()) {
            return null;
        }
        String value = node.asText();
        return value.isBlank() ? null : value;
    }
}
