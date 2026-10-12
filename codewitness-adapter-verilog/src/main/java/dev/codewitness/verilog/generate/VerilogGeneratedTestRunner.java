package dev.codewitness.verilog.generate;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.GeneratedTestExecution;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.verilog.VerilogBuildProvider;
import dev.codewitness.verilog.VerilogProjectDetector;
import dev.codewitness.verilog.VerilogTestCommand;
import dev.codewitness.verilog.VerilogTestReportParser;
import dev.codewitness.verilog.VerilogTool;

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
 * <p>A generated testbench is compiled together with the project's own sources with the generated
 * module as the simulation root, and simulated with {@code vvp -N}. Each generated test is its own
 * simulation, so its result is its own; a compile failure is an ERROR for that test alone, because the
 * generated file is the only thing that changed. The counterexample line the testbench printed is the
 * failure message, so the engine reconstructs the same counterexample it would from any language.
 *
 * <p>Sources are written into the project's own tree under {@code generated-tests/} and removed again
 * in a {@code finally} block unless {@code report.keep_generated_tests} is set, exactly as the other
 * adapters leave theirs. A copy always stays in the run's artefact store, which is what makes a
 * counterexample reviewable after the run and what {@link #reproductionCommand} replays.
 */
public final class VerilogGeneratedTestRunner implements VerificationTools.GeneratedTestExecutor {

    /** Subdirectory of the artefact store the generated sources and logs are kept in. */
    static final String GENERATED_DIRECTORY = "generated-tests";

    /** Subdirectory of the project the generated sources are written into while they run. */
    static final String PROJECT_DIRECTORY = "generated-tests";

    @Override
    public String id() {
        return "verilog-generated-tests";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.VERILOG);
    }

    @Override
    public GeneratedTestExecution execute(VerificationContext context, List<GeneratedTest> tests) {
        if (tests.isEmpty()) {
            return GeneratedTestExecution.notExecuted(tests, "no tests to run");
        }
        // Snapshot the project's own sources before writing anything, so a generated file cannot be
        // picked up as one of the design sources it is compiled against.
        List<Path> sources = VerilogProjectDetector.sourceFiles(context.projectRoot());
        if (sources.isEmpty()) {
            return GeneratedTestExecution.notExecuted(tests, "no Verilog source file was found to compile against");
        }
        String iverilog = VerilogTool.iverilog(context.projectInfo(), context);
        String vvp = VerilogTool.vvp(context.projectInfo(), context);

        Path projectGenerated = context.projectRoot().resolve(PROJECT_DIRECTORY);
        List<Path> written = new ArrayList<>();
        List<Path> artifacts = new ArrayList<>();
        List<TestCaseResult> cases = new ArrayList<>();
        Duration total = Duration.ZERO;
        try {
            Files.createDirectories(projectGenerated);
            for (GeneratedTest test : tests) {
                Path target = projectGenerated.resolve(test.className() + ".v");
                Files.writeString(target, test.sourceCode(), StandardCharsets.UTF_8);
                written.add(target);
                artifacts.add(context.artifacts().copyIn(
                        target, GENERATED_DIRECTORY + "/" + test.className() + ".v"));
            }

            for (GeneratedTest test : tests) {
                Path target = projectGenerated.resolve(test.className() + ".v");
                Path output = VerilogBuildProvider.simulationOutput(context,
                        "generated/" + test.className());
                try {
                    Files.createDirectories(output.getParent());
                } catch (IOException e) {
                    throw new UncheckedIOException("cannot create the directory for a generated simulation", e);
                }
                List<String> command = new ArrayList<>(
                        VerilogBuildProvider.compileCommand(iverilog, sources, output, test.className()));
                command.add(target.toString());
                ProcessResult compiled = context.runner().run(ProcessSpec.builder(command, context.projectRoot())
                        .environment(VerilogTool.environment(context))
                        .timeout(context.config().limits().timeout())
                        .maxOutputBytes(1_000_000)
                        .build());
                total = total.plus(compiled.duration());
                if (!compiled.started()) {
                    cases.add(error(test.className(), "iverilog could not be started: " + compiled.failureReason()));
                    continue;
                }
                if (!compiled.success()) {
                    cases.add(VerilogTestReportParser.compileFailure(test.className(), test.className(), compiled));
                    artifacts.add(context.artifacts().write(
                            GENERATED_DIRECTORY + "/" + test.className() + "-compile.log", compiled.combinedOutput()));
                    continue;
                }
                ProcessResult simulated = context.runner().run(ProcessSpec.builder(
                                List.of(vvp, "-N", output.toString()), context.projectRoot())
                        .environment(VerilogTool.environment(context))
                        .timeout(context.config().limits().timeout())
                        .maxOutputBytes(2_000_000)
                        .build());
                total = total.plus(simulated.duration());
                artifacts.add(context.artifacts().write(
                        GENERATED_DIRECTORY + "/" + test.className() + "-simulation.log", simulated.combinedOutput()));
                // The suite is the generated module name, which is also the GeneratedTest's class name, so
                // the engine can pair the result with the obligation the test was produced for.
                cases.add(VerilogTestReportParser.fromSimulation(test.className(), test.className(), simulated));
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
        String note = "generated testbenches compiled with iverilog and simulated with vvp (" + cases.size() + " case(s))";
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
        List<Path> sources = VerilogProjectDetector.sourceFiles(context.projectRoot());
        String iverilog = VerilogTool.iverilog(context.projectInfo(), context);
        String vvp = VerilogTool.vvp(context.projectInfo(), context);
        Path generated = context.artifacts().artifactsDirectory()
                .resolve(GENERATED_DIRECTORY).resolve(test.className() + ".v");
        Path output = VerilogBuildProvider.simulationOutput(context, "replay/" + test.className());

        StringBuilder script = new StringBuilder();
        script.append(VerilogTestCommand.quote(iverilog)).append(' ').append(VerilogTool.DIALECT_FLAG)
                .append(" -s ").append(test.className())
                .append(" -o ").append(VerilogTestCommand.quote(output.toString()));
        for (Path source : sources) {
            script.append(' ').append(VerilogTestCommand.quote(source.toString()));
        }
        script.append(' ').append(VerilogTestCommand.quote(generated.toString()));
        script.append(" && ").append(VerilogTestCommand.quote(vvp)).append(" -N ")
                .append(VerilogTestCommand.quote(output.toString()));
        // The seed and case are baked into the generated source, so no environment variable is needed to
        // reproduce it; the values passed here are recorded by the caller's counterexample, not consumed.
        return VerilogTestCommand.shell(script.toString());
    }

    private static TestCaseResult error(String suite, String message) {
        return new TestCaseResult(suite, suite, dev.codewitness.core.model.TestOutcome.ERROR, Duration.ZERO,
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
