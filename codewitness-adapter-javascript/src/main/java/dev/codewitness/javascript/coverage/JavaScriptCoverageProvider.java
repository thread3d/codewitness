package dev.codewitness.javascript.coverage;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.javascript.NodeRuntime;
import dev.codewitness.javascript.JavaScriptTestProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Measures coverage with Node's built-in V8 coverage, read from its LCOV export.
 *
 * <p>Nothing is installed: {@code node --test --experimental-test-coverage --test-reporter=lcov} runs
 * the project's own tests under V8 instrumentation and writes the standard LCOV format. The numbers are
 * read from that file rather than from the console table, and the raw file is kept as an artefact.
 *
 * <p>The provider reports what V8 measured and no more. V8 instruments the files the test run actually
 * loaded, so a source file no test imports does not appear in the report at all; the note says that
 * explicitly instead of presenting "100% of instrumented lines" as if it were whole-project coverage.
 * Coverage never decides a verdict, so a conservative reading is the honest one.
 */
public final class JavaScriptCoverageProvider implements VerificationTools.CoverageProvider {

    @Override
    public String id() {
        return "node-v8-coverage";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.JAVASCRIPT);
    }

    @Override
    public CoverageReport measure(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        List<Path> files = JavaScriptTestProvider.testFiles(context);
        if (files.isEmpty()) {
            return CoverageReport.notMeasured(id(), "no Node-runnable test file was detected, so there is "
                    + "nothing to measure coverage over");
        }
        Path report;
        try {
            report = JavaScriptTestProvider.prepareReport(context, "javascript/coverage/lcov.info");
        } catch (RuntimeException e) {
            return CoverageReport.notMeasured(id(), "cannot prepare the coverage report: " + e.getMessage());
        }
        String node = NodeRuntime.node(project, context);
        List<String> command = new ArrayList<>(List.of(
                node, "--test", "--experimental-test-coverage", "--test-reporter=lcov",
                "--test-reporter-destination=" + report));
        files.forEach(file -> command.add(file.toString()));

        context.emit("coverage", "running node --test with V8 coverage over " + files.size() + " test file(s)");
        ProcessResult result = context.runner().run(ProcessSpec.builder(command, context.projectRoot())
                .environment(NodeRuntime.environment(context))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(1_000_000)
                .build());
        if (result.timedOut()) {
            // The killed run may have written a partial LCOV file, so the timeout is checked before
            // that file is looked for or parsed.
            return CoverageReport.notMeasured(id(), "node --test with V8 coverage was killed after the "
                    + context.config().limits().timeout().toSeconds() + "s timeout; a partial coverage "
                    + "report is not a measurement");
        }
        if (!Files.isRegularFile(report)) {
            return CoverageReport.notMeasured(id(), "node produced no LCOV report (" + result.describeOutcome()
                    + "); last output: " + NodeRuntime.lastLines(result.combinedOutput(), 4));
        }
        return parse(report, "node V8 coverage (LCOV); test run " + result.describeOutcome());
    }

    /** Parses an LCOV file into aggregate totals and per-file line coverage. */
    public static CoverageReport parse(Path report, String note) {
        String text;
        try {
            text = Files.readString(report, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return CoverageReport.notMeasured("node-v8-coverage", "cannot read " + report + ": " + e);
        }
        return parse(text, note, List.of(report));
    }

    /**
     * Parses LCOV text into aggregate totals and per-file line coverage.
     *
     * <p>A record's branch totals are taken from its {@code BRDA} records when it has any, and from its
     * {@code BRF}/{@code BRH} summary lines otherwise, so a branch is never counted twice.
     */
    public static CoverageReport parse(String text, String note, List<Path> reports) {
        if (text == null || text.isBlank()) {
            return CoverageReport.notMeasured("node-v8-coverage", "the LCOV report was empty");
        }
        Map<String, Double> perFile = new LinkedHashMap<>();
        int lineCovered = 0;
        int lineTotal = 0;
        int branchCovered = 0;
        int branchTotal = 0;

        String source = null;
        int sourceLines = 0;
        int sourceCovered = 0;
        int sourceBranches = 0;
        int sourceBranchesCovered = 0;
        int sourceBranchSummary = 0;
        int sourceBranchSummaryCovered = 0;

        for (String raw : text.split("\\R", -1)) {
            String line = raw.strip();
            if (line.startsWith("SF:")) {
                source = line.substring(3).strip();
                sourceLines = 0;
                sourceCovered = 0;
                sourceBranches = 0;
                sourceBranchesCovered = 0;
                sourceBranchSummary = 0;
                sourceBranchSummaryCovered = 0;
                continue;
            }
            if (line.equals("end_of_record")) {
                if (source != null) {
                    perFile.put(source, sourceLines == 0 ? 0.0 : 100.0 * sourceCovered / sourceLines);
                }
                lineCovered += sourceCovered;
                lineTotal += sourceLines;
                if (sourceBranches > 0) {
                    branchCovered += sourceBranchesCovered;
                    branchTotal += sourceBranches;
                } else {
                    branchCovered += sourceBranchSummaryCovered;
                    branchTotal += sourceBranchSummary;
                }
                source = null;
                continue;
            }
            if (line.startsWith("DA:")) {
                // DA:<line>,<execution count>[,<checksum>]
                String[] parts = line.substring(3).split(",");
                if (parts.length >= 2) {
                    sourceLines++;
                    if (counting(parts[1])) {
                        sourceCovered++;
                    }
                }
                continue;
            }
            if (line.startsWith("BRDA:")) {
                // BRDA:<line>,<block>,<branch>,<taken>; "-" means the branch was never reached.
                String[] parts = line.substring(5).split(",");
                if (parts.length >= 4) {
                    sourceBranches++;
                    if (!parts[3].equals("-") && counting(parts[3])) {
                        sourceBranchesCovered++;
                    }
                }
                continue;
            }
            if (line.startsWith("BRF:")) {
                sourceBranchSummary = number(line.substring(4), sourceBranchSummary);
                continue;
            }
            if (line.startsWith("BRH:")) {
                sourceBranchSummaryCovered = number(line.substring(4), sourceBranchSummaryCovered);
            }
        }
        if (lineTotal == 0) {
            return CoverageReport.notMeasured("node-v8-coverage",
                    "the LCOV report contained no instrumented line; V8 only reports files the test run "
                            + "actually loaded");
        }
        String effectiveNote = note + "; V8 reports only the files the tests loaded, so a file no test "
                + "imports does not appear and its lines are not counted";
        return new CoverageReport(true, "node-v8-coverage", effectiveNote, lineCovered, lineTotal,
                branchCovered, branchTotal, perFile, reports);
    }

    private static int number(String value, int fallback) {
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException _) {
            return fallback;
        }
    }

    private static boolean counting(String value) {
        try {
            return Double.parseDouble(value.strip()) > 0;
        } catch (NumberFormatException _) {
            return false;
        }
    }
}
