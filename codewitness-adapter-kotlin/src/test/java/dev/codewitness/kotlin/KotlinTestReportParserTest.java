package dev.codewitness.kotlin;

import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.model.TestResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JUnit XML reports a Kotlin project's build writes.
 *
 * <p>The formats are copied from real Surefire output for a Kotlin class, including the fully qualified
 * class name a Kotlin test is reported under and the failure text, which is where a generated test's
 * counterexample line travels.
 */
class KotlinTestReportParserTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("a Kotlin test class is reported by its fully qualified name and its function by its name")
    void readsASurefireReport() throws IOException {
        Path report = KotlinTestContexts.write(tmp, "target/surefire-reports/TEST-ledger.LedgerTest.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" version="3.0"
                           name="ledger.LedgerTest" time="0.039" tests="2" errors="0" skipped="0" failures="1">
                  <testcase name="staysUnder" classname="ledger.LedgerTest" time="0.021"/>
                  <testcase name="rejectsTooMuch" classname="ledger.LedgerTest" time="0.018">
                    <failure message="expected: &lt;500&gt; but was: &lt;400&gt;"
                             type="org.opentest4j.AssertionFailedError">CODEWITNESS_COUNTEREXAMPLE|case=0|actual=400</failure>
                  </testcase>
                </testsuite>
                """);

        TestResult result = KotlinTestReportParser.parse(List.of(report), "parsed from 1 report");

        assertTrue(result.executed());
        assertEquals(2, result.total());
        assertEquals(1, result.passed());
        assertEquals(1, result.failed());
        TestCaseResult failure = result.cases().stream()
                .filter(testCase -> testCase.outcome() == TestOutcome.FAILED).findFirst().orElseThrow();
        assertEquals("ledger.LedgerTest", failure.suite());
        assertEquals("rejectsTooMuch", failure.name());
        assertTrue(failure.failureMessage().contains("expected"), failure.failureMessage());
        assertTrue(failure.stackTrace().contains("CODEWITNESS_COUNTEREXAMPLE"),
                "the failure text is carried verbatim, because that is where a counterexample travels");
    }

    @Test
    @DisplayName("a skipped case is not evidence, and an error is not a failure")
    void skippedAndErroredCasesAreDistinguished() throws IOException {
        Path report = KotlinTestContexts.write(tmp, "target/surefire-reports/TEST-A.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="example.ATest" time="0.1">
                  <testcase name="skipped" classname="example.ATest"><skipped message="no reason"/></testcase>
                  <testcase name="errored" classname="example.ATest"><error type="java.lang.IllegalStateException"
                      message="boom">stack</error></testcase>
                  <testcase name="passed" classname="example.ATest"/>
                </testsuite>
                """);

        TestResult result = KotlinTestReportParser.parse(List.of(report), "note");

        assertEquals(1, result.skipped());
        assertEquals(1, result.errors());
        assertEquals(1, result.passed());
        assertEquals(0, result.failed());
    }

    @Test
    @DisplayName("a malformed report is an error case, not a silent gap")
    void aMalformedReportIsRecorded() throws IOException {
        Path report = KotlinTestContexts.write(tmp, "target/surefire-reports/TEST-Broken.xml", "<testsuite>");

        TestResult result = KotlinTestReportParser.parse(List.of(report), "note");

        assertEquals(1, result.total(), result.cases().toString());
        assertEquals(TestOutcome.ERROR, result.cases().get(0).outcome());
        assertEquals("report-parse-failure", result.cases().get(0).name());
    }

    @Test
    @DisplayName("a report with no test cases is not an executed suite")
    void aReportWithNoCasesIsNotAPass() throws IOException {
        Path report = KotlinTestContexts.write(tmp, "target/surefire-reports/TEST-Empty.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="example.EmptyTest" time="0.0"/>
                """);

        TestResult result = KotlinTestReportParser.parse(List.of(report), "note");

        assertFalse(result.executed(), "a suite that reported nothing is not a passing run");
        assertTrue(result.note().contains("contained no test cases"), result.note());
    }

    @Test
    @DisplayName("reports are found under Maven and Gradle conventions and deleted before a run")
    void reportsAreFoundAndCleared() throws IOException {
        Path root = KotlinTestContexts.project(tmp.resolve("project"));
        KotlinTestContexts.write(root, "pom.xml", KotlinTestContexts.MAVEN_POM);
        Path surefire = KotlinTestContexts.write(root, "target/surefire-reports/TEST-A.xml",
                "<testsuite name=\"a\"/>");
        KotlinTestContexts.write(root, "build/test-results/test/TEST-B.xml", "<testsuite name=\"b\"/>");
        KotlinTestContexts.write(root, "target/surefire-reports/notes.txt", "not a report");

        var info = KotlinProjectDetector.inspect(dev.codewitness.core.model.ProjectRef.of(root));

        assertEquals(2, KotlinTestReportParser.findReportFiles(info).size(),
                "only TEST-*.xml files are reports");

        KotlinTestReportParser.clearReports(info);

        assertFalse(java.nio.file.Files.exists(surefire), "a stale report must not survive into a run");
    }
}
