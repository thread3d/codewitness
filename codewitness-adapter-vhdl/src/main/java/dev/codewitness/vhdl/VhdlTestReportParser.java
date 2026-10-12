package dev.codewitness.vhdl;

import dev.codewitness.core.engine.CounterexampleProtocol;
import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;

import java.time.Duration;
import java.util.List;

/**
 * Turns the output of one simulated testbench into a test case result.
 *
 * <p>VHDL has no test framework and no structured report, so there is nothing to parse but the
 * simulator's own console. The unit is the testbench, not a case inside it: a testbench is a
 * top-level entity that instantiates the design, drives it and stops the simulation, and the language
 * gives a command line caller no way to ask it to run one case of several. That is why the adapter
 * reports one test case per testbench and says so, rather than inventing a case protocol real
 * testbenches do not follow.
 *
 * <p>A simulation is read as failed when it exits non-zero, when it printed a counterexample line from
 * a generated test, when GHDL stopped on an assertion or a report at failure severity, or when it
 * printed a line whose first word is {@code FAIL}, {@code FAILED}, {@code ERROR} or
 * {@code ASSERTION FAILED}. The last rule is a documented convention - a testbench that checks
 * something and prints its own verdict is expected to begin that line with the verdict - and the first
 * three are the simulator's own facts. A testbench that exits zero and prints none of them is reported
 * as passed: the simulation ran to completion, which is the only thing the toolchain can attest to.
 */
public final class VhdlTestReportParser {

    /** Marker GHDL prints for an assertion and for a report that stopped the simulation. */
    private static final List<String> STOP_MARKERS =
            List.of("(assertion error)", "(assertion failure)", "(report failure)", "(report error)");

    private VhdlTestReportParser() {
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

    /** @return the case result for a testbench GHDL rejected, which is an error, not a test failure. */
    public static TestCaseResult analysisFailure(String suite, String name, ProcessResult analysis) {
        return error(suite, name, analysis.duration(),
                "the testbench did not analyse: " + analysis.describeOutcome(), analysis.combinedOutput(), analysis);
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

    /**
     * @return the first line GHDL printed for a simulation-stopping assertion or report, or a line that
     *     opens with a failure verdict, or {@code null}.
     */
    static String failureLine(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        for (String raw : output.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            for (String marker : STOP_MARKERS) {
                if (line.contains(marker)) {
                    return line;
                }
            }
            String leading = line.toUpperCase(java.util.Locale.ROOT);
            if (leading.startsWith("FAIL") || leading.startsWith("ERROR")
                    || leading.startsWith("ASSERTION FAILED") || leading.startsWith("FATAL")) {
                return line;
            }
        }
        return null;
    }

    private static String summarise(String output) {
        return VhdlTool.lastLines(output, 8);
    }
}
