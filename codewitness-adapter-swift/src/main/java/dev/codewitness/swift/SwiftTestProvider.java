package dev.codewitness.swift;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.support.Text;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a Swift package's XCTest suite with {@code swift test}.
 *
 * <p>XCTest ships with the Swift toolchain, so unlike RSpec or PHPUnit there is nothing to resolve at
 * test time: the same {@code swift} the build drove runs the tests. The adapter drives the suite the
 * package declares rather than selecting files, because SwiftPM decides which files belong to a test
 * target from the manifest.
 *
 * <p>The results are read from the runner's own console by {@link SwiftTestReportParser}, which explains
 * why that is the only report XCTest offers a command-line caller. The console is kept as an artefact so
 * the evidence records the output the result was parsed from.
 *
 * <p>A killed runner is not a partial pass: the timeout is checked before the output is parsed, and the
 * phase reports "not executed" rather than the cases that happened to finish before the kill.
 */
public final class SwiftTestProvider implements VerificationTools.TestRunner {

    @Override
    public String id() {
        return "xctest";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.SWIFT);
    }

    @Override
    public TestResult runTests(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        List<String> command = testCommand(context);
        if (command.isEmpty()) {
            return TestResult.notExecuted(unavailableReason(project, context));
        }
        Path log = reportPath(context);
        try {
            Files.createDirectories(log.getParent());
        } catch (IOException e) {
            return TestResult.notExecuted("cannot prepare the swift test log directory: " + e);
        }
        context.emit("tests", "running swift test for " + project.testSourceDirs().size()
                + " test target(s) with XCTest");

        ProcessResult result = context.runner().run(ProcessSpec.builder(command, context.projectRoot())
                .environment(SwiftToolchain.environment(context))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(2_000_000)
                .build());
        writeLog(log, result.combinedOutput());
        if (result.timedOut()) {
            return TestResult.notExecuted("swift test was killed after the "
                    + context.config().limits().timeout().toSeconds() + "s timeout; the partial output is "
                    + "not a complete test run: " + Text.lastLines(result.combinedOutput(), 6));
        }
        TestResult parsed = SwiftTestReportParser.parse(result.combinedOutput(), context.projectRoot(),
                "parsed from the swift test console output; " + result.describeOutcome());
        if (!parsed.executed()) {
            return TestResult.notExecuted(parsed.note());
        }
        return new TestResult(parsed.executed(), parsed.note(), parsed.total(), parsed.passed(),
                parsed.failed(), parsed.errors(), parsed.skipped(), parsed.duration(), parsed.cases(),
                List.of(log));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The command runs the whole suite. No path selection is added, because SwiftPM decides which
     * files belong to a test target from the manifest and a file outside it would not be compiled.
     */
    @Override
    public List<String> testCommand(VerificationContext context) {
        String swift = context.projectInfo().attributes().get(SwiftToolchain.ATTR_SWIFT);
        if (swift == null || swift.isBlank() || context.projectInfo().testSourceDirs().isEmpty()) {
            return List.of();
        }
        SwiftToolchain.prepareDirectories(context);
        return SwiftToolchain.command(swift, context, List.of("test"));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The replay pins one method with SwiftPM's own {@code --filter}, whose argument is
     * {@code Suite/method} where {@code Suite} is the module-qualified class XCTest reports. That is
     * exactly the suite and name a {@link TestCaseResult} carries, so the command is one a human can
     * paste into a shell and have it run that one test.
     */
    @Override
    public List<String> replayCommand(VerificationContext context, TestCaseResult testCase) {
        List<String> base = testCommand(context);
        if (base.isEmpty() || testCase == null) {
            return List.of();
        }
        List<String> command = new ArrayList<>(base);
        command.add("--filter");
        command.add(testCase.suite() + "/" + testCase.name());
        return List.copyOf(command);
    }

    /** @return why no test could be run, in the adapter's own words. */
    private static String unavailableReason(ProjectInfo project, VerificationContext context) {
        String probeFailure = project.attributes().get(SwiftToolchain.ATTR_PROBE_FAILURE);
        if (probeFailure != null) {
            return "the Swift toolchain probe failed: " + probeFailure;
        }
        if (project.attributes().get(SwiftToolchain.ATTR_SWIFT) == null) {
            return "no swift executable was resolved for this run, so XCTest could not be run";
        }
        if (project.testSourceDirs().isEmpty()) {
            return "the package declares no test target, so there is no XCTest suite to run";
        }
        return "no swift test command could be built for this package";
    }

    /** Writes the runner's output where the evidence can carry it; a failure here is not fatal. */
    private static void writeLog(Path log, String output) {
        try {
            Files.writeString(log, output == null ? "" : output, StandardCharsets.UTF_8);
        } catch (IOException _) {
            // the result is still parsed from the captured output; only the artefact copy is lost
        }
    }

    /** @return where this run's swift test console log is written, inside the run's artefact store. */
    static Path reportPath(VerificationContext context) {
        return context.artifacts().artifactsDirectory().resolve("swift").resolve("swift-test.log");
    }
}
