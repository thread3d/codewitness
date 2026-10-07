package dev.codewitness.cpp.coverage;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.cpp.CppBuildTools;
import dev.codewitness.cpp.CppProjectDetector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Measures coverage by building the project with instrumentation and reading what gcov recorded.
 *
 * <p>C and C++ have no coverage tool of their own; the measurement comes from the compiler's own
 * instrumented build and from {@code gcov}, which every compiler that implements the GNU coverage format
 * ships. The provider therefore configures a <em>second</em> build directory with {@code --coverage} added
 * to the flags rather than through the project, builds it, runs the project's own CTest suite over it, and
 * asks gcov for a report per instrumented object. Nothing about the project's own build is changed, which is
 * what keeps the coverage build a measurement of the project rather than an edit to it.
 *
 * <p>The instrumented build is the reason this phase costs a full compile. It is also why the provider is
 * careful to keep its build directory in the run's artefact store: a coverage build inside the project tree
 * would leave instrumented objects behind for the next run to find.
 *
 * <p>A project without an instrumentable build is not a failure: the run says coverage was not measured and
 * names what was missing - no gcov on {@code PATH}, no test registered with {@code add_test}, a build that
 * did not compile. What it must never do is report zero coverage, which would read as a measurement.
 *
 * <p>gcov records lines and branches. Both are reported under the names the report model uses for lines and
 * branches; no figure is invented for anything the tool did not measure.
 */
public final class CppCoverageProvider implements VerificationTools.CoverageProvider {

    /** Subdirectory of the artefact store the gcov output is written into. */
    public static final String REPORT_DIRECTORY = "cpp/gcov";

    /** {@code <count>:<line>:<source>}, where the count is {@code -} for a line that cannot execute. */
    private static final Pattern SOURCE_LINE = Pattern.compile("^\\s*(\\S+):\\s*(\\d+):(.*)$");

    /** {@code branch  <n> taken <count>}, or {@code branch <n> taken <percent>%}, or {@code never executed}. */
    private static final Pattern BRANCH_LINE = Pattern.compile("^\\s*branch\\s+\\d+\\s+(.*)$");

    /** The source path a gcov document names, which is the key its coverage is reported under. */
    private static final Pattern SOURCE_OF = Pattern.compile("^\\s*-:\\s*0:Source:(.*)$");

    @Override
    public String id() {
        return "gcov-coverage";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.CPP);
    }

    @Override
    public CoverageReport measure(VerificationContext context) {
        Path sourceDir = CppProjectDetector.buildRoot(context.projectRoot());
        if (sourceDir == null) {
            return CoverageReport.notMeasured(id(),
                    "no CMakeLists.txt declaring a project was found, so there is nothing to instrument");
        }
        if (CppProjectDetector.ctestTests(context.projectRoot()).isEmpty()) {
            return CoverageReport.notMeasured(id(),
                    "the project registers no test with add_test, so there are no tests to measure coverage "
                            + "over");
        }
        String gcov = CppBuildTools.resolveExecutable("gcov");
        if (gcov == null) {
            return CoverageReport.notMeasured(id(),
                    "no gcov executable was found on PATH, and coverage in C and C++ comes from the "
                            + "compiler's instrumented build read by gcov");
        }

        CppBuildTools tools = new CppBuildTools(context.runner());
        Path coverageBuild = tools.coverageDirectory(context);
        Path gcovDirectory = tools.gcovDirectory(context);
        try {
            // Reports from an earlier attempt would be indistinguishable from fresh ones, and a stale
            // .gcda from a previous run would be merged into this one's counts.
            deleteRecursively(coverageBuild);
            deleteRecursively(gcovDirectory);
            Files.createDirectories(coverageBuild);
            Files.createDirectories(gcovDirectory);
        } catch (IOException e) {
            return CoverageReport.notMeasured(id(), "cannot prepare the coverage directories under "
                    + context.artifacts().artifactsDirectory() + ": " + e);
        }

        String cmake = CppBuildTools.cmake(context.projectInfo(), context);
        List<String> configure = CppBuildTools.configureCommand(cmake, sourceDir, coverageBuild, true);
        ProcessResult configured = context.runner().run(ProcessSpec.builder(configure, context.projectRoot())
                .environment(CppBuildTools.environment(context))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(2_000_000)
                .build());
        if (!configured.started() || !configured.success()) {
            return CoverageReport.notMeasured(id(), "the instrumented configure did not succeed ("
                    + configured.describeOutcome() + "); last output: "
                    + CppBuildTools.lastLines(configured.combinedOutput(), 6));
        }
        context.emit("coverage", "configured an instrumented build in " + coverageBuild);

        List<String> build = CppBuildTools.buildCommand(cmake, coverageBuild);
        ProcessResult compiled = context.runner().run(ProcessSpec.builder(build, context.projectRoot())
                .environment(CppBuildTools.environment(context))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(4_000_000)
                .build());
        if (!compiled.started() || !compiled.success()) {
            return CoverageReport.notMeasured(id(), "the instrumented build did not succeed ("
                    + compiled.describeOutcome() + "); last output: "
                    + CppBuildTools.lastLines(compiled.combinedOutput(), 6));
        }

        List<String> tests = CppBuildTools.testCommand(coverageBuild,
                coverageBuild.resolve("ctest-junit.xml"));
        ProcessResult tested = context.runner().run(ProcessSpec.builder(tests, context.projectRoot())
                .environment(CppBuildTools.environment(context))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(2_000_000)
                .build());
        // A failing test is not a reason to give up on coverage: the instrumented run recorded whatever it
        // executed, and the note says whether the suite passed so that a reader knows what the figures cover.
        context.emit("coverage", "ran the instrumented tests: " + tested.describeOutcome());

        List<Path> gcnoFiles = instrumentedObjects(coverageBuild);
        if (gcnoFiles.isEmpty()) {
            return CoverageReport.notMeasured(id(),
                    "the instrumented build produced no .gcno file, so nothing was instrumented; the "
                            + "compiler flag that instruments a build is --coverage, which the provider adds "
                            + "to CMAKE_C_FLAGS and CMAKE_CXX_FLAGS");
        }
        List<String> gcovNotes = new ArrayList<>();
        for (Path gcno : gcnoFiles) {
            Path objectDirectory = gcno.getParent();
            List<String> command = List.of(gcov, "-b", "-c", "-o", objectDirectory.toString(), gcno.toString());
            ProcessResult result = context.runner().run(ProcessSpec.builder(command, gcovDirectory)
                    .environment(CppBuildTools.environment(context))
                    .timeout(context.config().limits().timeout())
                    .maxOutputBytes(1_000_000)
                    .build());
            if (!result.started() || !result.success()) {
                gcovNotes.add("gcov on " + gcno.getFileName() + " did not succeed ("
                        + result.describeOutcome() + "): " + CppBuildTools.lastLines(result.combinedOutput(), 2));
            }
        }

        List<Path> reports = gcovReports(gcovDirectory);
        if (reports.isEmpty()) {
            return CoverageReport.notMeasured(id(), "gcov produced no report: "
                    + (gcovNotes.isEmpty() ? "no .gcov file was written into " + gcovDirectory
                            : String.join("; ", gcovNotes)));
        }
        String note = "gcov -b -c over a second configure of the project with --coverage; the tests were "
                + tested.describeOutcome() + (gcovNotes.isEmpty() ? "" : "; " + String.join("; ", gcovNotes));
        return combine(reports, note, context.projectRoot());
    }

    /**
     * @return every {@code .gcno} the instrumented build wrote, sorted, skipping the compiler
     *     identification probes CMake compiles during a configure
     */
    static List<Path> instrumentedObjects(Path coverageBuild) {
        List<Path> objects = new ArrayList<>();
        if (coverageBuild == null || !Files.isDirectory(coverageBuild)) {
            return objects;
        }
        try (Stream<Path> walk = Files.walk(coverageBuild)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".gcno"))
                    .filter(path -> !path.toString().contains("CompilerId"))
                    .filter(path -> !path.toString().contains("CMakeFiles" + java.io.File.separator + "4."))
                    .sorted()
                    .forEach(objects::add);
        } catch (IOException _) {
            // an unreadable build tree yields nothing, which the caller reports rather than guesses about
        }
        return List.copyOf(objects);
    }

    /** @return every {@code .gcov} report gcov wrote into a directory, sorted. */
    static List<Path> gcovReports(Path gcovDirectory) {
        List<Path> reports = new ArrayList<>();
        if (gcovDirectory == null || !Files.isDirectory(gcovDirectory)) {
            return reports;
        }
        try (Stream<Path> walk = Files.walk(gcovDirectory)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".gcov"))
                    .sorted()
                    .forEach(reports::add);
        } catch (IOException _) {
            // an unreadable directory yields no reports, which the caller reports rather than guesses about
        }
        return List.copyOf(reports);
    }

    /**
     * Parses every gcov report in a list and combines them.
     *
     * @param gcovFiles   the {@code .gcov} documents
     * @param note        explanation carried into the report
     * @param projectRoot project root, used to shorten the source paths the reports name
     * @return the combined measurement, or a not-measured report when none of them held an instrumented line
     */
    static CoverageReport combine(List<Path> gcovFiles, String note, Path projectRoot) {
        int lineCovered = 0;
        int lineTotal = 0;
        int branchCovered = 0;
        int branchTotal = 0;
        Map<String, int[]> perSource = new LinkedHashMap<>();
        List<String> unreadable = new ArrayList<>();
        List<Path> read = new ArrayList<>();
        int external = 0;
        for (Path file : gcovFiles) {
            String text;
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                unreadable.add(file.getFileName() + " (" + e.getMessage() + ")");
                continue;
            }
            FileCoverage coverage = parseFile(text);
            if (!coverage.coverable()) {
                continue;
            }
            if (!isTheProjectsOwn(projectRoot, coverage.source())) {
                // The instrumentation covers everything the compiler compiled, standard library headers
                // included. A line in a header nobody in this project wrote is not a line this project can
                // cover, and counting it would make the figure a statement about the C++ standard library.
                external++;
                continue;
            }
            read.add(file);
            lineCovered += coverage.lineCovered();
            lineTotal += coverage.lineTotal();
            branchCovered += coverage.branchCovered();
            branchTotal += coverage.branchTotal();
            // The same source can be compiled into two targets, in which case gcov writes two documents for
            // it; summing them reports the file once with everything that ran against it.
            int[] totals = perSource.computeIfAbsent(shorten(projectRoot, coverage.source()), ignored -> new int[2]);
            totals[0] += coverage.lineCovered();
            totals[1] += coverage.lineTotal();
        }
        if (lineTotal == 0) {
            return CoverageReport.notMeasured("gcov-coverage",
                    "the gcov report(s) contained no instrumented line of the project's own"
                            + (unreadable.isEmpty() ? "" : "; unreadable: " + String.join(", ", unreadable)));
        }
        Map<String, Double> perFile = new LinkedHashMap<>();
        perSource.forEach((source, totals) -> perFile.put(source,
                totals[1] == 0 ? 0.0 : 100.0 * totals[0] / totals[1]));
        String fullNote = note + (external == 0 ? ""
                : "; " + external + " report(s) for a header outside the project were excluded, because a "
                        + "line in a standard library header is not a line this project can cover")
                + (unreadable.isEmpty() ? "" : "; unreadable: " + String.join(", ", unreadable));
        return new CoverageReport(true, "gcov-coverage", fullNote, lineCovered, lineTotal, branchCovered,
                branchTotal, perFile, read);
    }

    /**
     * Parses one {@code .gcov} document.
     *
     * <p>The format is one line per source line, {@code <count>:<line>:<text>}, with a {@code -} count for a
     * line that cannot execute and a run of {@code #} for one that can but did not. Branch lines follow
     * the line they belong to. Everything else - the source, graph and data headers, the run count and the
     * per-function summary gcov prints before the body - is metadata and is not counted.
     *
     * @param gcovText the document
     * @return the counts it records, with an empty source when the document names none
     */
    public static CoverageReport parse(String gcovText, String note) {
        FileCoverage coverage = parseFile(gcovText);
        if (!coverage.coverable()) {
            return CoverageReport.notMeasured("gcov-coverage", note);
        }
        return new CoverageReport(true, "gcov-coverage", note, coverage.lineCovered(), coverage.lineTotal(),
                coverage.branchCovered(), coverage.branchTotal(),
                Map.of(coverage.source(), 100.0 * coverage.lineCovered() / coverage.lineTotal()), List.of());
    }

    /** Parses one {@code .gcov} document into the counts it records. */
    public static FileCoverage parseFile(String gcovText) {
        if (gcovText == null || gcovText.isEmpty()) {
            return new FileCoverage("", 0, 0, 0, 0);
        }
        String source = "";
        int lineCovered = 0;
        int lineTotal = 0;
        int branchCovered = 0;
        int branchTotal = 0;
        boolean lastLineCoverable = false;
        for (String raw : gcovText.split("\\R")) {
            Matcher header = SOURCE_OF.matcher(raw);
            if (header.matches()) {
                source = header.group(1).strip();
                continue;
            }
            Matcher branch = BRANCH_LINE.matcher(raw);
            if (branch.matches()) {
                if (lastLineCoverable) {
                    branchTotal++;
                    if (taken(branch.group(1))) {
                        branchCovered++;
                    }
                }
                continue;
            }
            Matcher line = SOURCE_LINE.matcher(raw);
            if (!line.matches()) {
                lastLineCoverable = false;
                continue;
            }
            int number;
            try {
                number = Integer.parseInt(line.group(2).strip());
            } catch (NumberFormatException _) {
                lastLineCoverable = false;
                continue;
            }
            if (number <= 0) {
                // Line zero is gcov's own header: the source, graph, data and run records.
                lastLineCoverable = false;
                continue;
            }
            String count = line.group(1).strip();
            if (count.equals("-")) {
                lastLineCoverable = false;
                continue;
            }
            lastLineCoverable = true;
            lineTotal++;
            if (isExecuted(count)) {
                lineCovered++;
            }
        }
        return new FileCoverage(source, lineCovered, lineTotal, branchCovered, branchTotal);
    }

    /**
     * @return {@code true} when a gcov execution count is a number greater than zero.
     *
     * <p>A count of {@code #####} or {@code =====} is a line that can execute and did not; a count of
     *     {@code -} never reaches this test because it is not a coverable line at all.
     */
    static boolean isExecuted(String count) {
        if (count.isEmpty() || !Character.isDigit(count.charAt(0))) {
            return false;
        }
        try {
            return Long.parseLong(count) > 0;
        } catch (NumberFormatException _) {
            return false;
        }
    }

    /**
     * @param remainder what follows {@code branch <n> taken}, which gcov writes either as a count
     *     ({@code 3}), as a percentage ({@code 66.67%}) or as the words {@code never executed}
     * @return {@code true} when the branch was taken at least once
     */
    static boolean taken(String remainder) {
        if (remainder == null) {
            return false;
        }
        String value = remainder.strip();
        if (value.startsWith("never")) {
            return false;
        }
        if (value.startsWith("taken")) {
            // gcov writes "taken 3", "taken 66.67%" or "taken 2 (fallthrough)"; the word is not the number.
            value = value.substring("taken".length()).strip();
        }
        if (value.endsWith("%")) {
            value = value.substring(0, value.length() - 1).strip();
        }
        int space = value.indexOf(' ');
        if (space > 0) {
            value = value.substring(0, space);
        }
        try {
            return Double.parseDouble(value) > 0;
        } catch (NumberFormatException _) {
            return false;
        }
    }

    /**
     * @return {@code true} when a gcov document describes the project's own code: a source under the project
     *     root, or a path gcov wrote relative to somewhere this adapter cannot place - which is kept rather
     *     than dropped, because a figure that silently discarded it would be wrong in the other direction.
     */
    static boolean isTheProjectsOwn(Path projectRoot, String source) {
        if (projectRoot == null || source == null || source.isBlank()) {
            return true;
        }
        try {
            Path path = Path.of(source);
            if (!path.isAbsolute()) {
                return true;
            }
            return path.normalize().startsWith(projectRoot.toAbsolutePath().normalize());
        } catch (RuntimeException _) {
            return true;
        }
    }

    /** @return a gcov source path shortened to be relative to the project when it lies under it. */
    static String shorten(Path projectRoot, String source) {
        if (projectRoot == null || source == null || source.isBlank()) {
            return source == null ? "" : source;
        }
        try {
            Path path = Path.of(source);
            Path root = projectRoot.toAbsolutePath().normalize();
            if (path.isAbsolute() && path.startsWith(root)) {
                return root.relativize(path).toString();
            }
        } catch (RuntimeException _) {
            // not a usable path: the report keeps gcov's own words
        }
        return source;
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /**
     * The line and branch counts one gcov document records.
     *
     * @param source        the source path the document names, or the empty string
     * @param lineCovered   lines that executed
     * @param lineTotal     lines that can execute
     * @param branchCovered branches taken at least once
     * @param branchTotal   branches instrumented
     */
    public record FileCoverage(String source, int lineCovered, int lineTotal, int branchCovered, int branchTotal) {

        /** @return {@code true} when the document recorded at least one line that can execute. */
        public boolean coverable() {
            return lineTotal > 0;
        }

        /** @return the line coverage as a percentage, or zero when nothing was instrumented. */
        public double linePercent() {
            return lineTotal == 0 ? 0.0 : 100.0 * lineCovered / lineTotal;
        }
    }
}
