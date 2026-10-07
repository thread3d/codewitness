package dev.codewitness.cpp;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a C or C++ project's existing tests with CTest.
 *
 * <p>CTest is the test runner every CMake project already has: {@code add_test} registers a test and
 * {@code enable_testing()} makes it runnable, so the adapter runs exactly the suite the project declares
 * rather than inventing one. The report is read rather than the console, so every test carries the name it
 * was registered under, its outcome and its duration.
 *
 * <p>The build is driven first when the build directory holds no CTest description. That happens when the
 * test phase runs against a tree whose build earlier failed or was skipped, and running CTest over a
 * directory that was never configured would report "no tests" for a project that has plenty. A build that
 * fails here is a fact about the build, and it is carried in the note rather than turned into a test
 * failure.
 */
public final class CppTestProvider implements VerificationTools.TestRunner {

    /** Name of the JUnit report CTest writes inside the run's artefact store. */
    public static final String REPORT_NAME = "ctest-junit.xml";

    @Override
    public String id() {
        return "ctest";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.CPP);
    }

    /** @return the path the JUnit report is written to inside the run's artefact store. */
    public static Path reportFile(VerificationContext context) {
        return context.artifacts().artifactsDirectory().resolve("cpp/tests/" + REPORT_NAME);
    }

    @Override
    public TestResult runTests(VerificationContext context) {
        Path sourceDir = CppProjectDetector.buildRoot(context.projectRoot());
        if (sourceDir == null) {
            return TestResult.notExecuted(
                    "no CMakeLists.txt declaring a project was found, so there is no CTest suite to run");
        }
        if (CppProjectDetector.ctestTests(context.projectRoot()).isEmpty()) {
            // A CMake project with no add_test has no suite. Reporting that is different from reporting an
            // empty one: the report says the project declares no test rather than that none ran. The check
            // comes first because a project with no test has nothing for a build to make runnable.
            return TestResult.notExecuted("the project registers no test with add_test, so there is no CTest "
                    + "suite to run");
        }
        CppBuildTools tools = new CppBuildTools(context.runner());
        Path buildDir = tools.buildDirectory(context);
        if (!Files.isRegularFile(buildDir.resolve("CTestTestfile.cmake"))) {
            BuildResult build = new CppBuildProvider().build(context);
            if (!build.success()) {
                return TestResult.notExecuted("the project could not be built, so its tests could not run: "
                        + build.describe());
            }
        }

        Path report = reportFile(context);
        try {
            Files.createDirectories(report.getParent());
            // A report left by an earlier attempt would be indistinguishable from a fresh one.
            Files.deleteIfExists(report);
        } catch (IOException e) {
            return TestResult.notExecuted("cannot prepare the report directory " + report.getParent() + ": " + e);
        }

        List<String> command = CppBuildTools.testCommand(buildDir, report);
        context.emit("tests", "running ctest over " + buildDir.getFileName());
        ProcessResult result = context.runner().run(ProcessSpec.builder(command, context.projectRoot())
                .environment(CppBuildTools.environment(context))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(4_000_000)
                .build());
        if (!result.started()) {
            return TestResult.notExecuted("ctest could not be started: " + result.failureReason());
        }
        if (!Files.isRegularFile(report)) {
            return TestResult.notExecuted("ctest produced no JUnit report (" + result.describeOutcome()
                    + "); last output: " + CppBuildTools.lastLines(result.combinedOutput(), 4));
        }
        return CppTestReportParser.parse(report, "parsed from the CTest JUnit report; ctest "
                + result.describeOutcome());
    }

    @Override
    public List<String> testCommand(VerificationContext context) {
        if (CppProjectDetector.buildRoot(context.projectRoot()) == null) {
            return List.of();
        }
        return CppBuildTools.testCommand(new CppBuildTools(context.runner()).buildDirectory(context),
                reportFile(context));
    }

    @Override
    public List<String> replayCommand(VerificationContext context, TestCaseResult testCase) {
        Path sourceDir = CppProjectDetector.buildRoot(context.projectRoot());
        if (sourceDir == null || testCase == null) {
            return List.of();
        }
        Path buildDir = new CppBuildTools(context.runner()).buildDirectory(context);
        List<String> command = new ArrayList<>();
        command.add(CppBuildTools.ctest(context.projectInfo()));
        command.add("--test-dir");
        command.add(buildDir.toString());
        // CTest selects by regular expression over the test's registered name, which is the suite this
        // parser records; anchoring it replays that test and not every test whose name contains it. The name
        // is not escaped: CMake requires a test name to be a CMake identifier - letters, digits, underscores,
        // dots and dashes - so it holds nothing a CMake regular expression reads as a metacharacter. Java's
        // \Q...\E would be the wrong thing to reach for here: CMake does not implement it, and the command
        // would select nothing at all.
        command.add("-R");
        command.add("^" + testCase.suite() + "$");
        command.add("--output-on-failure");
        return List.copyOf(command);
    }
}
