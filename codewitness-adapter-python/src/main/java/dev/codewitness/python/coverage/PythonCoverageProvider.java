package dev.codewitness.python.coverage;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.python.PythonInterpreter;
import dev.codewitness.python.PythonScripts;
import dev.codewitness.python.PythonTestProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Measures coverage through coverage.py.
 *
 * <p>coverage.py is the de-facto standard and is not bundled with Python, so its absence is a
 * normal, reported condition rather than a failure: when the interpreter cannot import it the report
 * says coverage was not measured, and no requirement is affected either way. When it is present the
 * tests are re-run under {@code coverage run}, and the numbers are read from coverage.py's own JSON
 * report rather than from its console output.
 *
 * <p>The same test runner is used as the test phase - pytest when available, the standard library
 * runner otherwise - so coverage always describes the suite that was actually executed.
 */
public final class PythonCoverageProvider implements VerificationTools.CoverageProvider {

    private static final Pattern NUMBER = Pattern.compile("\"%s\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern ENTRY = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\\{");

    @Override
    public String id() {
        return "python-coverage";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(dev.codewitness.core.model.LanguageId.PYTHON);
    }

    @Override
    public CoverageReport measure(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        if (!Boolean.parseBoolean(project.attributes().getOrDefault(PythonInterpreter.ATTR_HAS_COVERAGE, "false"))) {
            return CoverageReport.notMeasured("coverage.py",
                    "the project interpreter cannot import coverage; install it to measure coverage");
        }
        List<Path> testDirs = project.testSourceDirs();
        if (testDirs.isEmpty()) {
            return CoverageReport.notMeasured("coverage.py", "no Python test directory was detected");
        }
        String interpreter = PythonTestProvider.interpreter(project, context);
        PythonScripts.Scripts scripts = PythonScripts.write(context);
        boolean pytest = PythonTestProvider.usePytest(project);

        Path jsonReport = context.artifacts().artifactsDirectory().resolve("python/coverage.json");
        Path junit = context.artifacts().artifactsDirectory().resolve("python/coverage-junit.xml");
        try {
            Files.createDirectories(jsonReport.getParent());
            Files.deleteIfExists(jsonReport);
        } catch (IOException e) {
            return CoverageReport.notMeasured("coverage.py", "cannot prepare the coverage report directory: " + e);
        }

        List<String> run = new ArrayList<>();
        run.add(interpreter);
        run.add("-m");
        run.add("coverage");
        run.add("run");
        run.add("--source");
        run.add(String.join(",", project.sourceDirs().stream().map(Path::toString).toList()));
        if (pytest) {
            run.add("-m");
            run.add("pytest");
            run.add("--rootdir");
            run.add(context.projectRoot().toString());
            run.add("-p");
            run.add("no:cacheprovider");
            run.add("-q");
            testDirs.forEach(directory -> run.add(directory.toString()));
        } else {
            run.add(scripts.unittestRunner().toString());
            run.add("--xml");
            run.add(junit.toString());
            run.add("--root");
            run.add(context.projectRoot().toString());
            for (Path directory : testDirs) {
                run.add("--start");
                run.add(directory.toString());
            }
        }
        Map<String, String> environment = PythonInterpreter.environment(project, context.projectRoot(), List.of());
        ProcessResult runResult = context.runner().run(ProcessSpec.builder(run, context.projectRoot())
                .environment(environment)
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(1_000_000)
                .build());
        if (runResult.timedOut()) {
            // The killed run may have written a partial data file, so the timeout is checked before any
            // report is exported or parsed.
            return CoverageReport.notMeasured("coverage.py", "coverage.py was killed after the "
                    + context.config().limits().timeout().toSeconds() + "s timeout; a partial coverage "
                    + "report is not a measurement");
        }

        List<String> export = List.of(interpreter, "-m", "coverage", "json", "-o", jsonReport.toString());
        ProcessResult exportResult = context.runner().run(ProcessSpec.builder(export, context.projectRoot())
                .environment(environment)
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(200_000)
                .build());
        if (exportResult.timedOut()) {
            // The JSON export can be interrupted mid-write, leaving a truncated file that parses into
            // invented numbers, so it is refused before it is read.
            return CoverageReport.notMeasured("coverage.py", "coverage.py json was killed after the "
                    + context.config().limits().timeout().toSeconds() + "s timeout; a partial coverage "
                    + "report is not a measurement");
        }

        if (!Files.isRegularFile(jsonReport)) {
            return CoverageReport.notMeasured("coverage.py", "coverage produced no JSON report ("
                    + runResult.describeOutcome() + " / " + exportResult.describeOutcome() + "); last output: "
                    + PythonTestProvider.lastLines(exportResult.combinedOutput(), 4));
        }
        return parse(jsonReport, "coverage.py JSON; test run " + runResult.describeOutcome());
    }

    /** Parses coverage.py's JSON report without pulling a JSON library into the adapter. */
    static CoverageReport parse(Path report, String note) {
        String json;
        try {
            json = Files.readString(report, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return CoverageReport.notMeasured("coverage.py", "cannot read " + report + ": " + e);
        }
        String totals = objectAt(json, "totals");
        int covered = (int) numberAt(totals, "covered_lines");
        int total = (int) numberAt(totals, "num_statements");
        int branchCovered = (int) numberAt(totals, "covered_branches");
        int branchTotal = (int) numberAt(totals, "num_branches");
        Map<String, Double> perFile = new LinkedHashMap<>();
        String files = objectAt(json, "files");
        if (!files.isBlank()) {
            Matcher entries = ENTRY.matcher(files);
            while (entries.find()) {
                // Only entries whose name looks like a source file are kept: the same scan also walks
                // into per-function and per-class objects, whose keys are identifiers, not paths.
                String name = entries.group(1);
                if (!name.endsWith(".py") && !name.endsWith(".pyi")) {
                    continue;
                }
                String body = balancedAt(files, entries.end() - 1);
                String summary = objectAt(body, "summary");
                perFile.put(name, numberAt(summary, "percent_covered"));
            }
        }
        return new CoverageReport(true, "coverage.py", note, covered, total, branchCovered, branchTotal,
                perFile, List.of(report));
    }

    /** @return the substring of the object stored under {@code "key"}, or an empty string. */
    static String objectAt(String json, String key) {
        if (json == null) {
            return "";
        }
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\{").matcher(json);
        if (!matcher.find()) {
            return "";
        }
        return balancedAt(json, matcher.end() - 1);
    }

    /** @return the balanced brace/bracket expression starting at {@code start}, or the rest of the text. */
    static String balancedAt(String text, int start) {
        if (text == null || start < 0 || start >= text.length()) {
            return "";
        }
        char open = text.charAt(start);
        char close = open == '{' ? '}' : open == '[' ? ']' : 0;
        if (close == 0) {
            return "";
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == open) {
                depth++;
            } else if (c == close && --depth == 0) {
                return text.substring(start, i + 1);
            }
        }
        return text.substring(start);
    }

    private static double numberAt(String json, String key) {
        if (json == null || json.isBlank()) {
            return 0;
        }
        Matcher matcher = Pattern.compile(String.format(NUMBER.pattern(), Pattern.quote(key))).matcher(json);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
