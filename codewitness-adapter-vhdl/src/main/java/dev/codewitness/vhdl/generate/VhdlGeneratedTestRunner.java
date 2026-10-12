package dev.codewitness.vhdl.generate;

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
import dev.codewitness.vhdl.VhdlAnalyzer;
import dev.codewitness.vhdl.VhdlBuildProvider;
import dev.codewitness.vhdl.VhdlProjectDetector;
import dev.codewitness.vhdl.VhdlTestCommand;
import dev.codewitness.vhdl.VhdlTestReportParser;
import dev.codewitness.vhdl.VhdlTool;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Executes generated scenario testbenches and reads the structured counterexamples out of the
 * simulation output.
 *
 * <p>The project's own sources are analysed once into a work library, then each generated testbench is
 * analysed into the same library and simulated on its own. Each generated test is its own simulation,
 * so its result is its own; an analysis failure is an ERROR for that test alone, because the generated
 * file is the only thing that changed. The counterexample line the testbench printed is the failure
 * message, so the engine reconstructs the same counterexample it would from any language.
 *
 * <p>Sources are written into the project's own tree under {@code generated-tests/} and removed again
 * in a {@code finally} block unless {@code report.keep_generated_tests} is set, exactly as the other
 * adapters leave theirs. A copy always stays in the run's artefact store, which is what makes a
 * counterexample reviewable after the run and what {@link #reproductionCommand} replays.
 */
public final class VhdlGeneratedTestRunner implements VerificationTools.GeneratedTestExecutor {

    /** Subdirectory of the artefact store the generated sources and logs are kept in. */
    static final String GENERATED_DIRECTORY = "generated-tests";

    /** Subdirectory of the project the generated sources are written into while they run. */
    static final String PROJECT_DIRECTORY = "generated-tests";

    @Override
    public String id() {
        return "vhdl-generated-tests";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.VHDL);
    }

    @Override
    public GeneratedTestExecution execute(VerificationContext context, List<GeneratedTest> tests) {
        if (tests.isEmpty()) {
            return GeneratedTestExecution.notExecuted(tests, "no tests to run");
        }
        // Snapshot the project's own sources before writing anything, so a generated file cannot be
        // picked up as one of the design sources it is analysed against.
        List<Path> sources = VhdlProjectDetector.sourceFiles(context.projectRoot());
        if (sources.isEmpty()) {
            return GeneratedTestExecution.notExecuted(tests, "no VHDL source file was found to analyse against");
        }
        String ghdl = VhdlTool.ghdl(context.projectInfo(), context);
        Path workDirectory = VhdlBuildProvider.workLibrary(context, "generated");

        List<Path> written = new ArrayList<>();
        List<Path> artifacts = new ArrayList<>();
        List<TestCaseResult> cases = new ArrayList<>();
        VhdlAnalyzer.Analysis project = VhdlAnalyzer.analyze(context, ghdl, workDirectory, sources);
        Duration total = project.duration();
        if (!project.success()) {
            for (GeneratedTest test : tests) {
                cases.add(error(test.className(), "the project did not analyse: " + project.failureOutput().strip()));
                artifacts.add(context.artifacts().write(
                        GENERATED_DIRECTORY + "/" + test.className() + "-analysis.log", project.failureOutput()));
            }
            return new GeneratedTestExecution(tests,
                    TestResult.from(cases, total, List.of(), "the project did not analyse, so no generated test ran"),
                    artifacts, "the project did not analyse");
        }

        Path projectGenerated = context.projectRoot().resolve(PROJECT_DIRECTORY);
        try {
            Files.createDirectories(projectGenerated);
            for (GeneratedTest test : tests) {
                Path target = projectGenerated.resolve(test.className() + ".vhd");
                Files.writeString(target, test.sourceCode(), StandardCharsets.UTF_8);
                written.add(target);
                artifacts.add(context.artifacts().copyIn(
                        target, GENERATED_DIRECTORY + "/" + test.className() + ".vhd"));
            }

            for (GeneratedTest test : tests) {
                Path target = projectGenerated.resolve(test.className() + ".vhd");
                context.emit("tests", "simulating generated testbench " + test.className());
                VhdlAnalyzer.Analysis analysed =
                        VhdlAnalyzer.analyze(context, ghdl, workDirectory, List.of(target));
                total = total.plus(analysed.duration());
                if (!analysed.success()) {
                    cases.add(error(test.className(), "the generated testbench did not analyse: "
                            + analysed.failureOutput().strip()));
                    artifacts.add(context.artifacts().write(
                            GENERATED_DIRECTORY + "/" + test.className() + "-analysis.log", analysed.failureOutput()));
                    continue;
                }
                ProcessResult simulated = context.runner().run(ProcessSpec.builder(
                                VhdlBuildProvider.runCommand(ghdl, workDirectory, test.className()),
                                context.projectRoot())
                        .environment(VhdlTool.environment(context))
                        .timeout(context.config().limits().timeout())
                        .maxOutputBytes(2_000_000)
                        .build());
                total = total.plus(simulated.duration());
                artifacts.add(context.artifacts().write(
                        GENERATED_DIRECTORY + "/" + test.className() + "-simulation.log", simulated.combinedOutput()));
                // The suite is the generated entity name, which is also the GeneratedTest's class name, so
                // the engine can pair the result with the obligation the test was produced for.
                cases.add(VhdlTestReportParser.fromSimulation(test.className(), test.className(), simulated));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot prepare generated tests", e);
        } finally {
            if (!context.config().report().keepGeneratedTests()) {
                for (Path path : written) {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException _) {
                        // leaving a generated file behind is untidy but not fatal
                    }
                }
                try {
                    if (Files.isDirectory(projectGenerated) && isDirectoryEmpty(projectGenerated)) {
                        Files.deleteIfExists(projectGenerated);
                    }
                } catch (IOException _) {
                    // best effort
                }
            }
        }

        if (cases.isEmpty()) {
            return new GeneratedTestExecution(tests, TestResult.notExecuted("no generated testbench was simulated"),
                    artifacts, "no generated testbench was simulated");
        }
        String note = "generated testbenches analysed with ghdl and simulated with ghdl -r (" + cases.size()
                + " case(s))";
        TestResult result = TestResult.from(cases, total, List.of(), note);
        return new GeneratedTestExecution(tests, result, artifacts, result.describeWithNote());
    }

    @Override
    public List<String> reproductionCommand(
            VerificationContext context, List<GeneratedTest> tests, Long seed, Integer caseIndex) {
        if (tests.isEmpty()) {
            return List.of();
        }
        GeneratedTest test = tests.getFirst();
        List<Path> sources = VhdlProjectDetector.sourceFiles(context.projectRoot());
        Path generated = context.artifacts().artifactsDirectory()
                .resolve(GENERATED_DIRECTORY).resolve(test.className() + ".vhd");
        // The seed and case are baked into the generated source, so no environment variable is needed to
        // reproduce it; the values passed here are recorded by the caller's counterexample, not consumed.
        return VhdlTestCommand.replay(context, sources, List.of(generated), test.className());
    }

    private static TestCaseResult error(String suite, String message) {
        return new TestCaseResult(suite, suite, TestOutcome.ERROR, Duration.ZERO,
                "SimulationError", message, null);
    }

    private static boolean isDirectoryEmpty(Path directory) {
        try (var stream = Files.list(directory)) {
            return stream.findAny().isEmpty();
        } catch (IOException _) {
            return false;
        }
    }
}
