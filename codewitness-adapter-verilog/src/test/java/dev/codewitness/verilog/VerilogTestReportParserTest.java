package dev.codewitness.verilog;

import dev.codewitness.core.engine.CounterexampleProtocol;
import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading a simulated testbench's console as a test case. */
class VerilogTestReportParserTest {

    private static ProcessResult result(int exitCode, String output, boolean timedOut) {
        return new ProcessResult(List.of("vvp", "-N", "sim.vvp"), Path.of("."), true, exitCode,
                output, "", Duration.ofMillis(12), timedOut, null);
    }

    @Test
    @DisplayName("a simulation that runs to completion is a pass")
    void cleanRunPasses() {
        TestCaseResult testCase = VerilogTestReportParser.fromSimulation(
                "ledger_tb", "ledger_tb", result(0, "PASS: all checks passed\n", false));
        assertEquals(TestOutcome.PASSED, testCase.outcome());
    }

    @Test
    @DisplayName("a line opening with FAIL is a failure even when the simulator exits zero")
    void failLineFails() {
        TestCaseResult testCase = VerilogTestReportParser.fromSimulation(
                "ledger_tb", "ledger_tb", result(0, "FAIL: the balance changed\n", false));
        assertEquals(TestOutcome.FAILED, testCase.outcome());
        assertTrue(testCase.failureMessage().contains("FAIL"), testCase.failureMessage());
    }

    @Test
    @DisplayName("a generated counterexample line is the failure message")
    void counterexampleIsCarried() {
        String line = CounterexampleProtocol.encodeCounterexample(
                3, 42L, java.util.Map.of("amount", 5), "observation == 0", "1", "observation == 0");
        TestCaseResult testCase = VerilogTestReportParser.fromSimulation(
                "cw_sc_x", "cw_sc_x", result(1, line + "\nFATAL: bad\n", false));

        assertEquals(TestOutcome.FAILED, testCase.outcome());
        assertEquals(line, testCase.failureMessage());
        assertTrue(CounterexampleProtocol.decode(testCase.failureMessage()).isPresent());
    }

    @Test
    @DisplayName("a non-zero exit with no marker is a failure")
    void exitCodeFails() {
        TestCaseResult testCase = VerilogTestReportParser.fromSimulation(
                "ledger_tb", "ledger_tb", result(1, "FATAL: file.v:5: bad\n", false));
        assertEquals(TestOutcome.FAILED, testCase.outcome());
    }

    @Test
    @DisplayName("a timeout is an error, not a code failure")
    void timeoutIsError() {
        TestCaseResult testCase = VerilogTestReportParser.fromSimulation(
                "ledger_tb", "ledger_tb", result(-1, "", true));
        assertEquals(TestOutcome.ERROR, testCase.outcome());
        assertTrue(testCase.failureMessage().contains("timed out"), testCase.failureMessage());
    }

    @Test
    @DisplayName("a testbench that does not compile is an error, not a failing test")
    void compileFailureIsError() {
        TestCaseResult testCase = VerilogTestReportParser.compileFailure(
                "ledger_tb", "ledger_tb", result(1, "ledger_tb.v:8: syntax error\n", false));
        assertEquals(TestOutcome.ERROR, testCase.outcome());
        assertFalse(testCase.failureMessage().isBlank());
    }
}
