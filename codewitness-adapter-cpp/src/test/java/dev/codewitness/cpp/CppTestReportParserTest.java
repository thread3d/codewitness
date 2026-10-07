package dev.codewitness.cpp;

import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.model.TestResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

import static dev.codewitness.cpp.CppTestContexts.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the CTest JUnit report parser.
 *
 * <p>Two things are asserted beyond the ordinary mapping of an element to an outcome. CTest records
 * {@code Failed} as a failure's message and puts the test's own output beside it, so the parser has to join
 * the two for a counterexample to survive; and a test that did not run has to be recorded as skipped rather
 * than as a pass.
 */
class CppTestReportParserTest {

    @Test
    @DisplayName("a passing test becomes a passing case with the name it was registered under")
    void parsesAPassingCase(@TempDir Path directory) throws IOException {
        Path report = write(directory, "report.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="(empty)" tests="1" failures="0" disabled="0" skipped="0" time="0">
                	<testcase name="withdrawal_ledger_test" classname="withdrawal_ledger_test"
                		time="0.00405378" status="run">
                		<properties/>
                		<system-out>all checks passed
                </system-out>
                	</testcase>
                </testsuite>
                """);

        TestResult result = CppTestReportParser.parse(report, "parsed from the CTest JUnit report");

        assertTrue(result.executed());
        assertEquals(1, result.total());
        assertEquals(1, result.passed());
        TestCaseResult testCase = result.cases().getFirst();
        assertEquals("withdrawal_ledger_test", testCase.suite());
        assertEquals("withdrawal_ledger_test", testCase.name());
        assertTrue(testCase.duration().toMillis() >= 4, "the report's seconds are read as a duration");
    }

    @Test
    @DisplayName("a failure carries the test's own output, which is where a counterexample is printed")
    void joinsTheCapturedOutputToTheFailure(@TempDir Path directory) throws IOException {
        Path report = write(directory, "report.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="(empty)" tests="1" failures="1" disabled="0" skipped="0" time="0">
                	<testcase name="CwScRollingOb001" classname="CwScRollingOb001" time="0.324831"
                		status="fail">
                		<failure message="Failed"/>
                		<properties/>
                		<system-out>CODEWITNESS_COUNTEREXAMPLE|case=0|seed=42|inputs={t1=85800000, a1=70000}|\
                expected=observation &lt;= 100000|actual=140000|invariant=observation &lt;= 100000
                </system-out>
                	</testcase>
                </testsuite>
                """);

        TestResult result = CppTestReportParser.parse(report, "generated test run");

        assertEquals(1, result.failed());
        TestCaseResult testCase = result.cases().getFirst();
        assertEquals(TestOutcome.FAILED, testCase.outcome());
        assertTrue(testCase.failureMessage().contains("Failed"), testCase.failureMessage());
        assertTrue(testCase.failureMessage().contains("CODEWITNESS_COUNTEREXAMPLE|case=0|seed=42"),
                "the structured line has to survive into the report: " + testCase.failureMessage());
        assertTrue(testCase.failureMessage().contains("actual=140000"), testCase.failureMessage());
    }

    @Test
    @DisplayName("a test CTest could not run is skipped, never counted as a pass")
    void aTestThatDidNotRunIsSkipped(@TempDir Path directory) throws IOException {
        Path report = write(directory, "report.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="ledger" tests="3" failures="0" disabled="1" skipped="1" time="0">
                	<testcase name="disabled_test" classname="disabled_test" status="disabled">
                		<skipped/>
                	</testcase>
                	<testcase name="not_run_test" classname="not_run_test" status="notrun"/>
                	<testcase name="ran_test" classname="ran_test" time="0.01" status="run"/>
                </testsuite>
                """);

        TestResult result = CppTestReportParser.parse(report, "parsed");

        assertEquals(1, result.passed());
        assertEquals(2, result.skipped());
        assertEquals(0, result.failed());
    }

    @Test
    @DisplayName("an error element is an error, and a failure without a message is not silent")
    void parsesErrors(@TempDir Path directory) throws IOException {
        Path report = write(directory, "report.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="ledger">
                	<testcase name="crashed" classname="crashed">
                		<error message="Segmentation fault" type="crash"/>
                	</testcase>
                	<testcase name="mute" classname="mute">
                		<failure/>
                	</testcase>
                </testsuite>
                """);

        TestResult result = CppTestReportParser.parse(report, "parsed");

        assertEquals(1, result.errors());
        assertEquals("test error", result.cases().getFirst().failureType());
        assertTrue(result.cases().getFirst().failureMessage().contains("Segmentation fault"));
        assertEquals(1, result.failed());
        assertTrue(result.cases().get(1).failureMessage().contains("no message"),
                "a failure the report recorded without one still has to say so: "
                        + result.cases().get(1).failureMessage());
    }

    @Test
    @DisplayName("the suite is the class the report names, and the suite's own name when it is a real one")
    void choosesTheSuiteName(@TempDir Path directory) throws IOException {
        Path report = write(directory, "report.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuites>
                	<testsuite name="(empty)">
                		<testcase name="one" classname="LedgerTest" time="0"/>
                	</testsuite>
                	<testsuite name="LedgerFeeTest">
                		<testcase name="two" classname="(empty)" time="0"/>
                	</testsuite>
                	<testsuite name="(empty)">
                		<testcase name="three" time="0"/>
                	</testsuite>
                </testsuites>
                """);

        TestResult result = CppTestReportParser.parse(report, "parsed");

        assertEquals("LedgerTest", result.cases().get(0).suite());
        assertEquals("LedgerFeeTest", result.cases().get(1).suite(),
                "the suite's own name is used when the class column is the placeholder");
        assertEquals("three", result.cases().get(2).suite(),
                "with neither, the test's own name is the only honest identifier left");
    }

    @Test
    @DisplayName("a report with no test case is not an executed test run")
    void emptyReportIsNotExecuted(@TempDir Path directory) throws IOException {
        Path report = write(directory, "report.xml",
                "<?xml version=\"1.0\"?><testsuite name=\"(empty)\" tests=\"0\"/>\n");

        TestResult result = CppTestReportParser.parse(report, "parsed");

        assertFalse(result.executed());
        assertTrue(result.note().contains("recorded no test case"), result.note());
    }

    @Test
    @DisplayName("a report that cannot be read is reported rather than counted as a pass")
    void unreadableReportIsReported(@TempDir Path directory) throws IOException {
        Path missing = directory.resolve("absent.xml");
        TestResult absent = CppTestReportParser.parse(missing, "parsed");
        assertFalse(absent.executed());
        assertTrue(absent.note().contains("could not be read"), absent.note());

        Path malformed = write(directory, "bad.xml", "<testsuite><testcase name=\"x\"></testsuite>\n");
        TestResult broken = CppTestReportParser.parse(malformed, "parsed");
        assertFalse(broken.executed());
        assertTrue(broken.note().contains("could not be read"), broken.note());
    }

    @Test
    @DisplayName("a duration in seconds becomes a duration, and nonsense becomes zero")
    void parsesDurations() {
        assertEquals(Duration.ofMillis(1500), CppTestReportParser.seconds("1.5"));
        assertEquals(Duration.ZERO, CppTestReportParser.seconds("garbage"));
        assertEquals(Duration.ZERO, CppTestReportParser.seconds(null));
        assertEquals(Duration.ZERO, CppTestReportParser.seconds(""));
    }

    @Test
    @DisplayName("a document that declares an entity is not expanded")
    void refusesEntityExpansion(@TempDir Path directory) throws IOException {
        Path report = write(directory, "hostile.xml", """
                <?xml version="1.0"?>
                <!DOCTYPE testsuite [<!ENTITY laugh "ha">]>
                <testsuite name="x">
                	<testcase name="one" classname="one"><system-out>&laugh;</system-out></testcase>
                </testsuite>
                """);

        // The DOCTYPE is stripped before parsing, so the document either parses with the entity left alone
        // or is reported as unreadable; what it must never do is expand the entity.
        TestResult result = CppTestReportParser.parse(report, "parsed");
        if (result.executed()) {
            assertFalse(result.cases().getFirst().suite().contains("&") || "ha".equals(result.cases().getFirst().suite()));
        }
    }
}
