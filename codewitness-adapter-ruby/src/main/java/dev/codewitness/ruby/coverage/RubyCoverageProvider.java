package dev.codewitness.ruby.coverage;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.ruby.RubyInterpreter;
import dev.codewitness.ruby.RubyProjectDetector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Measures Ruby coverage from SimpleCov's own result set.
 *
 * <p>Ruby ships no coverage tool, so unlike the PHP adapter there is no interpreter extension to probe
 * for and no "driver is missing" branch here: SimpleCov is a gem the project installs, and it only writes
 * a result set when the project loads it. The interpreter probe records whether the gem is present as a
 * hint, but presence is not the same as use - a project can have SimpleCov in its bundle and never call
 * {@code SimpleCov.start} - so the measurement is decided by the report the run produces, not by the
 * probe. When no report appears the answer is "coverage was not measured", with the reason; never zero,
 * because a zero is indistinguishable from a project whose tests execute nothing.
 *
 * <p>SimpleCov is activated by requiring it before the tests load, which is what the opt-in environment
 * variables {@code COVERAGE} and {@code SIMPLECOV} are for: a project that gates
 * {@code require 'simplecov'} behind one of them - the common arrangement in a suite that does not want
 * coverage on every local run - still measures, because the run turns the gate on. They are conventional
 * names, not SimpleCov's own; a project that gates on something else produces no report, and the report
 * says so rather than pretending the numbers are zero.
 *
 * <p>SimpleCov writes to a fixed location inside the project, {@code coverage/.resultset.json}, so that
 * file - not a path in the run's artefact store - is what this provider reads. Because the location is
 * fixed, a result set from an earlier run would otherwise be indistinguishable from this run's: the file
 * is deleted before RSpec starts, so a run that produced nothing can never be read as a run that produced
 * these numbers. The PHP adapter's Clover report has the same hazard from its build caches, and answers
 * it the same way.
 *
 * <p>Only the project's own files are counted. A result set can name any file the run loaded, and the
 * ones that matter here are the ones this project wrote: a file below a dependency tree, a path outside
 * the project, or a stale entry for a file that no longer exists is left out, because a dependency's
 * coverage says nothing about the code under verification.
 */
public final class RubyCoverageProvider implements VerificationTools.CoverageProvider {

    /**
     * The coverage tool as it is named in the report.
     *
     * <p>SimpleCov both collects and writes, so the tool is named once for the provider and for the
     * report; there is nothing else in a Ruby coverage measurement to name.
     */
    private static final String TOOL = "simplecov";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String id() {
        return "simplecov";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.RUBY);
    }

    @Override
    public CoverageReport measure(VerificationContext context) {
        List<String> command = coverageCommand(context);
        if (command.isEmpty()) {
            return CoverageReport.notMeasured(TOOL, "RSpec was not found, so the suite SimpleCov would "
                    + "instrument could not be run: neither bin/rspec (a Bundler binstub the build's bundle "
                    + "install creates), nor bundle exec rspec, nor an RSpec below vendor/bundle or on PATH; "
                    + "coverage was not measured rather than reported as zero");
        }
        Path report = reportPath(context);
        try {
            // Deleted before the run, so that the result set this run is credited with is the one it wrote
            // and a suite that wrote nothing is never read as a suite that produced these numbers.
            Files.deleteIfExists(report);
        } catch (IOException e) {
            return CoverageReport.notMeasured(TOOL, "cannot clear the previous SimpleCov result set "
                    + report + ": " + e);
        }
        context.emit("coverage", "measuring coverage with SimpleCov through RSpec");

        ProcessResult result = context.runner().run(ProcessSpec.builder(command, context.projectRoot())
                .environment(environment(context))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(1_000_000)
                .build());
        if (result.timedOut()) {
            // A killed run may have written a partial result set for the examples that finished; a
            // percentage describing an unfinished suite is not a measurement.
            return CoverageReport.notMeasured(TOOL, "RSpec was killed after the "
                    + context.config().limits().timeout().toSeconds() + "s timeout, so a partial SimpleCov "
                    + "result set is not a measurement; last output: " + result.tail(5));
        }
        if (!Files.isRegularFile(report)) {
            return CoverageReport.notMeasured(TOOL, "SimpleCov wrote no coverage/.resultset.json ("
                    + result.describeOutcome() + "); the project may not load SimpleCov, or the suite may "
                    + "not have run; last output: " + result.tail(5));
        }
        return parse(report, context.projectRoot(),
                "SimpleCov's result set written while RSpec ran the suite; SimpleCov's default JSON carries "
                        + "line data only, so branch coverage is not measured; " + result.describeOutcome());
    }

    /**
     * @return the environment the suite runs under, with the coverage opt-ins set
     *
     * <p>The sandbox replaces the child environment, so this map is the only channel through which the
     * project learns that a coverage run is wanted.
     * {@link RubyInterpreter#environment(ProjectInfo, Path, Path)} contributes the Bundler directories -
     * which is what makes the run see the gems this run's own {@code bundle install} installed - and the
     * two opt-in flags are added over them. Both spellings are set because projects differ on which one
     * they gate {@code SimpleCov.start} behind, and setting the one a project does not read costs nothing.
     */
    private static Map<String, String> environment(VerificationContext context) {
        Map<String, String> environment = new LinkedHashMap<>(RubyInterpreter.environment(
                context.projectInfo(), context.projectRoot(), context.artifacts().artifactsDirectory()));
        environment.put("COVERAGE", "true");
        environment.put("SIMPLECOV", "true");
        return environment;
    }

    /**
     * @return the argv that runs the suite under SimpleCov, or an empty list when RSpec is unavailable
     *
     * <p>This is deliberately not the test provider's command with an option appended: that one also asks
     * for a JSON report, and a coverage run that had to write into a directory the test phase prepared
     * would make the measurement depend on the order the phases ran in. The suite SimpleCov instruments is
     * the same suite, selected the same way - the detected test roots, unless the project's own
     * {@code .rspec} says where its tests are.
     */
    static List<String> coverageCommand(VerificationContext context) {
        List<String> rspec = RubyInterpreter.rspec(context.projectInfo(), context);
        if (rspec.isEmpty()) {
            return List.of();
        }
        List<String> command = new ArrayList<>(rspec);
        if (!Files.isRegularFile(context.projectRoot().resolve(".rspec"))) {
            for (Path directory : context.projectInfo().testSourceDirs()) {
                command.add(directory.toString());
            }
        }
        return List.copyOf(command);
    }

    /**
     * Where SimpleCov writes the result set.
     *
     * <p>The location is SimpleCov's, not this provider's: it is fixed below the project root, which is
     * why a stale file has to be deleted before a run rather than being written somewhere unique per run.
     */
    static Path reportPath(VerificationContext context) {
        return context.projectRoot().resolve("coverage").resolve(".resultset.json");
    }

    /**
     * Parses a SimpleCov result set into a coverage summary.
     *
     * @param report      the {@code coverage/.resultset.json} file
     * @param projectRoot the project whose files may be counted
     * @param note        explanation carried into the report
     * @return the summary, or "not measured" when the report could not be read or named no file of this
     *     project
     */
    public static CoverageReport parse(Path report, Path projectRoot, String note) {
        JsonNode root;
        try {
            root = MAPPER.readTree(Files.readString(report, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return CoverageReport.notMeasured(TOOL, "the SimpleCov result set " + report.getFileName()
                    + " could not be read (" + e.getClass().getSimpleName() + "): " + e.getMessage());
        }
        JsonNode coverage = coverageOf(root);
        if (coverage == null) {
            return CoverageReport.notMeasured(TOOL, "the SimpleCov result set " + report.getFileName()
                    + " carries no coverage section, so nothing was measured about the code under "
                    + "verification");
        }
        int linesCovered = 0;
        int linesTotal = 0;
        Map<String, Double> perFile = new LinkedHashMap<>();
        int countedFiles = 0;

        for (Iterator<Map.Entry<String, JsonNode>> files = coverage.fields(); files.hasNext(); ) {
            Map.Entry<String, JsonNode> file = files.next();
            Path path = projectFile(projectRoot, file.getKey());
            if (path == null) {
                continue;
            }
            countedFiles++;
            int covered = 0;
            int total = 0;
            JsonNode lines = file.getValue().path("lines");
            if (lines.isArray()) {
                for (JsonNode line : lines) {
                    // SimpleCov writes one entry per source line and uses null for one a test cannot
                    // execute - a blank line, a comment, a declaration. Counting those as uncovered would
                    // put lines in the denominator that no test could ever move, which is how a project
                    // reads as badly covered when it is not.
                    if (line.isNull() || line.isMissingNode()) {
                        continue;
                    }
                    total++;
                    if (line.asInt(0) > 0) {
                        covered++;
                    }
                }
            }
            linesCovered += covered;
            linesTotal += total;
            perFile.put(relativeName(projectRoot, path), total == 0 ? 0.0 : 100.0 * covered / total);
        }
        if (countedFiles == 0) {
            return CoverageReport.notMeasured(TOOL, "the SimpleCov result set " + report.getFileName()
                    + " names no file of this project (only files under a dependency tree, outside the "
                    + "project, or already deleted), so nothing was measured about the code under "
                    + "verification");
        }
        // Branch coverage is not reported: SimpleCov's default JSON carries one entry per line and no
        // branch data at all, and a zero would claim a project with branches has none. Zero total is this
        // model's way of saying "no branch data", which is what the PHP adapter reports for a Clover
        // report without condition lines.
        return new CoverageReport(true, TOOL, note, linesCovered, linesTotal, 0, 0, perFile,
                List.of(report));
    }

    /**
     * @param root the parsed result set file
     * @return the {@code coverage} object of the result set to read, or {@code null} when there is none
     *
     * <p>A {@code .resultset.json} maps a command name to one result set per process, so the file can hold
     * several. RSpec's own name is preferred and the first result set is the fallback, because the
     * measurement is of the suite this run executed and RSpec's set is the one that describes it; the
     * fallback is what a project that renamed its command produces, and a number from a differently named
     * result set is still this project's code, measured by the run that just happened.
     */
    static JsonNode coverageOf(JsonNode root) {
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode selected = null;
        for (Iterator<Map.Entry<String, JsonNode>> fields = root.fields(); fields.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!entry.getValue().isObject()) {
                continue;
            }
            if (selected == null) {
                selected = entry.getValue();
            }
            if (entry.getKey().contains("RSpec")) {
                selected = entry.getValue();
                break;
            }
        }
        return coverageNode(selected);
    }

    /**
     * @param resultSet one command's result set, or {@code null}
     * @return the {@code coverage} object inside it
     *
     * <p>A result set is normally {@code {"coverage": {...}, "timestamp": n}}. SimpleCov can also nest a
     * merged result set under an {@code all} key, so that shape is followed one level down; the depth
     * bound stops a report that names itself from being followed forever.
     */
    private static JsonNode coverageNode(JsonNode resultSet) {
        JsonNode node = resultSet;
        for (int depth = 0; depth < 4 && node != null && node.isObject(); depth++) {
            JsonNode coverage = node.path("coverage");
            if (coverage.isObject()) {
                return coverage;
            }
            node = node.path("all");
        }
        return null;
    }

    /**
     * @return the report's file when it is a file this project wrote, otherwise {@code null}
     *
     * <p>A file counts when it exists, lies inside the project root and is not in a dependency tree, a
     * cache or another excluded directory. The existence check is what excludes a stale entry from an
     * earlier run, and the root check uses real paths because SimpleCov names the path it opened, which on
     * a machine with a symlinked checkout is not the path the run was started from.
     */
    private static Path projectFile(Path projectRoot, String name) {
        if (projectRoot == null || name == null || name.isBlank()) {
            return null;
        }
        Path path;
        try {
            path = Path.of(name);
        } catch (InvalidPathException _) {
            return null;
        }
        if (!path.isAbsolute() || !Files.isRegularFile(path)) {
            return null;
        }
        if (!realPath(path).startsWith(realPath(projectRoot))) {
            return null;
        }
        if (RubyProjectDetector.isExcludedSource(projectRoot, path)) {
            return null;
        }
        return path;
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException _) {
            return path.toAbsolutePath().normalize();
        }
    }

    /** @return the report's name for a file, relative to the project root where that is possible. */
    private static String relativeName(Path projectRoot, Path path) {
        try {
            return realPath(projectRoot).relativize(realPath(path)).toString();
        } catch (IllegalArgumentException _) {
            return path.getFileName().toString();
        }
    }
}
