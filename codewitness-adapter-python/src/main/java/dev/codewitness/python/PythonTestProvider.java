package dev.codewitness.python;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
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
import java.util.Map;
import dev.codewitness.support.Text;

/**
 * Runs a Python project's existing tests.
 *
 * <p>pytest is used when the interpreter can import it, because it is what most Python projects use
 * and it writes JUnit XML without any plugin. When it is not installed, the standard library
 * {@code unittest} runner is used instead, through a small script that writes the same XML. That
 * matters: verification must not depend on a package the project may not have, and "pytest is
 * missing" must not become "the tests could not be run".
 *
 * <p>The result is always parsed from the raw report. A run that produced no report is reported as
 * "tests did not execute" with the interpreter's own output, never as a pass.
 */
public final class PythonTestProvider implements VerificationTools.TestRunner {

    /** Attribute recorded with the framework actually used in the last run. */
    public static final String ATTR_USED_FRAMEWORK = "usedTestFramework";

    @Override
    public String id() {
        return "python-tests";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(dev.codewitness.core.model.LanguageId.PYTHON);
    }

    @Override
    public TestResult runTests(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        List<Path> testDirs = project.testSourceDirs();
        if (testDirs.isEmpty()) {
            return TestResult.notExecuted("no Python test directory was detected");
        }
        PythonScripts.write(context); // materialise the helper script the command below refers to
        boolean pytest = usePytest(project);
        Path report = context.artifacts().artifactsDirectory().resolve("python/tests/junit.xml");
        PythonTestReportParser.deleteIfExists(report);
        try {
            Files.createDirectories(report.getParent());
        } catch (IOException e) {
            return TestResult.notExecuted("cannot prepare the JUnit report directory: " + e);
        }
        List<String> command = testCommand(context);
        context.emit("tests", "running " + (pytest ? "pytest" : "unittest") + " for " + testDirs.size() + " directory(ies)");

        ProcessSpec spec = ProcessSpec.builder(command, context.projectRoot())
                .environment(PythonInterpreter.environment(project, context.projectRoot(), List.of()))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(1_000_000)
                .build();
        ProcessResult result = context.runner().run(spec);
        if (result.timedOut()) {
            // The killed runner may still have written a report for the tests that finished before the
            // kill, so the timeout is checked before that report is looked for or parsed.
            return TestResult.notExecuted((pytest ? "pytest" : "unittest") + " was killed after the "
                    + context.config().limits().timeout().toSeconds() + "s timeout; the partial output "
                    + "is not a complete test run: " + lastLines(result.combinedOutput(), 6));
        }

        if (!Files.isRegularFile(report)) {
            return TestResult.notExecuted("the Python test runner produced no JUnit report ("
                    + result.describeOutcome() + "); last output: " + lastLines(result.combinedOutput(), 6));
        }
        return PythonTestReportParser.parse(List.of(report), context.projectRoot(),
                "parsed from " + (pytest ? "pytest" : "unittest") + " JUnit XML; " + result.describeOutcome());
    }

    @Override
    public List<String> testCommand(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        List<Path> testDirs = project.testSourceDirs();
        if (testDirs.isEmpty()) {
            return List.of();
        }
        // The unittest runner's path is derived, never written here, so asking for the command has no
        // side effect on the run directory.
        Path report = context.artifacts().artifactsDirectory().resolve("python/tests/junit.xml");
        List<String> command = command(interpreter(project, context), PythonScripts.unittestRunnerPath(context),
                project, context.projectRoot(), testDirs, usePytest(project), report);
        return List.copyOf(command);
    }

    @Override
    public List<String> replayCommand(VerificationContext context, TestCaseResult testCase) {
        ProjectInfo project = context.projectInfo();
        String interpreter = interpreter(project, context);
        Path testFile = context.projectRoot().resolve(testCase.suite().replace('.', '/') + ".py");
        List<String> command = new ArrayList<>();
        Map<String, String> environment = PythonInterpreter.environment(project, context.projectRoot(), List.of());
        String pythonPath = environment.get("PYTHONPATH");
        if (pythonPath != null && !pythonPath.isBlank()) {
            // The replayed command runs outside CodeWitness's process environment, so the module path
            // the run used has to be part of the command or the project's package will not import.
            command.add("env");
            command.add("PYTHONPATH=" + pythonPath);
        }
        if (usePytest(project)) {
            String selection = testFile + "::" + String.join("::", testCase.name().split("\\."));
            command.add(interpreter);
            command.add("-m");
            command.add("pytest");
            command.add("-p");
            command.add("no:cacheprovider");
            command.add(selection);
        } else {
            command.add(interpreter);
            command.add("-m");
            command.add("unittest");
            command.add(testCase.suite() + "." + testCase.name());
        }
        return List.copyOf(command);
    }

    private static List<String> command(
            String interpreter,
            Path unittestRunner,
            ProjectInfo project,
            Path projectRoot,
            List<Path> testDirs,
            boolean pytest,
            Path report) {
        List<String> command = new ArrayList<>();
        command.add(interpreter);
        if (pytest) {
            command.add("-m");
            command.add("pytest");
            command.add("--rootdir");
            command.add(projectRoot.toString());
            command.add("-p");
            command.add("no:cacheprovider");
            command.add("-q");
            command.add("--junitxml=" + report);
            testDirs.forEach(directory -> command.add(directory.toString()));
            return command;
        }
        command.add(unittestRunner.toString());
        command.add("--xml");
        command.add(report.toString());
        command.add("--root");
        command.add(projectRoot.toString());
        command.add("--pattern");
        command.add("test*.py");
        for (Path directory : testDirs) {
            command.add("--start");
            command.add(directory.toString());
        }
        return command;
    }

    public static boolean usePytest(ProjectInfo project) {
        return Boolean.parseBoolean(project.attributes().getOrDefault(PythonInterpreter.ATTR_HAS_PYTEST, "false"));
    }

    public static String interpreter(ProjectInfo project, VerificationContext context) {
        String interpreter = project.attributes().get(PythonInterpreter.ATTR_INTERPRETER);
        if (interpreter != null && !interpreter.isBlank()) {
            return interpreter;
        }
        return PythonInterpreter.resolve(context.projectRoot(), System.getenv());
    }

    public static String lastLines(String text, int count) {
        return Text.lastLines(text, count);
    }
}
