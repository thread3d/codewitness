package dev.codewitness.support;

import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.model.TestResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing of the JUnit report Node's own test runner writes. */
class NodeTestReportParserTest {

    private static final String REPORT = """
            <?xml version="1.0" encoding="utf-8"?>
            <testsuites>
            \t<testcase name="deposit adds" time="0.001" classname="test" file="/p/tests/ledger.test.js"/>
            \t<testcase name="rejects zero" time="0.002" classname="test" file="/p/tests/ledger.test.js" failure="CODEWITNESS_COUNTEREXAMPLE|case=3|seed=42|inputs={amount=-100}|expected=observation &lt;= 100000|actual=100100|invariant=observation &lt;= 100000|step=second">
            \t\t<failure type="testCodeFailure" message="CODEWITNESS_COUNTEREXAMPLE|case=3|seed=42">
            [Error [ERR_TEST_FAILURE]: CODEWITNESS_COUNTEREXAMPLE|case=3|seed=42]
            \t\t</failure>
            \t</testcase>
            \t<testcase name="skipped case" time="0.000" classname="test" file="/p/tests/ledger.test.js">
            \t\t<skipped type="skipped" message="true"/>
            \t</testcase>
            \t<testcase name="cw_case0000" time="0.004" classname="test" file="/p/tests/cw_sc_rolling_ob_001.test.mjs" failure="CODEWITNESS_COUNTEREXAMPLE|case=0|actual=140000">
            \t\t<failure type="testCodeFailure" message="CODEWITNESS_COUNTEREXAMPLE|case=0|actual=140000"/>
            \t</testcase>
            </testsuites>
            """;

    /**
     * The report Node writes before 24: no {@code file} attribute anywhere, and the only name a case
     * has is the {@code <testsuite>} it is nested in. This is the shape the CI runner produces, so it is
     * the shape a run on the adapter's supported floors actually has to parse.
     */
    private static final String REPORT_BEFORE_NODE_24 = """
            <?xml version="1.0" encoding="utf-8"?>
            <testsuites>
            \t<testsuite name="cw_sc_rolling_ob_001" time="0.004" disabled="0" errors="0" tests="1" failures="1" skipped="0" hostname="ci">
            \t\t<testcase name="cw_case0000" time="0.004" classname="test" failure="CODEWITNESS_COUNTEREXAMPLE|case=0|actual=140000">
            \t\t\t<failure type="testCodeFailure" message="CODEWITNESS_COUNTEREXAMPLE|case=0|actual=140000"/>
            \t\t</testcase>
            \t</testsuite>
            \t<testsuite name="ledger" time="0.001" disabled="0" errors="0" tests="1" failures="0" skipped="0" hostname="ci">
            \t\t<testcase name="totals are the sum" time="0.001" classname="test"/>
            \t</testsuite>
            </testsuites>
            """;

    @Test
    @DisplayName("passing, failing and skipped cases are read with their file-derived suite")
    void parsesCases() {
        TestResult result = NodeTestReportParser.parse(REPORT, "test", List.of());

        assertTrue(result.executed());
        assertEquals(4, result.total());
        assertEquals(1, result.passed());
        assertEquals(2, result.failed());
        assertEquals(1, result.skipped());

        TestCaseResult failure = result.failures().getFirst();
        assertEquals("ledger", failure.suite());
        assertEquals("rejects zero", failure.name());
        assertEquals(TestOutcome.FAILED, failure.outcome());
        assertNotNull(failure.failureMessage());
        assertTrue(failure.failureMessage().contains("CODEWITNESS_COUNTEREXAMPLE"),
                "the counterexample protocol line must survive XML decoding: " + failure.failureMessage());
        assertTrue(failure.failureMessage().contains("observation <= 100000"),
                "escaped entities must be decoded: " + failure.failureMessage());
    }

    @Test
    @DisplayName("a generated test's suite is exactly the class name the generator chose")
    void derivesGeneratedSuite() {
        TestResult result = NodeTestReportParser.parse(REPORT, "test", List.of());

        TestCaseResult generated = result.cases().stream()
                .filter(testCase -> testCase.name().equals("cw_case0000"))
                .findFirst()
                .orElseThrow();
        assertEquals("cw_sc_rolling_ob_001", generated.suite(),
                "the engine maps a generated result back to its obligation by this name");
    }

    @Test
    @DisplayName("before Node 24 the enclosing suite names a case, because the report carries no file")
    void derivesSuiteFromTheEnclosingSuite() {
        TestResult result = NodeTestReportParser.parse(REPORT_BEFORE_NODE_24, "test", List.of());

        assertTrue(result.executed());
        assertEquals(2, result.total());
        TestCaseResult generated = result.cases().stream()
                .filter(testCase -> testCase.name().equals("cw_case0000"))
                .findFirst()
                .orElseThrow();
        assertEquals("cw_sc_rolling_ob_001", generated.suite(),
                "without this the whole run collapses to suite 'test' and no result is attributable");
        TestCaseResult existing = result.cases().stream()
                .filter(testCase -> testCase.name().equals("totals are the sum"))
                .findFirst()
                .orElseThrow();
        assertEquals("ledger", existing.suite());
    }

    @Test
    @DisplayName("a file the report does name is still authoritative over the enclosing suite")
    void fileWinsOverTheEnclosingSuite() {
        String report = """
                <testsuites>
                \t<testsuite name="some describe">
                \t\t<testcase name="case" time="0.001" classname="test" file="/p/tests/ledger.test.js"/>
                \t</testsuite>
                </testsuites>
                """;

        TestResult result = NodeTestReportParser.parse(report, "test", List.of());

        assertEquals("ledger", result.cases().getFirst().suite());
    }

    @Test
    @DisplayName("a suite the case is no longer inside does not name it")
    void closedSuiteDoesNotNameALaterCase() {
        String report = """
                <testsuites>
                \t<testsuite name="closed">
                \t\t<testcase name="inside" time="0.001" classname="test"/>
                \t</testsuite>
                \t<testcase name="after" time="0.001" classname="test"/>
                </testsuites>
                """;

        TestResult result = NodeTestReportParser.parse(report, "test", List.of());

        assertEquals("closed", result.cases().getFirst().suite());
        assertEquals("test", result.cases().get(1).suite(),
                "a case outside the suite must not inherit its name");
    }

    @Test
    @DisplayName("an empty report is 'not executed', never a passing suite")
    void emptyReportIsNotExecuted() {
        assertFalse(NodeTestReportParser.parse("", "test", List.of()).executed());
        assertFalse(NodeTestReportParser.parse("<testsuites></testsuites>", "test", List.of()).executed());
    }

    @Test
    @DisplayName("suite names drop the extension and the test/spec marker")
    void derivesSuiteNames() {
        assertEquals("ledger", NodeTestReportParser.suiteOf("/p/tests/ledger.test.js", "test"));
        assertEquals("ledger", NodeTestReportParser.suiteOf("/p/tests/ledger.spec.mjs", "test"));
        assertEquals("ledger", NodeTestReportParser.suiteOf("C:\\\\p\\\\tests\\\\ledger.test.cjs", "test"));
        assertEquals("cw_sc_fee_ob_002", NodeTestReportParser.suiteOf("/p/tests/cw_sc_fee_ob_002.test.mjs", null));
        assertEquals("ledger", NodeTestReportParser.suiteOf("/p/tests/ledger.test.ts", "test"));
        assertEquals("ledger", NodeTestReportParser.suiteOf("/p/tests/ledger.spec.mts", "test"));
        assertEquals("ledger", NodeTestReportParser.suiteOf("C:\\\\p\\\\tests\\\\ledger.test.ts", "test"));
        assertEquals("some suite", NodeTestReportParser.suiteOf(null, "some suite"));
        assertEquals("test", NodeTestReportParser.suiteOf(null, null));
    }

    @Test
    @DisplayName("XML entities in an element body are decoded")
    void decodesEntities() {
        assertEquals("a < b && c > d", NodeTestReportParser.unescape("a &lt; b &amp;&amp; c &gt; d"));
        assertEquals("x\ny", NodeTestReportParser.unescape("x&#10;y"));
    }
}
