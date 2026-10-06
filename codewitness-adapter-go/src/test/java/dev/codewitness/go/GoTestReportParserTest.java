package dev.codewitness.go;

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
 * {@code go test -json} is the adapter's machine readable report, so the parser is the Go equivalent
 * of a JUnit XML parser. These tests pin the event pairing that makes a case attributable to its
 * package, the failure-output capture that makes a counterexample structured, and the rule that a
 * package which failed to build is never reported as a passing suite.
 */
class GoTestReportParserTest {

    private static final String PASS_AND_FAIL = """
            {"Time":"2026-01-01T00:00:00Z","Action":"start","Package":"codewitness.local/ledger"}
            {"Time":"2026-01-01T00:00:00Z","Action":"run","Package":"codewitness.local/ledger","Test":"TestFee"}
            {"Time":"2026-01-01T00:00:00Z","Action":"output","Package":"codewitness.local/ledger","Test":"TestFee","Output":"--- PASS: TestFee (0.00s)\\n"}
            {"Time":"2026-01-01T00:00:00Z","Action":"pass","Package":"codewitness.local/ledger","Test":"TestFee","Elapsed":0.01}
            {"Time":"2026-01-01T00:00:00Z","Action":"run","Package":"codewitness.local/ledger","Test":"TestNegative"}
            {"Time":"2026-01-01T00:00:00Z","Action":"output","Package":"codewitness.local/ledger","Test":"TestNegative","Output":"    ledger_test.go:20: CODEWITNESS_COUNTEREXAMPLE|case=0|seed=|inputs={amount=-100}|expected=observation >= 500|actual=400\\n"}
            {"Time":"2026-01-01T00:00:00Z","Action":"fail","Package":"codewitness.local/ledger","Test":"TestNegative","Elapsed":0.02}
            {"Time":"2026-01-01T00:00:00Z","Action":"run","Package":"codewitness.local/ledger","Test":"TestSkip"}
            {"Time":"2026-01-01T00:00:00Z","Action":"output","Package":"codewitness.local/ledger","Test":"TestSkip","Output":"--- SKIP: TestSkip (0.00s)\\n"}
            {"Time":"2026-01-01T00:00:00Z","Action":"skip","Package":"codewitness.local/ledger","Test":"TestSkip","Elapsed":0}
            {"Time":"2026-01-01T00:00:00Z","Action":"output","Package":"codewitness.local/ledger","Output":"FAIL\\n"}
            {"Time":"2026-01-01T00:00:00Z","Action":"fail","Package":"codewitness.local/ledger","Elapsed":0.03}
            """;

    private static final String BUILD_FAILURE = """
            {"Action":"start","Package":"codewitness.local/broken"}
            {"Action":"output","Package":"codewitness.local/broken","Output":"# codewitness.local/broken\\n"}
            {"Action":"output","Package":"codewitness.local/broken","Output":"./broken.go:4:2: cannot use \\"not a number\\" (untyped string constant) as int64 value\\n"}
            {"Action":"build-output","Package":"codewitness.local/broken","Output":"broken.go:4:2: cannot use ...\\n"}
            {"Action":"build-fail","Package":"codewitness.local/broken","Elapsed":0}
            {"Action":"fail","Package":"codewitness.local/broken","Elapsed":0}
            """;

    @Test
    @DisplayName("cases are attributed to their package's module-qualified import path")
    void attributesCasesToTheirPackage() {
        TestResult result = GoTestReportParser.parse(PASS_AND_FAIL, "", "test", List.of());

        assertTrue(result.executed());
        assertEquals(3, result.total());
        assertEquals(1, result.passed());
        assertEquals(1, result.failed());
        assertEquals(1, result.skipped());
        for (TestCaseResult testCase : result.cases()) {
            assertEquals("codewitness.local/ledger", testCase.suite(),
                    "the suite must be the import path the mapper derives for the same file");
        }
    }

    @Test
    @DisplayName("a failing case carries the structured counterexample from its output events")
    void capturesFailureOutput() {
        TestResult result = GoTestReportParser.parse(PASS_AND_FAIL, "", "test", List.of());

        TestCaseResult failure = result.failures().getFirst();
        assertEquals("TestNegative", failure.name());
        assertEquals(TestOutcome.FAILED, failure.outcome());
        assertTrue(failure.failureMessage().contains("CODEWITNESS_COUNTEREXAMPLE"),
                "a counterexample must survive into the parsed failure: " + failure.failureMessage());
        assertTrue(failure.stackTrace().contains("inputs={amount=-100}"));
    }

    @Test
    @DisplayName("a package that failed to build is not executed, never a passing suite")
    void buildFailureIsNotExecuted() {
        TestResult result = GoTestReportParser.parse(BUILD_FAILURE, "", "test", List.of());

        assertFalse(result.executed());
        assertTrue(result.note().contains("could not build"), result.note());
        assertTrue(result.note().contains("codewitness.local/broken"), result.note());
    }

    @Test
    @DisplayName("a run that reported no case is not executed, never a pass")
    void noCasesIsNotExecuted() {
        TestResult result = GoTestReportParser.parse(
                "{\"Action\":\"pass\",\"Package\":\"codewitness.local/empty\",\"Elapsed\":0.001}\n", "", "test", List.of());

        assertFalse(result.executed());
        assertTrue(result.note().contains("reported no test case"), result.note());
    }

    @Test
    @DisplayName("a package-level output event is not mistaken for a test case")
    void packageLevelEventsAreNotCases() {
        TestResult result = GoTestReportParser.parse(PASS_AND_FAIL, "", "test", List.of());

        assertTrue(result.cases().stream().noneMatch(testCase -> testCase.name().isBlank()));
    }
}
