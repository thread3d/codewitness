package dev.codewitness.java;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.java.build.JavaBuildSystem;

import java.nio.file.Path;
import java.util.List;

/**
 * Runs the project's existing tests and reads the results from the raw XML reports.
 *
 * <p>Stale reports are deleted first: a run that appears to pass because it re-read an old report is
 * worse than a run that fails. When the build tool ran but produced no reports, that is reported as
 * "tests did not execute" with the tool's own output, not as a pass.
 */
public final class JavaTestProvider implements VerificationTools.TestRunner {

    private final BuildToolLauncher launcher;
    private final JavaBuildSystem buildSystem;

    public JavaTestProvider(BuildToolLauncher launcher, JavaBuildSystem buildSystem) {
        this.launcher = launcher;
        this.buildSystem = buildSystem;
    }

    @Override
    public String id() {
        return "java-tests-" + buildSystem.id();
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.buildSystem().id().equals(buildSystem.id());
    }

    @Override
    public TestResult runTests(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        if (!project.hasTests()) {
            return TestResult.notExecuted("no test source directory was detected");
        }
        List<String> command = testCommand(context);
        if (command.isEmpty()) {
            return TestResult.notExecuted("no " + buildSystem.id() + " executable found");
        }

        TestReportParser.clearReports(project);
        ProcessResult result = context.runner().run(ProcessSpec.builder(command, buildSystem.buildRoot(project))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(1_000_000)
                .build());

        List<Path> reports = TestReportParser.findReportFiles(project);
        if (reports.isEmpty()) {
            return TestResult.notExecuted("the build tool produced no test reports ("
                    + result.describeOutcome() + "); last output: " + lastLines(result.combinedOutput(), 5));
        }
        TestResult parsed = TestReportParser.parse(reports, "parsed from " + reports.size() + " XML report file(s); "
                + result.describeOutcome());
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
        List<String> executable = launcher.resolve(project, buildSystem.buildRoot(project));
        if (executable == null) {
            return List.of();
        }
        return BuildToolLauncher.command(executable, buildSystem.testGoals());
    }

    @Override
    public List<String> replayCommand(VerificationContext context, dev.codewitness.core.model.TestCaseResult testCase) {
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
            sb.append(lines[i]).append(" | ");
        }
        return sb.toString();
    }
}
