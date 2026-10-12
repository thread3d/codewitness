package dev.codewitness.pascal;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Runs a Pascal project's own test suite.
 *
 * <p>A Pascal test program is a program: the compiler links the test units and the fpcunit framework
 * into one executable, and running it runs the tests. The adapter therefore compiles each runner it can
 * find and executes it. A project that keeps test units but no runner is still testable, because a runner
 * over them is mechanical - the fpcunit units register their suites when they are linked, so a program
 * that uses them runs them - and the adapter synthesizes one rather than reporting a suite it cannot run.
 *
 * <p>A run through the fpcunit console runner is asked for its XML report, which names each test and its
 * failure. A project whose test program is not an fpcunit runner is judged by its exit status.
 */
public final class PascalTestProvider implements VerificationTools.TestRunner {

    @Override
    public String id() {
        return "pascal-test";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return LanguageId.PASCAL.equals(project.language());
    }

    @Override
    public TestResult runTests(VerificationContext context) {
        List<Runner> runners = runners(context);
        if (runners.isEmpty()) {
            return TestResult.notExecuted(
                    "no Pascal test suite was found; the adapter compiles and runs a test program, and this "
                            + "tree declares neither a runner nor a unit that uses fpcunit");
        }
        Path root = context.projectRoot();
        String fpc = PascalTool.fpc(context.projectInfo(), context);
        List<TestCaseResult> cases = new ArrayList<>();
        List<Path> reports = new ArrayList<>();
        Duration duration = Duration.ZERO;

        for (Runner runner : runners) {
            String suite = runner.name();
            Path executable = testExecutable(context, suite);
            List<String> compile = PascalBuildProvider.compileCommand(fpc, root, context, runner.file(), executable, true);
            context.emit("tests", "compiling test program " + root.relativize(runner.file()));
            ProcessResult compiled = context.runner().run(ProcessSpec.builder(compile, root)
                    .environment(PascalTool.environment(context))
                    .timeout(context.config().limits().timeout())
                    .maxOutputBytes(1_000_000)
                    .build());
            duration = duration.plus(compiled.duration());
            if (!compiled.started() || !compiled.success()) {
                cases.add(PascalTestReportParser.compileFailure(suite, suite, compiled));
                reports.add(writeLog(context, "pascal/tests/" + PascalBuildProvider.safeName(suite) + "-compile.log",
                        compiled.combinedOutput()));
                continue;
            }

            Path xml = null;
            List<String> runCommand;
            if (runner.consoleRunner()) {
                xml = context.artifacts().artifactsDirectory()
                        .resolve("pascal").resolve("tests").resolve(PascalBuildProvider.safeName(suite) + ".xml");
                createDirectories(xml.getParent());
                runCommand = List.of(executable.toString(), "--all", "--format=xml", "--file=" + xml, "--no-addresses");
            } else {
                runCommand = List.of(executable.toString());
            }
            context.emit("tests", "running test program " + suite);
            ProcessResult run = context.runner().run(ProcessSpec.builder(runCommand, root)
                    .environment(PascalTool.environment(context))
                    .timeout(context.config().limits().timeout())
                    .maxOutputBytes(2_000_000)
                    .build());
            duration = duration.plus(run.duration());
            reports.add(writeLog(context, "pascal/tests/" + PascalBuildProvider.safeName(suite) + "-run.log",
                    run.combinedOutput()));

            List<TestCaseResult> parsed = xml != null && Files.isRegularFile(xml)
                    ? PascalTestReportParser.parseXml(read(xml), suite)
                    : List.of();
            if (!parsed.isEmpty()) {
                cases.addAll(parsed);
                reports.add(xml);
                continue;
            }
            Optional<TestCaseResult> summary = runner.consoleRunner()
                    ? PascalTestReportParser.parsePlainSummary(run.combinedOutput(), suite)
                    : Optional.empty();
            cases.add(summary.orElseGet(() -> PascalTestReportParser.fromExecution(suite, suite, run)));
        }

        if (cases.isEmpty()) {
            return TestResult.notExecuted("no Pascal test program could be compiled or run");
        }
        String note = "each Pascal test program is compiled and run; a project's fpcunit suites are reported "
                + "per test, and a program that is not an fpcunit runner is reported as one case judged by its exit status";
        return TestResult.from(cases, duration, reports, note);
    }

    @Override
    public List<String> testCommand(VerificationContext context) {
        // Deliberately empty: a Pascal test is a compile followed by a run, and naming either one alone
        // would misattribute the result. The mutation tester uses PascalTestCommand for the pair.
        return List.of();
    }

    @Override
    public List<String> replayCommand(VerificationContext context, TestCaseResult testCase) {
        if (testCase == null) {
            return List.of();
        }
        return PascalTestCommand.forProject(context);
    }

    /**
     * Finds the test programs the project declares, synthesizing a runner when it keeps test units but
     * no program that links them.
     *
     * @param context run context
     * @return the runners, in path order
     */
    public static List<Runner> runners(VerificationContext context) {
        Path root = context.projectRoot();
        List<PascalModule> modules = PascalProjectDetector.modules(root);
        List<String> testUnitNames = modules.stream()
                .filter(module -> module.test() && !module.program())
                .map(PascalModule::name)
                .toList();
        List<Runner> declared = new ArrayList<>();
        for (PascalModule module : modules) {
            if (!module.program()) {
                continue;
            }
            boolean linksTests = module.test() || testUnitNames.stream().anyMatch(module::uses);
            if (linksTests) {
                declared.add(new Runner(module.file(), isConsoleRunner(module.file()), module.name()));
            }
        }
        if (!declared.isEmpty()) {
            return List.copyOf(declared);
        }
        List<PascalModule> testUnits = modules.stream()
                .filter(module -> module.test() && !module.program())
                .toList();
        if (testUnits.isEmpty()) {
            return List.of();
        }
        return List.of(new Runner(synthesizeRunner(context, testUnits), true, synthesizeRunnerName()));
    }

    /** @return the file a synthesized runner is written to, for the mutation tester's manifest check. */
    static String synthesizeRunnerName() {
        return "cw_fpcunit_runner";
    }

    /**
     * Writes a runner that links every test unit, which is all fpcunit needs to run the suites those
     * units registered.
     */
    static Path synthesizeRunner(VerificationContext context, List<PascalModule> testUnits) {
        Path directory = context.artifacts().artifactsDirectory().resolve("pascal").resolve("runners");
        createDirectories(directory);
        Path file = directory.resolve(synthesizeRunnerName() + ".pas");
        StringBuilder uses = new StringBuilder("fpcunit, testregistry, consoletestrunner");
        for (PascalModule unit : testUnits) {
            uses.append(", ").append(unit.name());
        }
        String source = "program " + synthesizeRunnerName() + ";\n"
                + "{$mode objfpc}{$H+}\n"
                + "uses\n"
                + "  " + uses + ";\n"
                + "var\n"
                + "  app: TTestRunner;\n"
                + "begin\n"
                + "  app := TTestRunner.Create(nil);\n"
                + "  app.Initialize;\n"
                + "  app.Title := 'CodeWitness fpcunit runner';\n"
                + "  app.Run;\n"
                + "  app.Free;\n"
                + "end.\n";
        try {
            Files.createDirectories(directory);
            Files.writeString(file, source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the fpcunit runner " + file, e);
        }
        return file;
    }

    /** @return the executable a test program compiles to. */
    static Path testExecutable(VerificationContext context, String name) {
        Path directory = context.artifacts().artifactsDirectory().resolve("pascal").resolve("tests");
        createDirectories(directory);
        return directory.resolve("cw-" + PascalBuildProvider.safeName(name) + PascalBuildProvider.executableSuffix());
    }

    private static boolean isConsoleRunner(Path file) {
        String source = PascalSourceParser.read(file).toLowerCase(Locale.ROOT);
        return source.contains("consoletestrunner") || source.contains("ttestrunner");
    }

    private static Path writeLog(VerificationContext context, String relative, String content) {
        return context.artifacts().write(relative, content == null ? "" : content);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException _) {
            return "";
        }
    }

    private static void createDirectories(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create " + directory, e);
        }
    }

    /**
     * A test program to compile and run.
     *
     * @param file          the program source
     * @param consoleRunner {@code true} when the program is an fpcunit console runner, which is asked for
     *     its XML report and therefore reports per test
     * @param name          the name the runner is reported under
     */
    public record Runner(Path file, boolean consoleRunner, String name) {
    }
}
