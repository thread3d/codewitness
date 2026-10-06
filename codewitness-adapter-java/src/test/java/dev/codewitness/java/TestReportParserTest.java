package dev.codewitness.java;

import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ModuleInfo;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.model.TestResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static dev.codewitness.java.TestContexts.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Surefire, Failsafe and Gradle all write the same JUnit XML, so one parser is tested against all
 * three shapes. The reports are parsed rather than scraped so that the recorded result is the
 * tool's own output, never an interpretation of its log.
 */
class TestReportParserTest {

    @TempDir
    Path tmp;

    private static final String SUREFIRE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.example.CalcTest" tests="4" skipped="1" failures="1" errors="1" time="0.25">
              <testcase name="adds" classname="com.example.CalcTest" time="0.01"/>
              <testcase name="subtracts" classname="com.example.CalcTest" time="0.02">
                <failure message="expected 3 but was 4" type="java.lang.AssertionError">java.lang.AssertionError: expected 3 but was 4
                	at com.example.CalcTest.subtracts(CalcTest.java:20)</failure>
              </testcase>
              <testcase name="divides" classname="com.example.CalcTest" time="0.03">
                <error message="boom" type="java.lang.IllegalStateException">java.lang.IllegalStateException: boom
                	at com.example.CalcTest.divides(CalcTest.java:30)</error>
              </testcase>
              <testcase name="ignored" classname="com.example.CalcTest" time="0.0">
                <skipped message="disabled for now"/>
              </testcase>
            </testsuite>
            """;

    @Test
    void parsesTotalsAndOutcomesFromASurefireReport() throws IOException {
        Path root = tmp.resolve("surefire");
        write(root, "target/surefire-reports/TEST-com.example.CalcTest.xml", SUREFIRE);
        ProjectInfo project = project(root);

        List<Path> reports = TestReportParser.findReportFiles(project);
        assertEquals(1, reports.size(), "the surefire report directory must be searched: " + reports);

        TestResult result = TestReportParser.parse(reports, "surefire");

        assertTrue(result.executed());
        assertEquals(4, result.total());
        assertEquals(1, result.passed());
        assertEquals(1, result.failed());
        assertEquals(1, result.errors());
        assertEquals(1, result.skipped());
        assertTrue(result.anyFailure());
        assertEquals(2, result.failures().size(), "a failure and an error are both failures");
        assertEquals(List.of(reports.get(0)), result.reports());
        assertEquals("surefire", result.note());
        assertEquals("4 executed, 1 passed, 2 failed, 1 skipped", result.describe());
    }

    @Test
    void accumulatesTheSuiteDurationAndEachCaseDuration() throws IOException {
        Path root = tmp.resolve("durations");
        write(root, "target/surefire-reports/TEST-com.example.CalcTest.xml", SUREFIRE);

        TestResult result = TestReportParser.parse(TestReportParser.findReportFiles(project(root)), "surefire");

        assertEquals(Duration.ofMillis(250), result.duration(),
                "the suite time attribute is the aggregate wall clock time");
        TestCaseResult passed = result.cases().get(0);
        assertEquals(Duration.ofMillis(10), passed.duration());
        assertEquals(Duration.ofMillis(20), result.cases().get(1).duration());
    }

    @Test
    void capturesFailureTypeMessageAndStackTrace() throws IOException {
        Path root = tmp.resolve("failures");
        write(root, "target/surefire-reports/TEST-com.example.CalcTest.xml", SUREFIRE);

        TestResult result = TestReportParser.parse(TestReportParser.findReportFiles(project(root)), "surefire");

        TestCaseResult failure = result.cases().get(1);
        assertEquals("com.example.CalcTest", failure.suite());
        assertEquals("subtracts", failure.name());
        assertEquals(TestOutcome.FAILED, failure.outcome());
        assertEquals("java.lang.AssertionError", failure.failureType());
        assertEquals("expected 3 but was 4", failure.failureMessage());
        assertNotNull(failure.stackTrace());
        assertTrue(failure.stackTrace().contains("CalcTest.java:20"), failure.stackTrace());

        TestCaseResult error = result.cases().get(2);
        assertEquals(TestOutcome.ERROR, error.outcome());
        assertEquals("java.lang.IllegalStateException", error.failureType());
        assertEquals("boom", error.failureMessage());
        assertTrue(error.stackTrace().contains("CalcTest.java:30"));

        TestCaseResult skipped = result.cases().get(3);
        assertEquals(TestOutcome.SKIPPED, skipped.outcome());
        assertEquals("disabled for now", skipped.failureMessage());
        assertNull(skipped.stackTrace());
    }

    @Test
    void aMalformedReportBecomesAnErrorCaseWithoutLosingTheOtherResults() throws IOException {
        Path root = tmp.resolve("malformed");
        write(root, "target/surefire-reports/TEST-good.Test.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="good.Test" tests="1" time="0.1">
                  <testcase name="works" classname="good.Test" time="0.1"/>
                </testsuite>
                """);
        Path bad = write(root, "target/surefire-reports/TEST-bad.Test.xml",
                "<?xml version=\"1.0\"?>\n<testsuite name=\"bad.Test\"><testcase");

        TestResult result = TestReportParser.parse(
                TestReportParser.findReportFiles(project(root)), "surefire");

        assertEquals(2, result.total(), "one real case plus one error case");
        assertEquals(1, result.passed());
        assertEquals(1, result.errors());
        TestCaseResult errorCase = result.cases().stream()
                .filter(c -> c.outcome() == TestOutcome.ERROR)
                .findFirst()
                .orElseThrow();
        assertEquals("TEST-bad.Test.xml", errorCase.suite());
        assertEquals("report-parse-failure", errorCase.name());
        assertNotNull(errorCase.failureType());
        assertEquals(bad.getFileName().toString(), errorCase.suite());
    }

    @Test
    void findsGradleJunitXmlReportsToo() throws IOException {
        Path root = tmp.resolve("gradle");
        write(root, "build/test-results/test/TEST-com.example.AppTest.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.AppTest" tests="1" time="0.5">
                  <testcase name="runs" classname="com.example.AppTest" time="0.5"/>
                </testsuite>
                """);
        write(root, "build/test-results/test/notes.xml", "<notes/>");
        write(root, "build/test-results/test/TEST-not-xml.txt", "ignore me");

        List<Path> reports = TestReportParser.findReportFiles(project(root));

        assertEquals(1, reports.size(), "only TEST-*.xml files are reports: " + reports);
        assertTrue(reports.get(0).getFileName().toString().startsWith("TEST-"));
        TestResult result = TestReportParser.parse(reports, "gradle");
        assertEquals(1, result.total());
        assertEquals(1, result.passed());
    }

    @Test
    void reportsThatContainNoTestCasesAreNotExecuted() throws IOException {
        Path root = tmp.resolve("empty-suite");
        write(root, "target/surefire-reports/TEST-empty.Test.xml",
                "<?xml version=\"1.0\"?>\n<testsuite name=\"empty.Test\" tests=\"0\" time=\"0.0\"/>");

        TestResult result = TestReportParser.parse(TestReportParser.findReportFiles(project(root)), "surefire");

        assertFalse(result.executed());
        assertEquals(0, result.total());
        assertTrue(result.note().contains("contained no test cases"), result.note());
    }

    @Test
    void anUnparsableDurationDoesNotDiscardTheCase() throws IOException {
        Path root = tmp.resolve("bad-time");
        write(root, "target/surefire-reports/TEST-odd.Test.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="odd.Test" tests="1" time="not-a-number">
                  <testcase name="runs" classname="odd.Test" time="also-not-a-number"/>
                </testsuite>
                """);

        TestResult result = TestReportParser.parse(TestReportParser.findReportFiles(project(root)), "surefire");

        assertEquals(1, result.total());
        assertEquals(Duration.ZERO, result.cases().get(0).duration());
        assertEquals(Duration.ZERO, result.duration());
    }

    @Test
    void clearReportsRemovesStaleReports() throws IOException {
        Path root = tmp.resolve("clear");
        Path report = write(root, "target/surefire-reports/TEST-old.Test.xml", SUREFIRE);
        ProjectInfo project = project(root);

        assertEquals(1, TestReportParser.findReportFiles(project).size());
        TestReportParser.clearReports(project);

        assertFalse(Files.exists(report), "stale reports must never be mistaken for fresh results");
        assertTrue(TestReportParser.findReportFiles(project).isEmpty());
    }

    private static ProjectInfo project(Path root) {
        return ProjectInfo.builder(ProjectRef.of(root))
                .language(LanguageId.JAVA)
                .buildSystem(BuildSystemId.MAVEN)
                .addModule(new ModuleInfo(root.getFileName().toString(), root, "jar",
                        List.of(), List.of(), "17"))
                .build();
    }
}
