package dev.codewitness.typescript;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runs a TypeScript project's existing tests with Node's own test runner.
 *
 * <p>The command is {@code node --test}, which needs no dependency, discovers the conventional test
 * files, and executes {@code .ts} sources directly by stripping their type annotations (Node 22.18 and
 * later, where type stripping is enabled by default). Results are read from the JUnit report the runner
 * writes, not from console output, and the raw report and console output are stored as artefacts.
 *
 * <p>The files are named explicitly rather than left to discovery, so a compiled copy of the tests
 * under {@code dist} cannot be run a second time and be mistaken for a second test suite. A {@code .tsx}
 * test cannot be executed by the runtime's type stripper, so it is skipped and the skip is reported
 * rather than silently treated as a pass.
 */
public final class TypeScriptTestProvider implements VerificationTools.TestRunner {

    @Override
    public String id() {
        return "node-test";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.TYPESCRIPT);
    }

    @Override
    public TestResult runTests(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        List<Path> files = testFiles(context);
        if (files.isEmpty()) {
            return TestResult.notExecuted("no Node-runnable test file was detected under "
                    + (project.testSourceDirs().isEmpty() ? "the project" : project.testSourceDirs()));
        }
        Path report = context.artifacts().artifactsDirectory().resolve("typescript/tests/node-junit.xml");
        try {
            Files.createDirectories(report.getParent());
            Files.deleteIfExists(report);
        } catch (IOException e) {
            return TestResult.notExecuted("cannot prepare the test report directory: " + e);
        }
        context.emit("tests", "running node --test over " + files.size() + " test file(s)");

        List<String> command = testCommand(context);

        ProcessResult result = context.runner().run(ProcessSpec.builder(command, context.projectRoot())
                .environment(NodeRuntime.environment(context))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(1_000_000)
                .build());
        if (result.timedOut()) {
            // A killed node run may still have written a JUnit report for the tests that finished
            // before the kill, so the timeout is checked before that report is looked for or parsed.
            return TestResult.notExecuted("node --test was killed after the "
                    + context.config().limits().timeout().toSeconds() + "s timeout; the partial output "
                    + "is not a complete test run: " + NodeRuntime.lastLines(result.combinedOutput(), 4));
        }
        if (!result.started()) {
            return TestResult.notExecuted("node could not be started: " + result.failureReason());
        }

        List<Path> reports = new ArrayList<>();
        if (!result.combinedOutput().isBlank()) {
            reports.add(context.artifacts().write("typescript/tests/node-test-output.txt", result.combinedOutput()));
        }
        if (!Files.isRegularFile(report)) {
            return TestResult.notExecuted("node --test produced no JUnit report ("
                    + result.describeOutcome() + "); last output: " + NodeRuntime.lastLines(result.combinedOutput(), 4));
        }
        reports.add(report);
        return TypeScriptTestReportParser.parse(report,
                "parsed from the Node JUnit report; " + result.describeOutcome(), reports);
    }

    @Override
    public List<String> testCommand(VerificationContext context) {
        List<Path> files = testFiles(context);
        if (files.isEmpty()) {
            return List.of();
        }
        Path report = context.artifacts().artifactsDirectory().resolve("typescript/tests/node-junit.xml");
        List<String> command = new ArrayList<>(List.of(
                NodeRuntime.node(context.projectInfo(), context),
                "--test", "--test-reporter=junit", "--test-reporter-destination=" + report));
        files.forEach(file -> command.add(file.toString()));
        return List.copyOf(command);
    }

    @Override
    public List<String> replayCommand(VerificationContext context, TestCaseResult testCase) {
        String node = NodeRuntime.node(context.projectInfo(), context);
        // Node can select a case by name pattern, which is the closest equivalent of "run this test".
        return List.of(node, "--test", "--test-name-pattern", testCase.name());
    }

    /** @return every test file the runtime can execute, with {@code .tsx} reported and skipped. */
    public static List<Path> testFiles(VerificationContext context) {
        List<Path> files = new ArrayList<>();
        for (Path root : context.projectInfo().testSourceDirs()) {
            for (Path file : TypeScriptProjectDetector.testFiles(root)) {
                if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".tsx")) {
                    files.add(file);
                }
            }
        }
        return List.copyOf(files);
    }

    /** Exposed for the generated-test runner, which uses the same execution rules. */
    public static Path prepareReport(VerificationContext context, String relative) {
        Path report = context.artifacts().artifactsDirectory().resolve(relative);
        try {
            Files.createDirectories(report.getParent());
            Files.deleteIfExists(report);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot prepare " + report, e);
        }
        return report;
    }
}
