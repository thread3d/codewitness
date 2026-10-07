package dev.codewitness.go.coverage;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.go.GoTool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Measures coverage with Go's own {@code go test -coverprofile}.
 *
 * <p>Unlike Rust, Go has statement coverage built into its toolchain, so there is no optional
 * external tool whose absence has to be reported: coverage is available whenever the module can be
 * tested. The provider therefore runs the tests once with the profile flag and reads the numbers
 * back from the profile Go wrote, rather than from the console summary, which does not break the
 * per-file figures down.
 *
 * <p>The profile records statement blocks, not lines and not branches. Every statement in a block
 * that executed at least once is counted as covered, and the branch totals are reported as zero
 * rather than invented: a block count is not a branch count, and reporting one as the other would
 * overstate what was observed.
 */
public final class GoCoverageProvider implements VerificationTools.CoverageProvider {

    @Override
    public String id() {
        return "go-cover";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.GO);
    }

    @Override
    public CoverageReport measure(VerificationContext context) {
        if (!GoTool.hasManifest(context)) {
            return CoverageReport.notMeasured("go-cover", "no go.mod was found");
        }
        Path report = context.artifacts().artifactsDirectory().resolve("go/coverage.out");
        try {
            Files.createDirectories(report.getParent());
            // A stale profile from an earlier attempt would be indistinguishable from a fresh one, so
            // it is deleted before the run and only a file this run wrote is parsed.
            Files.deleteIfExists(report);
        } catch (IOException e) {
            return CoverageReport.notMeasured("go-cover",
                    "cannot prepare the coverage report directory: " + e);
        }
        String go = GoTool.go(context.projectInfo(), context);
        List<String> command = List.of(go, "test", "-coverprofile=" + report, "-count=1", "./...");
        context.emit("coverage", "running go test -coverprofile");

        ProcessResult result = context.runner().run(ProcessSpec.builder(command, context.projectRoot())
                .environment(GoTool.environment(context))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(2_000_000)
                .build());
        if (result.timedOut()) {
            // go test writes the profile as it goes, so a killed run leaves a partial one; it is
            // refused before it is looked for or parsed.
            return CoverageReport.notMeasured("go-cover", "go test -coverprofile was killed after the "
                    + context.config().limits().timeout().toSeconds() + "s timeout; a partial coverage "
                    + "profile is not a measurement");
        }

        if (!Files.isRegularFile(report)) {
            // go test writes the profile only when it could build and run the package, so a missing
            // file is a reported condition with the tool's own words, never a zero-coverage claim.
            return CoverageReport.notMeasured("go-cover",
                    "go test produced no coverage profile (" + result.describeOutcome() + "); last output: "
                            + GoTool.lastLines(result.combinedOutput(), 4));
        }
        return parse(report, "Go statement coverage; test run " + result.describeOutcome());
    }

    /**
     * Parses a Go coverage profile.
     *
     * <p>The format is one {@code mode: set|count|atomic} header line followed by one line per
     * statement block: {@code <importpath>/<file>.go>:<startLine>.<startCol>,<endLine>.<endCol>
     * <numStmt> <count>}. Only the statement count and execution count are needed, so the profile is
     * read without a parser generator and without trusting the file name layout beyond the {@code :}
     * that separates it from the position.
     *
     * @param report profile written by {@code go test -coverprofile}
     * @param note   explanation carried into the report, including the tool run's outcome
     * @return the measured coverage, or a not-measured report when the profile held no statements
     */
    static CoverageReport parse(Path report, String note) {
        List<String> lines;
        try {
            lines = Files.readAllLines(report, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return CoverageReport.notMeasured("go-cover", "cannot read " + report + ": " + e);
        }
        int lineCovered = 0;
        int lineTotal = 0;
        Map<String, Integer> coveredPerFile = new LinkedHashMap<>();
        Map<String, Integer> totalPerFile = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("mode:")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length < 3) {
                continue;
            }
            String location = parts[0];
            int colon = location.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            int statements = parseInt(parts[1]);
            long count = parseLong(parts[2]);
            if (statements <= 0) {
                continue;
            }
            String file = location.substring(0, colon);
            lineTotal += statements;
            totalPerFile.merge(file, statements, Integer::sum);
            if (count > 0) {
                lineCovered += statements;
                coveredPerFile.merge(file, statements, Integer::sum);
            }
        }
        if (lineTotal == 0) {
            return CoverageReport.notMeasured("go-cover", "the Go coverage profile contained no statements");
        }
        Map<String, Double> perFile = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : totalPerFile.entrySet()) {
            int covered = coveredPerFile.getOrDefault(entry.getKey(), 0);
            perFile.put(entry.getKey(), 100.0 * covered / entry.getValue());
        }
        return new CoverageReport(true, "go-cover", note, lineCovered, lineTotal, 0, 0, perFile, List.of(report));
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException _) {
            return 0;
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException _) {
            return 0;
        }
    }
}
