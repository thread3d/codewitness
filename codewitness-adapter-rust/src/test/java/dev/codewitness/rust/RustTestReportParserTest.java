package dev.codewitness.rust;

import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.model.TestResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The libtest line protocol is the only report Cargo produces, so the parser is the Rust adapter's
 * equivalent of a JUnit XML parser. These tests pin the stream pairing that makes a case attributable
 * to a test target, and the failure-detail capture that makes a counterexample structured.
 */
class RustTestReportParserTest {

    private static final String STDERR = """
               Compiling withdrawal-ledger v0.1.0
                Finished `test` profile [unoptimized + debuginfo] target(s) in 0.5s
                     Running unittests src/lib.rs (target/debug/deps/withdrawal_ledger-abc)
                     Running tests/ledger.rs (target/debug/deps/ledger-def)
                   Doc-tests withdrawal_ledger
            """;

    private static final String STDOUT = """
            running 0 tests

            test result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s


            running 2 tests
            test test_fee_is_added ... ok
            test test_negative_amount ... FAILED

            failures:

            ---- test_negative_amount stdout ----
            thread 'test_negative_amount' panicked at tests/ledger.rs:5:5:
            CODEWITNESS_COUNTEREXAMPLE|case=0|seed=|inputs={amount=-100}|expected=observation >= 500|actual=400
            note: run with `RUST_BACKTRACE=1` environment variable to display a backtrace


            failures:
                test_negative_amount

            test result: FAILED. 1 passed; 1 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.01s


            running 0 tests

            test result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s
            """;

    @Test
    @DisplayName("cases are attributed to the test target named on the stderr Running lines")
    void attributesCasesToTheirTarget() {
        TestResult result = RustTestReportParser.parse(STDOUT, STDERR, "test", List.of());

        assertTrue(result.executed());
        assertEquals(2, result.total());
        assertEquals(1, result.passed());
        assertEquals(1, result.failed());
        assertEquals(0, result.skipped());
        for (TestCaseResult testCase : result.cases()) {
            assertEquals("ledger", testCase.suite(), "the suite is the test target, not a line of output");
        }
    }

    @Test
    @DisplayName("the failure detail block is attached, carrying the structured counterexample")
    void capturesFailureDetail() {
        TestResult result = RustTestReportParser.parse(STDOUT, STDERR, "test", List.of());

        TestCaseResult failure = result.failures().getFirst();
        assertEquals("test_negative_amount", failure.name());
        assertEquals(TestOutcome.FAILED, failure.outcome());
        assertEquals("panic", failure.failureType());
        assertTrue(failure.failureMessage().contains("CODEWITNESS_COUNTEREXAMPLE"),
                "a counterexample must survive into the parsed failure: " + failure.failureMessage());
        assertTrue(failure.stackTrace().contains("inputs={amount=-100}"));
    }

    @Test
    @DisplayName("documentation tests are not attributed to a requirement")
    void skipsDocumentationTests() {
        TestResult result = RustTestReportParser.parse(STDOUT, STDERR, "test", List.of());

        assertFalse(result.cases().stream().anyMatch(testCase -> testCase.suite() == null));
        assertEquals(2, result.cases().size());
    }

    @Test
    @DisplayName("a run that reported no case is not executed, never a pass")
    void noCasesIsNotExecuted() {
        TestResult result = RustTestReportParser.parse("running 0 tests\n\ntest result: ok.\n", STDERR, "test", List.of());

        assertFalse(result.executed());
        assertTrue(result.note().contains("reported no test case"));
    }

    @Test
    @DisplayName("the test target identifier is the source file stem")
    void targetIdentifierIsTheFileStem() {
        assertEquals("ledger", RustTestReportParser.stem("tests/ledger.rs"));
        assertEquals("lib", RustTestReportParser.stem("src/lib.rs"));
        assertEquals("cw_sc_rolling_ob_001", RustTestReportParser.stem("/tmp/work/tests/cw_sc_rolling_ob_001.rs"));
    }
}
