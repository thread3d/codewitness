package dev.codewitness.verilog;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runs a Verilog project's existing testbenches with Icarus Verilog.
 *
 * <p>A Verilog test has no runner: a testbench is a top-level module that instantiates the design,
 * drives it and calls {@code $finish}, and the only way to run it is to compile the tree with that
 * testbench as the root ({@code iverilog -s <testbench>}) and simulate it ({@code vvp}). That is
 * exactly what this provider does, once per testbench, and it reports one test case per testbench
 * because there is no case-level protocol to read.
 *
 * <p>A testbench that does not compile is an ERROR, not a failing test: the compile is a statement
 * about the build, and Verilog's compile of one root also elaborates the design it instantiates. A
 * simulation that reports a counterexample, exits non-zero or prints a line opening with a failure
 * verdict is a failure; a simulation that runs to completion is a pass.
 *
 * <p>There is deliberately no single test command. A Verilog test is inherently two processes -
 * compile, then simulate - so {@link #testCommand} returns nothing rather than naming a command that
 * cannot have produced the result, and {@link #replayCommand} returns the two-step recipe a human
 * would type.
 */
public final class VerilogTestProvider implements VerificationTools.TestRunner {

    @Override
    public String id() {
        return "verilog-test";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.VERILOG);
    }

    @Override
    public TestResult runTests(VerificationContext context) {
        List<VerilogModule> testbenches = VerilogProjectDetector.testbenches(context.projectRoot());
        if (testbenches.isEmpty()) {
            return TestResult.notExecuted("no testbench module was found; a Verilog test is a top-level "
                    + "module that drives the design and calls $finish, and without one there is nothing to simulate");
        }
        List<Path> sources = VerilogProjectDetector.sourceFiles(context.projectRoot());
        String iverilog = VerilogTool.iverilog(context.projectInfo(), context);
        String vvp = VerilogTool.vvp(context.projectInfo(), context);

        List<TestCaseResult> cases = new ArrayList<>();
        List<Path> reports = new ArrayList<>();
        Duration total = Duration.ZERO;
        for (VerilogModule testbench : testbenches) {
            String suite = VerilogModuleParser.stem(testbench.file().getFileName().toString());
            String name = testbench.name();
            Path output = VerilogBuildProvider.simulationOutput(context,
                    "tests/" + safeName(suite) + "-" + safeName(name));
            try {
                Files.createDirectories(output.getParent());
            } catch (IOException e) {
                throw new UncheckedIOException("cannot create the directory for the compiled testbench", e);
            }
            context.emit("tests", "compiling testbench " + name);

            List<String> compile = VerilogBuildProvider.compileCommand(iverilog, sources, output, name);
            ProcessResult compiled = context.runner().run(ProcessSpec.builder(compile, context.projectRoot())
                    .environment(VerilogTool.environment(context))
                    .timeout(context.config().limits().timeout())
                    .maxOutputBytes(1_000_000)
                    .build());
            total = total.plus(compiled.duration());
            if (!compiled.started()) {
                cases.add(error(suite, name, "iverilog could not be started: " + compiled.failureReason()));
                continue;
            }
            if (!compiled.success()) {
                cases.add(VerilogTestReportParser.compileFailure(suite, name, compiled));
                reports.add(context.artifacts().write(
                        "verilog/tests/" + safeName(name) + "-compile.log", compiled.combinedOutput()));
                continue;
            }

            ProcessResult simulated = context.runner().run(ProcessSpec.builder(
                            List.of(vvp, "-N", output.toString()), context.projectRoot())
                    .environment(VerilogTool.environment(context))
                    .timeout(context.config().limits().timeout())
                    .maxOutputBytes(2_000_000)
                    .build());
            total = total.plus(simulated.duration());
            reports.add(context.artifacts().write(
                    "verilog/tests/" + safeName(name) + "-simulation.log", simulated.combinedOutput()));
            cases.add(VerilogTestReportParser.fromSimulation(suite, name, simulated));
        }

        if (cases.isEmpty()) {
            return TestResult.notExecuted("no testbench could be compiled or simulated");
        }
        String note = "each testbench was compiled with iverilog and simulated with vvp; a testbench is "
                + "reported as one case because Verilog gives a command line caller no case-level protocol";
        return TestResult.from(cases, total, reports, note);
    }

    @Override
    public List<String> testCommand(VerificationContext context) {
        // Verilog has no single test invocation: the phase compiles each testbench and then simulates
        // it. Naming only the compile or only the simulation would describe an invocation that cannot
        // have produced the result, so the evidence carries no command and the note says why.
        return List.of();
    }

    @Override
    public List<String> replayCommand(VerificationContext context, TestCaseResult testCase) {
        if (testCase == null) {
            return List.of();
        }
        List<Path> sources = VerilogProjectDetector.sourceFiles(context.projectRoot());
        if (sources.isEmpty()) {
            return List.of();
        }
        String iverilog = VerilogTool.iverilog(context.projectInfo(), context);
        String vvp = VerilogTool.vvp(context.projectInfo(), context);
        // Replaying one testbench is the two commands the adapter ran, joined into the shell line a
        // human would type. A test case's suite is the testbench file's own name and its name is the
        // module, which is what -s needs; when only the suite is known, the simulation root is taken
        // from the same convention.
        String root = testCase.name() == null || testCase.name().isBlank() ? testCase.suite() : testCase.name();
        Path output = VerilogBuildProvider.simulationOutput(context,
                "replay/" + safeName(root == null ? "testbench" : root));
        StringBuilder compile = new StringBuilder();
        compile.append(iverilog).append(' ').append(VerilogTool.DIALECT_FLAG)
                .append(" -s ").append(root == null ? "" : root)
                .append(" -o ").append(output);
        for (Path source : sources) {
            compile.append(' ').append(source);
        }
        return List.of(compile + " && " + vvp + " -N " + output);
    }

    private static TestCaseResult error(String suite, String name, String message) {
        return new TestCaseResult(suite, name, TestOutcome.ERROR, Duration.ZERO, "SimulationError", message, null);
    }

    private static String safeName(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' ? c : '_');
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
