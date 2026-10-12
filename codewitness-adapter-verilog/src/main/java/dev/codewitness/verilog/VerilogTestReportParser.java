package dev.codewitness.verilog;

import dev.codewitness.core.engine.CounterexampleProtocol;
import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the output of one simulated testbench into a test case result.
 *
 * <p>Verilog has no test framework and no structured report, so there is nothing to parse but the
 * simulator's own console. The unit is the testbench, not a case inside it: a testbench is a
 * top-level simulation that drives the design, checks it and calls {@code $finish}, and the language
 * gives a command line caller no way to ask it to run one case of several. That is why the adapter
 * reports one test case per testbench and says so, rather than inventing a case protocol real
 * testbenches do not follow.
 *
 * <p>A simulation is read as failed when it exits non-zero, when it printed a counterexample line
 * from a generated test, or when it printed a line whose first word is {@code FAIL}, {@code FAILED},
 * {@code ERROR} or {@code ASSERTION FAILED}. The last rule is a documented convention - a testbench
 * that checks something and prints its own verdict is expected to begin that line with the verdict -
 * and the other two are the simulator's own facts. A testbench that exits zero and prints none of
 * them is reported as passed: the simulation ran to completion, which is the only thing the
 * toolchain can attest to.
 */
public final class VerilogTestReportParser {

    /** A line that opens with a failure verdict, which is the convention a self-checking testbench follows. */
    private static final Pattern FAILURE_LINE =
            Pattern.compile("(?m)^\\s*(FAIL(?:ED|URE)?|ERROR|ASSERTION FAILED|FATAL)\\b.*");

    private VerilogTestReportParser() {
    }

    /** @return the case result for a testbench whose simulation ran (or failed to run). */
    public static TestCaseResult fromSimulation(String suite, String name, ProcessResult run) {
        Duration duration = run.duration();
        if (!run.started()) {
            return error(suite, name, duration, "simulation could not start: " + run.failureReason(), "", run);
        }
        String output = run.combinedOutput();
        String counterexample = counterexampleLine(output);
        if (counterexample != null) {
            return new TestCaseResult(suite, name, TestOutcome.FAILED, duration, "Counterexample",
                    counterexample, summarise(output));
        }
        if (run.timedOut()) {
            return error(suite, name, duration, "simulation timed out after " + duration.toSeconds() + "s",
                    output, run);
        }
        String failure = failureLine(output);
        if (failure != null) {
            return new TestCaseResult(suite, name, TestOutcome.FAILED, duration, "SimulationFailure",
                    failure, summarise(output));
        }
        if (run.exitCode() != 0) {
            return new TestCaseResult(suite, name, TestOutcome.FAILED, duration, "SimulationExit",
                    "the simulation exited with code " + run.exitCode(), summarise(output));
        }
        return TestCaseResult.passed(suite, name, duration);
    }

    /** @return the case result for a testbench the compiler rejected, which is an error, not a test failure. */
    public static TestCaseResult compileFailure(String suite, String name, ProcessResult compile) {
        return error(suite, name, compile.duration(),
                "the testbench did not compile: " + compile.describeOutcome(), compile.combinedOutput(), compile);
    }

    private static TestCaseResult error(
            String suite, String name, Duration duration, String message, String output, ProcessResult result) {
        return new TestCaseResult(suite, name, TestOutcome.ERROR, duration, "SimulationError",
                message, summarise(output));
    }

    /** @return the first generated-test counterexample line in the output, or {@code null}. */
    static String counterexampleLine(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        for (String line : output.split("\\R")) {
            if (line.contains(CounterexampleProtocol.MARKER) && CounterexampleProtocol.decode(line).isPresent()) {
                return line.strip();
            }
        }
        return null;
    }

    /** @return the first line that opens with a failure verdict, or {@code null}. */
    static String failureLine(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        Matcher matcher = FAILURE_LINE.matcher(output);
        return matcher.find() ? matcher.group().strip() : null;
    }

    private static String summarise(String output) {
        return VerilogTool.lastLines(output, 8);
    }
}
