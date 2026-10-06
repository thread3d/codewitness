package dev.codewitness.python;

import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.model.TestResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing of the JUnit XML that pytest and the standard-library runner produce. */
class PythonTestReportParserTest {

    @Test
    @DisplayName("a pytest class name is resolved to its module so requirement mapping matches")
    void resolvesPytestClassname(@TempDir Path root) throws IOException {
        PythonTestContexts.write(root, "tests/test_ledger.py", "class LedgerTest:\n    pass\n");
        String protocol = "AssertionError: CODEWITNESS_COUNTEREXAMPLE|case=3|seed=42|inputs={a=700}|"
                + "expected=at most 1000|actual=1400|invariant=a &lt;= 1000";
        Path report = PythonTestContexts.write(root, "junit.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <testsuites>
                  <testsuite name="pytest" tests="2" failures="1" errors="0" skipped="0" time="0.12">
                    <testcase classname="tests.test_ledger.LedgerTest" name="test_fails" time="0.01">
                      <failure message="%s" type="AssertionError">%s</failure>
                    </testcase>
                    <testcase classname="tests.test_ledger.LedgerTest" name="test_passes" time="0.01"/>
                  </testsuite>
                </testsuites>
                """.formatted(protocol, protocol));

        TestResult result = PythonTestReportParser.parse(List.of(report), root, "test");

        assertTrue(result.executed());
        assertEquals(2, result.total());
        assertEquals(1, result.failed());
        var failure = result.failures().getFirst();
        assertEquals("tests.test_ledger", failure.suite(), "the module is what a requirement can map to");
        assertEquals("LedgerTest.test_fails", failure.name(), "the class stays in the replayable name");
        assertEquals(TestOutcome.FAILED, failure.outcome());
        assertTrue(failure.failureMessage().contains("CODEWITNESS_COUNTEREXAMPLE"));
    }

    @Test
    @DisplayName("the standard-library runner's report parses with a module class name")
    void parsesUnittestReport(@TempDir Path root) throws IOException {
        PythonTestContexts.write(root, "tests/test_ledger.py", "");
        Path report = PythonTestContexts.write(root, "junit.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <testsuites name="codewitness" tests="2" failures="0" errors="1" skipped="1" time="0.3">
                  <testsuite name="codewitness" tests="2" failures="0" errors="1" skipped="1" time="0.3">
                    <testcase classname="tests.test_ledger" name="test_ok" time="0.01"/>
                    <testcase classname="tests.test_ledger" name="test_broken" time="0.02">
                      <error message="ImportError: no module named ledger" type="ImportError">trace</error>
                    </testcase>
                    <testcase classname="tests.test_ledger" name="test_skipped" time="0.0">
                      <skipped message="not applicable"/>
                    </testcase>
                  </testsuite>
                </testsuites>
                """);

        TestResult result = PythonTestReportParser.parse(List.of(report), root, "test");

        assertEquals(3, result.total());
        assertEquals(1, result.passed());
        assertEquals(1, result.errors());
        assertEquals(1, result.skipped());
        assertEquals("tests.test_ledger", result.cases().getFirst().suite());
    }

    @Test
    @DisplayName("a report with no test cases is reported as not executed, never as a pass")
    void emptyReportIsNotExecuted(@TempDir Path root) throws IOException {
        Path report = PythonTestContexts.write(root, "junit.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <testsuites><testsuite name="pytest" tests="0" failures="0" errors="0" skipped="0" time="0"/></testsuites>
                """);

        TestResult result = PythonTestReportParser.parse(List.of(report), root, "test");

        assertEquals(false, result.executed());
    }
}
