package dev.codewitness.swift;

import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.model.TestResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading XCTest's console output.
 *
 * <p>The parser exists because XCTest gives a command-line caller no structured report; these tests pin
 * the two things that makes it safe: the outcome is the runner's own closing line, and a failure's
 * message - which is where a generated test's counterexample line lives - survives.
 */
class SwiftTestReportParserTest {

    private static final String OUTPUT = """
            [3/5] Compiling Ledger Ledger.swift
            Test Suite 'All tests' started at 2026-10-12 07:14:06.147.
            Test Suite 'LedgerTests' started at 2026-10-12 07:14:06.150.
            Test Case '-[LedgerTests.LedgerTests testAdd]' started.
            Test Case '-[LedgerTests.LedgerTests testAdd]' passed (0.002 seconds).
            Test Case '-[LedgerTests.LedgerTests testFail]' started.
            /work/Tests/LedgerTests/LedgerTests.swift:6: error: -[LedgerTests.LedgerTests testFail] : XCTAssertEqual failed: ("3") is not equal to ("4") - R-001: sum must be 4
            Test Case '-[LedgerTests.LedgerTests testFail]' failed (0.191 seconds).
            Test Suite 'LedgerTests' failed at 2026-10-12 07:14:06.344.
            \t Executed 2 tests, with 1 failure (0 unexpected) in 0.193 (0.194) seconds
            """;

    @Test
    @DisplayName("the runner's closing line decides each case's outcome, and its duration is kept")
    void readsCases() {
        TestResult result = SwiftTestReportParser.parse(OUTPUT, null, "test");

        assertTrue(result.executed(), result.note());
        assertEquals(2, result.total());
        assertEquals(1, result.passed());
        assertEquals(1, result.failed());

        TestCaseResult passed = result.cases().get(0);
        assertEquals("LedgerTests.LedgerTests", passed.suite());
        assertEquals("testAdd", passed.name());
        assertEquals(TestOutcome.PASSED, passed.outcome());
        assertEquals(Duration.ofMillis(2), passed.duration());
    }

    @Test
    @DisplayName("a failure's message is carried, which is how a counterexample line reaches the engine")
    void keepsFailureMessage() {
        TestResult result = SwiftTestReportParser.parse(OUTPUT, null, "test");

        TestCaseResult failed = result.cases().get(1);
        assertEquals(TestOutcome.FAILED, failed.outcome());
        assertNotNull(failed.failureMessage());
        assertTrue(failed.failureMessage().contains("R-001"), failed.failureMessage());
        assertEquals("XCTAssertEqual", failed.failureType());
    }

    @Test
    @DisplayName("a generated test's counterexample line survives the console format")
    void keepsCounterexampleLine() {
        String output = """
                Test Case '-[LedgerTests.CwScSC_ROLLINGTests cw_random_search]' started.
                /work/Tests/LedgerTests/Generated/CwScSC_ROLLINGTests.swift:44: error: -[LedgerTests.CwScSC_ROLLINGTests cw_random_search] : CODEWITNESS_COUNTEREXAMPLE|case=37|seed=42|inputs={t1=85800000, t2=90000000}|expected=<=100000|actual=140000|invariant=observation <= 100000
                Test Case '-[LedgerTests.CwScSC_ROLLINGTests cw_random_search]' failed (0.010 seconds).
                """;

        TestResult result = SwiftTestReportParser.parse(output, null, "generated");

        assertTrue(result.executed(), result.note());
        assertEquals("LedgerTests.CwScSC_ROLLINGTests", result.cases().getFirst().suite());
        assertTrue(result.cases().getFirst().failureMessage().contains("CODEWITNESS_COUNTEREXAMPLE"));
    }

    @Test
    @DisplayName("an empty run is not executed, never a pass")
    void emptyOutputIsNotExecuted() {
        assertFalse(SwiftTestReportParser.parse("", null, "test").executed());
        assertFalse(SwiftTestReportParser.parse("   \n", null, "test").executed());
        assertFalse(SwiftTestReportParser.parse((String) null, null, "test").executed());
    }

    @Test
    @DisplayName("a Swift Testing suite is reported as unreadable rather than as a pass")
    void swiftTestingIsNotMistakenForXCTest() {
        String output = """
                ◇ Test run started.
                ↳ Testing Library Version: 1902
                ✔ Test run with 3 tests in 1 suite passed after 0.001 seconds.
                """;

        TestResult result = SwiftTestReportParser.parse(output, null, "test");

        assertFalse(result.executed());
        assertTrue(result.note().contains("Swift Testing"), result.note());
    }

    @Test
    @DisplayName("a run that ended early records the case the summary counted and the console never named")
    void incompleteRunIsRecorded() {
        String output = """
                Test Case '-[LedgerTests.LedgerTests testAdd]' started.
                Test Case '-[LedgerTests.LedgerTests testAdd]' passed (0.001 seconds).
                Test Case '-[LedgerTests.LedgerTests testCrash]' started.
                \t Executed 3 tests, with 2 failures (0 unexpected) in 0.100 (0.100) seconds
                """;

        TestResult result = SwiftTestReportParser.parse(output, null, "test");

        assertTrue(result.executed());
        assertEquals(3, result.total());
        assertTrue(result.cases().stream().anyMatch(c -> c.outcome() == TestOutcome.ERROR),
                result.cases().toString());
        assertTrue(result.cases().stream().anyMatch(c -> c.outcome() == TestOutcome.FAILED),
                result.cases().toString());
    }

    @Test
    @DisplayName("swift-corelibs-xctest's dot-separated dialect is read, because a Linux toolchain prints it")
    void readsCorelibsDialect() {
        String output = """
                Test Suite 'All tests' started at 2026-10-12 07:14:06.147
                Test Case 'WithdrawalLedgerTests.CwScSC_ROLLINGTests.testCwSearch' started at 2026-10-12 07:14:06.148
                /work/Tests/WithdrawalLedgerTests/Generated/CwScSC_ROLLINGTests.swift:44: error: WithdrawalLedgerTests.CwScSC_ROLLINGTests.testCwSearch : failed - CODEWITNESS_COUNTEREXAMPLE|case=0|seed=42|inputs={t1=85800000, t2=90000000}|actual=140000
                Test Case 'WithdrawalLedgerTests.CwScSC_ROLLINGTests.testCwSearch' failed (0.010 seconds)
                Test Case 'WithdrawalLedgerTests.WithdrawalLedgerTests.testAdd' started at 2026-10-12 07:14:06.150
                Test Case 'WithdrawalLedgerTests.WithdrawalLedgerTests.testAdd' passed (0.001 seconds)
                \t Executed 2 tests, with 1 failure (0 unexpected) in 0.011 (0.011) seconds
                """;

        TestResult result = SwiftTestReportParser.parse(output, null, "linux");

        assertTrue(result.executed(), result.note());
        assertEquals(2, result.total());
        assertEquals(1, result.passed());
        assertEquals(1, result.failed());
        TestCaseResult failed = result.cases().stream()
                .filter(c -> c.outcome() == TestOutcome.FAILED).findFirst().orElseThrow();
        assertEquals("WithdrawalLedgerTests.CwScSC_ROLLINGTests", failed.suite(),
                "the module-qualified suite the engine matches against a generated test's name");
        assertEquals("testCwSearch", failed.name());
        assertTrue(failed.failureMessage().contains("CODEWITNESS_COUNTEREXAMPLE"),
                failed.failureMessage());
    }
}
