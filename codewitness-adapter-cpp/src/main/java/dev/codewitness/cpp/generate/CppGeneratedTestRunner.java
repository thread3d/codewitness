package dev.codewitness.cpp.generate;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.GeneratedTestExecution;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.cpp.CppBuildProvider;
import dev.codewitness.cpp.CppBuildTools;
import dev.codewitness.cpp.CppDiagnostics;
import dev.codewitness.cpp.CppProjectDetector;
import dev.codewitness.support.Platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Compiles and runs generated scenario tests with the project's own compiler and flags.
 *
 * <p>A C or C++ generated test is not added to the project's build. It is compiled by the compiler the
 * project's own build used, with the exact arguments that build recorded for its own sources in
 * {@code compile_commands.json}, and linked against the archives that build produced. That is the same
 * guarantee the Java and C# adapters get by writing into the project's test sources - the test is compiled
 * the way the project compiles itself - without either editing a {@code CMakeLists.txt} or requiring a test
 * framework the project may not have.
 *
 * <p>Everything lives in the run's artefact store: the generated source, the object file, the linked
 * executable and the transcript of each command. That is what makes the replay command in a counterexample
 * work after the run, and it is why a verification run never leaves anything behind in the tree it verified.
 *
 * <p>A batch that does not compile is reported as not executed, with the compiler's own first errors. The
 * requirement then stays {@code UNVERIFIED} rather than becoming {@code BLOCKED}: the generated test is
 * CodeWitness's own artefact, and a mistake in it says nothing about the project's build.
 *
 * <p>One test is one program: it runs every case the scenario declares and exits non-zero at the first
 * violation, after printing the {@code CODEWITNESS_COUNTEREXAMPLE|...} line the engine decodes. The engine
 * pairs the result back to the obligation through the suite name, which is the generated test's own name.
 */
public final class CppGeneratedTestRunner implements VerificationTools.GeneratedTestExecutor {

    @Override
    public String id() {
        return "cpp-generated-tests";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.CPP);
    }

    @Override
    public GeneratedTestExecution execute(VerificationContext context, List<GeneratedTest> tests) {
        if (tests.isEmpty()) {
            return GeneratedTestExecution.notExecuted(tests, "no tests to run");
        }
        CppBuildTools tools = new CppBuildTools(context.runner());
        Path buildDir = tools.buildDirectory(context);
        if (!Files.isRegularFile(CppBuildTools.compileDatabase(buildDir))) {
            // The generated test is compiled with the flags the project's build recorded, so the build has
            // to have happened; running it here is what makes the phase self-sufficient.
            var build = new CppBuildProvider().build(context);
            if (!build.success()) {
                return GeneratedTestExecution.notExecuted(tests,
                        "the project could not be built, so its compile flags are not available: "
                                + build.describe());
            }
        }

        Path reference = referenceSource(context);
        Path compiler = compiler(context, buildDir, reference);
        List<String> flags = flags(context, buildDir, reference);
        List<Path> archives = CppBuildTools.builtArchives(buildDir);
        Path directory = tools.generatedDirectory(context);
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            return GeneratedTestExecution.notExecuted(tests,
                    "cannot create the generated-test directory " + directory + ": " + e);
        }
        Path workingDirectory = reference == null
                ? context.projectRoot() : CppBuildTools.compileDirectory(buildDir, reference);

        List<Path> artifacts = new ArrayList<>();
        List<TestCaseResult> cases = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (GeneratedTest test : tests) {
            String name = identifier(test.className());
            Path source = directory.resolve(name + ".cpp");
            Path object = directory.resolve(name + ".o");
            Path executable = directory.resolve(name + (Platform.isWindows() ? ".exe" : ""));
            try {
                Files.writeString(source, test.sourceCode(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                notes.add("cannot write " + source + ": " + e);
                continue;
            }
            artifacts.add(source);

            List<String> compile = compileCommand(compiler, flags, source, object);
            ProcessResult compiled = context.runner().run(ProcessSpec.builder(compile, workingDirectory)
                    .environment(CppBuildTools.environment(context))
                    .timeout(context.config().limits().timeout())
                    .maxOutputBytes(2_000_000)
                    .build());
            if (!compiled.started() || !compiled.success()) {
                String detail = compilerErrors(context, compiled);
                notes.add("the generated test " + test.className() + " did not compile ("
                        + compiled.describeOutcome() + "): " + detail);
                return new GeneratedTestExecution(tests, TestResult.notExecuted(String.join("; ", notes)),
                        artifacts, String.join("; ", notes));
            }
            List<String> link = linkCommand(compiler, object, archives, executable);
            ProcessResult linked = context.runner().run(ProcessSpec.builder(link, workingDirectory)
                    .environment(CppBuildTools.environment(context))
                    .timeout(context.config().limits().timeout())
                    .maxOutputBytes(2_000_000)
                    .build());
            if (!linked.started() || !linked.success()) {
                notes.add("the generated test " + test.className() + " could not be linked ("
                        + linked.describeOutcome() + "); the project's built libraries are "
                        + archives.size() + " archive(s) and the linker said: "
                        + CppBuildTools.lastLines(linked.combinedOutput(), 6));
                return new GeneratedTestExecution(tests, TestResult.notExecuted(String.join("; ", notes)),
                        artifacts, String.join("; ", notes));
            }
            artifacts.add(executable);

            ProcessSpec.Builder run = ProcessSpec.builder(List.of(executable.toString()), context.projectRoot())
                    .environment(CppBuildTools.environment(context))
                    .timeout(context.config().limits().timeout())
                    .maxOutputBytes(1_000_000);
            if (test.seed() != null) {
                run.env("CODEWITNESS_SEED", String.valueOf(test.seed()));
            }
            ProcessResult result = context.runner().run(run.build());
            cases.add(caseOf(test, result));
        }

        if (cases.isEmpty()) {
            return GeneratedTestExecution.notExecuted(tests,
                    "no generated test produced a result: " + String.join("; ", notes));
        }
        Duration duration = cases.stream().map(TestCaseResult::duration).reduce(Duration.ZERO, Duration::plus);
        String note = "generated test run (" + cases.size() + " program(s))"
                + (notes.isEmpty() ? "" : "; " + String.join("; ", notes));
        TestResult result = TestResult.from(cases, duration, artifacts, note);
        return new GeneratedTestExecution(tests, result, artifacts, note);
    }

    /**
     * @return the result of running one generated program: the counterexample line it printed when it
     *     found a violation, or the reason it could not be read when it failed without one
     */
    private TestCaseResult caseOf(GeneratedTest test, ProcessResult result) {
        Duration duration = result.duration();
        if (result.started() && result.success()) {
            return new TestCaseResult(test.className(), test.className(), TestOutcome.PASSED, duration,
                    null, null, null);
        }
        String counterexample = counterexampleLine(result.combinedOutput());
        if (counterexample != null) {
            return new TestCaseResult(test.className(), test.className(), TestOutcome.FAILED, duration,
                    "assertion failure", counterexample, null);
        }
        String message = "the generated test exited with " + result.describeOutcome()
                + " without reporting a counterexample; its output was: "
                + CppBuildTools.lastLines(result.combinedOutput(), 6);
        return new TestCaseResult(test.className(), test.className(), TestOutcome.ERROR, duration,
                "test error", message, null);
    }

    /** @return the structured counterexample line an output carries, or {@code null} when it carries none. */
    static String counterexampleLine(String output) {
        if (output == null) {
            return null;
        }
        String marker = dev.codewitness.core.engine.CounterexampleProtocol.MARKER;
        for (String line : output.split("\\R")) {
            if (line.contains(marker)) {
                return line.strip();
            }
        }
        return null;
    }

    @Override
    public List<String> reproductionCommand(
            VerificationContext context, List<GeneratedTest> tests, Long seed, Integer caseIndex) {
        if (tests.isEmpty()) {
            return List.of();
        }
        CppBuildTools tools = new CppBuildTools(context.runner());
        Path executable = tools.generatedDirectory(context)
                .resolve(identifier(tests.getFirst().className()) + (Platform.isWindows() ? ".exe" : ""));
        List<String> command = new ArrayList<>();
        if (Platform.isWindows()) {
            // cmd.exe has no env(1); the variables are set for the one command that follows.
            StringBuilder line = new StringBuilder();
            if (seed != null) {
                line.append("set CODEWITNESS_SEED=").append(seed).append("&& ");
            }
            if (caseIndex != null) {
                line.append("set CODEWITNESS_CASE=").append(caseIndex).append("&& ");
            }
            line.append(executable);
            command.add("cmd.exe");
            command.add("/c");
            command.add(line.toString());
            return List.copyOf(command);
        }
        command.add("env");
        if (seed != null) {
            command.add("CODEWITNESS_SEED=" + seed);
        }
        if (caseIndex != null) {
            command.add("CODEWITNESS_CASE=" + caseIndex);
        }
        command.add(executable.toString());
        return List.copyOf(command);
    }

    /**
     * @return the argv that compiles one generated source with the project's own recorded arguments
     *
     * @param compiler the compiler the project's own build used
     * @param flags    the arguments that build recorded, without its own output file
     * @param source   the generated source
     * @param object   the object file to write
     */
    public static List<String> compileCommand(Path compiler, List<String> flags, Path source, Path object) {
        List<String> command = new ArrayList<>();
        command.add(compiler.toString());
        command.addAll(flags);
        command.add(source.toString());
        command.add("-o");
        command.add(object.toString());
        command.add("-c");
        return List.copyOf(command);
    }

    /**
     * @return the argv that links one generated object and the project's built archives into a program
     *
     * <p>{@code -pthread} is passed on POSIX because a CONCURRENT scenario generates {@code std::thread}
     *     code and a C++ runtime on Linux needs the flag to link it; on macOS it is accepted and ignored.
     */
    public static List<String> linkCommand(Path compiler, Path object, List<Path> archives, Path executable) {
        List<String> command = new ArrayList<>();
        command.add(compiler.toString());
        if (!Platform.isWindows()) {
            command.add("-pthread");
        }
        command.add(object.toString());
        for (Path archive : archives) {
            command.add(archive.toString());
        }
        command.add("-o");
        command.add(executable.toString());
        return List.copyOf(command);
    }

    /** @return the flags and include paths one generated source is compiled with. */
    static List<String> flags(VerificationContext context, Path buildDir, Path reference) {
        Set<String> flags = new LinkedHashSet<>();
        if (reference != null) {
            flags.addAll(CppBuildTools.compileFlags(buildDir, reference));
        }
        // The generated file is not in the project tree, so the path it includes its headers by - relative to
        // the project root - has to be reachable from the compiler's search path.
        flags.add("-I" + context.projectRoot());
        return List.copyOf(flags);
    }

    /** @return the compiler the project's own build used, or a PATH-resolved {@code c++}. */
    static Path compiler(VerificationContext context, Path buildDir, Path reference) {
        if (reference != null) {
            String recorded = CppBuildTools.compiler(buildDir, reference);
            if (recorded != null && !recorded.isBlank()) {
                return Path.of(recorded);
            }
        }
        String resolved = CppBuildTools.resolveExecutable("c++");
        return Path.of(resolved == null ? "c++" : resolved);
    }

    /**
     * @return a project source file whose recorded flags stand for the whole project: the first source of
     *     the first main source root, or any source at all when the project declares none
     */
    static Path referenceSource(VerificationContext context) {
        for (Path root : context.projectInfo().sourceDirs()) {
            List<Path> files = CppProjectDetector.sourceFiles(root);
            if (!files.isEmpty()) {
                return files.getFirst();
            }
        }
        List<Path> files = CppProjectDetector.sourceFiles(context.projectRoot());
        return files.isEmpty() ? null : files.getFirst();
    }

    /** @return the compiler's own first errors, for a note that says why a generated test did not build. */
    static String compilerErrors(VerificationContext context, ProcessResult result) {
        List<CompilerDiagnostic> diagnostics =
                CppDiagnostics.parse(result.combinedOutput(), context.projectRoot());
        if (!diagnostics.isEmpty()) {
            return diagnostics.stream()
                    .limit(3)
                    .map(diagnostic -> diagnostic.location() + ": " + diagnostic.message())
                    .reduce((left, right) -> left + "; " + right)
                    .orElse("");
        }
        if (!result.started()) {
            return result.failureReason();
        }
        return CppBuildTools.lastLines(result.combinedOutput(), 6);
    }

    /** @return a class-like name turned into a file name and C++ identifier safe to pass to a compiler. */
    public static String identifier(String className) {
        StringBuilder sb = new StringBuilder();
        for (char c : className.toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        return sb.isEmpty() ? "CwGenerated" : sb.toString();
    }
}
