package dev.codewitness.kotlin;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.nio.file.Path;
import java.util.List;

/**
 * Runs a Kotlin project's existing tests and reads the results from the raw XML reports.
 *
 * <p>Stale reports are deleted first: a run that appears to pass because it re-read an old report is worse
 * than a run that fails. When the build tool ran but produced no reports, that is reported as "the tests did
 * not execute" with the tool's own output, never as a pass - the case that matters most is a Kotlin test
 * source that no build ever compiled, which produces exactly that evidence.
 */
public final class KotlinTestProvider implements VerificationTools.TestRunner {

    private final KotlinBuildTools tools;
    private final KotlinBuildSystem buildSystem;

    public KotlinTestProvider(KotlinBuildTools tools, KotlinBuildSystem buildSystem) {
        this.tools = tools;
        this.buildSystem = buildSystem;
    }

    @Override
    public String id() {
        return "kotlin-tests-" + buildSystem.id();
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.KOTLIN)
                && project.buildSystem().id().equals(buildSystem.id());
    }

    @Override
    public TestResult runTests(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        if (!project.hasTests()) {
            return TestResult.notExecuted("no Kotlin test source directory was detected");
        }
        List<String> command = testCommand(context);
        if (command.isEmpty()) {
            return TestResult.notExecuted("no " + buildSystem.id() + " executable found");
        }

        KotlinTestReportParser.clearReports(project);
        ProcessResult result = context.runner().run(ProcessSpec.builder(command, buildSystem.buildRoot(project))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(1_000_000)
                .build());
        if (result.timedOut()) {
            // A killed build can still have written reports for the tests that finished before the kill,
            // so the timeout is checked before the reports are looked for or parsed.
            return TestResult.notExecuted(buildSystem.id() + " was killed after the "
                    + context.config().limits().timeout().toSeconds() + "s timeout; the partial output is not a "
                    + "complete test run: " + lastLines(result.combinedOutput(), 5));
        }

        List<Path> reports = KotlinTestReportParser.findReportFiles(project);
        if (reports.isEmpty()) {
            return TestResult.notExecuted("the build tool produced no test reports ("
                    + result.describeOutcome() + "); last output: " + lastLines(result.combinedOutput(), 5));
        }
        TestResult parsed = KotlinTestReportParser.parse(reports, "parsed from " + reports.size()
                + " XML report file(s); " + result.describeOutcome());
        if (!result.success() && !parsed.anyFailure()) {
            return new TestResult(parsed.executed(), parsed.note() + " (build tool exit code was non-zero)",
                    parsed.total(), parsed.passed(), parsed.failed(), parsed.errors(), parsed.skipped(),
                    parsed.duration(), parsed.cases(), parsed.reports());
        }
        return parsed;
    }

    @Override
    public List<String> testCommand(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        List<String> executable = tools.resolve(project, buildSystem.buildRoot(project));
        if (executable == null) {
            return List.of();
        }
        return KotlinBuildTools.command(executable, buildSystem.testGoals());
    }

    @Override
    public List<String> replayCommand(VerificationContext context, TestCaseResult testCase) {
        // The build command is the only record of which executable was resolved, so it is what the replay
        // command is derived from; a bare `mvn`/`./gradlew` is the fallback when there is no build.
        List<String> build = context.state().build().command();
        boolean gradle = buildSystem.id().equals("gradle");
        String executable = build.isEmpty() ? (gradle ? "./gradlew" : "mvn") : build.getFirst();
        if (gradle) {
            return List.of(executable, "test", "--tests", testCase.suite() + "." + testCase.name());
        }
        return List.of(executable, "-Dtest=" + testCase.suite() + "#" + testCase.name(), "test");
    }

    private static String lastLines(String text, int count) {
        if (text == null || text.isBlank()) {
            return "(no output)";
        }
        String[] lines = text.strip().split("\\R");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, lines.length - count); i < lines.length; i++) {
            sb.append(lines[i].strip()).append(" | ");
        }
        return sb.toString();
    }
}
