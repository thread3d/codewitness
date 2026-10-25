package dev.codewitness.python.generate;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.GeneratedTestExecution;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.python.PythonInterpreter;
import dev.codewitness.python.PythonScripts;
import dev.codewitness.python.PythonTestProvider;
import dev.codewitness.python.PythonTestReportParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Executes generated scenario tests and reads the structured counterexamples out of the report.
 *
 * <p>The generated modules are written into the run's artefact store rather than into the project.
 * That is a deliberate difference from the Java adapter, which must compile test sources inside the
 * project: a Python test is just a module, so it can live wherever it is importable. Keeping it out
 * of the project means a verification run never leaves generated code behind, and the saved copy is
 * the file the reproduction command actually runs.
 *
 * <p>The project's own source roots are put on {@code PYTHONPATH}, so a project that is not
 * installed is still importable from a generated test.
 */
public final class PythonGeneratedTestRunner implements VerificationTools.GeneratedTestExecutor {

    /** Subdirectory of the artefact store the generated modules live in. */
    static final String GENERATED_DIRECTORY = "generated-tests";

    @Override
    public String id() {
        return "python-generated-tests";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(dev.codewitness.core.model.LanguageId.PYTHON);
    }

    @Override
    public GeneratedTestExecution execute(VerificationContext context, List<GeneratedTest> tests) {
        if (tests.isEmpty()) {
            return GeneratedTestExecution.notExecuted(tests, "no tests to run");
        }
        ProjectInfo project = context.projectInfo();
        String interpreter = PythonTestProvider.interpreter(project, context);
        boolean pytest = PythonTestProvider.usePytest(project);
        PythonScripts.Scripts scripts = PythonScripts.write(context);

        Path generatedDirectory = context.artifacts().artifactsDirectory().resolve(GENERATED_DIRECTORY);
        List<Path> artifacts = new ArrayList<>();
        List<Path> generatedFiles = new ArrayList<>();
        for (GeneratedTest test : tests) {
            Path file = context.artifacts().write(GENERATED_DIRECTORY + "/" + test.className() + ".py",
                    test.sourceCode());
            artifacts.add(file);
            generatedFiles.add(file);
        }
        Path report = generatedDirectory.resolve("junit.xml");
        PythonTestReportParser.deleteIfExists(report);

        List<String> command = new ArrayList<>();
        command.add(interpreter);
        if (pytest) {
            command.add("-m");
            command.add("pytest");
            command.add("--rootdir");
            command.add(generatedDirectory.toString());
            command.add("-p");
            command.add("no:cacheprovider");
            command.add("-q");
            command.add("--junitxml=" + report);
            generatedFiles.forEach(file -> command.add(file.toString()));
        } else {
            command.add(scripts.unittestRunner().toString());
            command.add("--xml");
            command.add(report.toString());
            command.add("--root");
            command.add(generatedDirectory.toString());
            for (Path file : generatedFiles) {
                command.add("--file");
                command.add(file.toString());
            }
        }

        Map<String, String> environment =
                PythonInterpreter.environment(project, context.projectRoot(), List.of(generatedDirectory));
        if (pytest) {
            // The generated tests are plain unittest cases; nothing about them needs a pytest plugin. But
            // pytest auto-loads every plugin installed in the interpreter, and a third-party plugin that
            // fails at import time - an incompatible mark on a fixture, for instance - aborts collection
            // before a single generated case runs. That turns a test that would have found a counterexample
            // into "no evidence", so the plugins are switched off for this run: they are code CodeWitness
            // does not control, running inside the measurement.
            environment.put("PYTEST_DISABLE_PLUGIN_AUTOLOAD", "1");
        }
        ProcessSpec spec = ProcessSpec.builder(command, context.projectRoot())
                .environment(environment)
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(1_000_000)
                .build();
        ProcessResult result = context.runner().run(spec);

        if (pytest && !Files.isRegularFile(report)) {
            // pytest still could not produce a report - a broken project conftest, a pytest version that
            // rejects the file, an interpreter whose site-packages is unusable. The generated test needs
            // nothing but the standard library, so the stdlib runner is tried before the evidence is
            // written off: the difference between "no counterexample" and "the runner would not start" is
            // the difference between UNVERIFIED and a finding.
            PythonTestReportParser.deleteIfExists(report);
            List<String> fallback = new ArrayList<>();
            fallback.add(interpreter);
            fallback.add(scripts.unittestRunner().toString());
            fallback.add("--xml");
            fallback.add(report.toString());
            fallback.add("--root");
            fallback.add(generatedDirectory.toString());
            for (Path file : generatedFiles) {
                fallback.add("--file");
                fallback.add(file.toString());
            }
            ProcessResult fallbackResult = context.runner().run(ProcessSpec.builder(fallback, context.projectRoot())
                    .environment(environment)
                    .timeout(context.config().limits().timeout())
                    .maxOutputBytes(1_000_000)
                    .build());
            if (Files.isRegularFile(report)) {
                TestResult parsed = PythonTestReportParser.parse(List.of(report), generatedDirectory,
                        "generated test run through the stdlib runner after pytest produced no report ("
                                + result.describeOutcome() + "; fallback " + fallbackResult.describeOutcome()
                                + ")");
                if (!parsed.cases().isEmpty()) {
                    return new GeneratedTestExecution(tests, parsed, artifacts, parsed.describe());
                }
            }
            result = fallbackResult;
        }

        if (!Files.isRegularFile(report)) {
            String note = "the Python runner produced no report for the generated tests ("
                    + result.describeOutcome() + "); last output: "
                    + PythonTestProvider.lastLines(result.combinedOutput(), 6);
            return new GeneratedTestExecution(tests, TestResult.notExecuted(note), artifacts, note);
        }
        TestResult parsed = PythonTestReportParser.parse(List.of(report), generatedDirectory,
                "generated test run (" + result.describeOutcome() + ")");
        if (parsed.cases().isEmpty()) {
            // A report that carries no case for the generated modules is not a pass: the modules either
            // never ran or were not collected. Report the batch as not executed so that silence is never
            // mistaken for a successful run.
            String note = "the Python runner reported no test case for the generated tests ("
                    + result.describeOutcome() + "); last output: "
                    + PythonTestProvider.lastLines(result.combinedOutput(), 6);
            return new GeneratedTestExecution(tests, TestResult.notExecuted(note), artifacts, note);
        }
        return new GeneratedTestExecution(tests, parsed, artifacts, parsed.describe());
    }

    @Override
    public List<String> reproductionCommand(
            VerificationContext context, List<GeneratedTest> tests, Long seed, Integer caseIndex) {
        if (tests.isEmpty()) {
            return List.of();
        }
        ProjectInfo project = context.projectInfo();
        String interpreter = PythonTestProvider.interpreter(project, context);
        boolean pytest = PythonTestProvider.usePytest(project);
        PythonScripts.Scripts scripts = PythonScripts.write(context);
        Path generatedDirectory = context.artifacts().artifactsDirectory().resolve(GENERATED_DIRECTORY);
        Map<String, String> environment =
                PythonInterpreter.environment(project, context.projectRoot(), List.of(generatedDirectory));

        List<String> command = new ArrayList<>();
        String pythonPath = environment.get("PYTHONPATH");
        if (pythonPath != null && !pythonPath.isBlank()) {
            command.add("env");
            command.add("PYTHONPATH=" + pythonPath);
        } else {
            command.add("env");
        }
        if (seed != null) {
            command.add("CODEWITNESS_SEED=" + seed);
        }
        if (caseIndex != null) {
            command.add("CODEWITNESS_CASE=" + caseIndex);
        }
        // The run disables pytest's plugin auto-loading for generated tests, so the replay has to as well: a
        // command that differs from what produced the counterexample is not a reproduction. The project's own
        // suite is left alone, because a project may legitimately depend on its plugins.
        if (pytest) {
            command.add("PYTEST_DISABLE_PLUGIN_AUTOLOAD=1");
        }
        command.add(interpreter);
        if (pytest) {
            command.add("-m");
            command.add("pytest");
            command.add("-p");
            command.add("no:cacheprovider");
            for (GeneratedTest test : tests) {
                command.add(generatedDirectory.resolve(test.className() + ".py").toString());
            }
            return List.copyOf(command);
        }
        command.add(scripts.unittestRunner().toString());
        command.add("--xml");
        command.add(generatedDirectory.resolve("replay.xml").toString());
        command.add("--root");
        command.add(generatedDirectory.toString());
        for (GeneratedTest test : tests) {
            command.add("--file");
            command.add(generatedDirectory.resolve(test.className() + ".py").toString());
        }
        return List.copyOf(command);
    }
}
