package dev.codewitness.pascal.generate;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.GeneratedTestExecution;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.pascal.PascalBuildProvider;
import dev.codewitness.pascal.PascalTestCommand;
import dev.codewitness.pascal.PascalTestReportParser;
import dev.codewitness.pascal.PascalTool;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Compiles and runs the Pascal tests a scenario generated.
 *
 * <p>A generated test is a program, so executing it is the project's own toolchain doing what it always
 * does: Free Pascal links the program against the project's units, and running it exercises them. The
 * program reports a violation in the counterexample protocol and exits non-zero, which this executor
 * reads back into a case the engine can turn into a reproducible counterexample.
 *
 * <p>The generated sources are written into the project tree so the compiler resolves the project's units
 * beside them, and are removed afterwards unless the configuration asks for them to be kept. The restore
 * happens in a {@code finally}, so a failed run leaves no generated source behind either.
 */
public final class PascalGeneratedTestRunner implements VerificationTools.GeneratedTestExecutor {

    static final String PROJECT_DIRECTORY = "generated-tests";

    @Override
    public String id() {
        return "pascal-generated-tests";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return LanguageId.PASCAL.equals(project.language());
    }

    @Override
    public GeneratedTestExecution execute(VerificationContext context, List<GeneratedTest> tests) {
        if (tests.isEmpty()) {
            return GeneratedTestExecution.notExecuted(tests, "no generated test was produced");
        }
        Path root = context.projectRoot();
        String fpc = PascalTool.fpc(context.projectInfo(), context);
        List<Path> written = new ArrayList<>();
        List<Path> artifacts = new ArrayList<>();
        List<TestCaseResult> cases = new ArrayList<>();
        Duration duration = Duration.ZERO;

        try {
            for (GeneratedTest test : tests) {
                Path file = PascalScenarioTestGenerator.generatedFile(root, test.className());
                write(file, test.sourceCode());
                written.add(file);
                artifacts.add(context.artifacts().copyIn(file, PROJECT_DIRECTORY + "/" + test.className() + ".pas"));
            }

            for (GeneratedTest test : tests) {
                String className = test.className();
                Path source = PascalScenarioTestGenerator.generatedFile(root, className);
                Path executable = executable(context, className);
                List<String> compile = PascalBuildProvider.compileCommand(fpc, root, context, source, executable, true);
                context.emit("generated-tests", "compiling generated test " + className);
                ProcessResult compiled = context.runner().run(ProcessSpec.builder(compile, root)
                        .environment(PascalTool.environment(context))
                        .timeout(context.config().limits().timeout())
                        .maxOutputBytes(1_000_000)
                        .build());
                duration = duration.plus(compiled.duration());
                if (!compiled.started()) {
                    cases.add(error(className, "fpc could not be started: " + compiled.failureReason()));
                    continue;
                }
                if (!compiled.success()) {
                    cases.add(PascalTestReportParser.compileFailure(className, className, compiled));
                    artifacts.add(context.artifacts().write(
                            "pascal/generated/" + className + "-compile.log", compiled.combinedOutput()));
                    continue;
                }
                ProcessResult run = context.runner().run(ProcessSpec.builder(List.of(executable.toString()), root)
                        .environment(PascalTool.environment(context))
                        .timeout(context.config().limits().timeout())
                        .maxOutputBytes(2_000_000)
                        .build());
                duration = duration.plus(run.duration());
                artifacts.add(context.artifacts().write(
                        "pascal/generated/" + className + "-run.log", run.combinedOutput()));
                cases.add(PascalTestReportParser.fromExecution(className, className, run));
            }
        } finally {
            if (!context.config().report().keepGeneratedTests()) {
                for (Path file : written) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException _) {
                        // A leftover generated file is untidy but does not change the verdict.
                    }
                }
                deleteIfEmpty(root.resolve(PROJECT_DIRECTORY));
            }
        }

        if (cases.isEmpty()) {
            return new GeneratedTestExecution(tests,
                    TestResult.notExecuted("no generated test could be compiled or run"), artifacts,
                    "no generated test could be compiled or run");
        }
        TestResult result = TestResult.from(cases, duration, List.of(),
                "each generated test is a program compiled against the project's own units and run once per case");
        return new GeneratedTestExecution(tests, result, artifacts, result.describeWithNote());
    }

    @Override
    public List<String> reproductionCommand(
            VerificationContext context, List<GeneratedTest> tests, Long seed, Integer caseIndex) {
        if (tests == null || tests.isEmpty()) {
            return List.of();
        }
        String className = tests.getFirst().className();
        Path source = context.artifacts().artifactsDirectory().resolve(PROJECT_DIRECTORY).resolve(className + ".pas");
        Path executable = executable(context, className);
        String fpc = PascalTool.fpc(context.projectInfo(), context);
        List<String> compile = PascalBuildProvider.compileCommand(fpc, context.projectRoot(), context, source, executable, true);
        StringBuilder script = new StringBuilder("set -e\n");
        for (String part : compile) {
            script.append(PascalTestCommand.quote(part)).append(' ');
        }
        script.append('\n').append(PascalTestCommand.quote(executable.toString())).append('\n');
        // The seed and case index are baked into the generated source, so the reproduction is the program itself.
        return PascalTestCommand.shell(script.toString());
    }

    private static Path executable(VerificationContext context, String name) {
        Path directory = context.artifacts().artifactsDirectory().resolve("pascal").resolve("generated");
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create " + directory, e);
        }
        return directory.resolve(PascalBuildProvider.safeName(name) + PascalBuildProvider.executableSuffix());
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the generated test " + file, e);
        }
    }

    private static TestCaseResult error(String suite, String message) {
        return new TestCaseResult(suite, suite, TestOutcome.ERROR, Duration.ZERO, "GeneratedTestError", message, null);
    }

    private static void deleteIfEmpty(Path directory) {
        try {
            if (Files.isDirectory(directory)) {
                try (var entries = Files.list(directory)) {
                    if (entries.findAny().isEmpty()) {
                        Files.deleteIfExists(directory);
                    }
                }
            }
        } catch (IOException _) {
            // Leaving an empty directory behind is harmless.
        }
    }
}
