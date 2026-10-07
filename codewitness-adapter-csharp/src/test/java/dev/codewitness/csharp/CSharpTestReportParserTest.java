package dev.codewitness.csharp;

import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.model.TestResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the TRX report parser, which is where every .NET test observation comes from. */
class CSharpTestReportParserTest {

    /** A TRX report shaped exactly as the SDK writes one, including the byte order mark it starts with. */
    private static String report(String results) {
        return "\uFEFF<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<TestRun id=\"1\" name=\"run\" xmlns=\"http://microsoft.com/schemas/VisualStudio/TeamTest/2010\">\n"
                + "  <Results>\n" + results + "  </Results>\n"
                + "  <TestDefinitions>\n"
                + "    <UnitTest name=\"Ledger.Tests.LedgerTests.Fails\" id=\"a\" storage=\"Ledger.Tests.dll\">\n"
                + "      <TestMethod className=\"Ledger.Tests.LedgerTests\" name=\"Fails\" />\n"
                + "    </UnitTest>\n"
                + "    <UnitTest name=\"Ledger.Tests.LedgerTests.Passes\" id=\"b\" storage=\"Ledger.Tests.dll\">\n"
                + "      <TestMethod className=\"Ledger.Tests.LedgerTests\" name=\"Passes\" />\n"
                + "    </UnitTest>\n"
                + "    <UnitTest name=\"Ledger.Tests.LedgerTests.NotRun\" id=\"c\" storage=\"Ledger.Tests.dll\">\n"
                + "      <TestMethod className=\"Ledger.Tests.LedgerTests\" name=\"NotRun\" />\n"
                + "    </UnitTest>\n"
                + "  </TestDefinitions>\n"
                + "</TestRun>\n";
    }

    @Test
    @DisplayName("each result is attributed to the class its definition names, with its outcome and message")
    void parsesResults(@TempDir Path directory) throws IOException {
        String trx = report("""
                    <UnitTestResult testId="a" testName="Ledger.Tests.LedgerTests.Fails" outcome="Failed" duration="00:00:01.5000000">
                      <Output>
                        <ErrorInfo>
                          <Message>CODEWITNESS_COUNTEREXAMPLE|case=3|seed=42|inputs={t=1}</Message>
                          <StackTrace>   at Ledger.Tests.LedgerTests.Fails()</StackTrace>
                        </ErrorInfo>
                      </Output>
                    </UnitTestResult>
                    <UnitTestResult testId="b" testName="Ledger.Tests.LedgerTests.Passes" outcome="Passed" duration="00:00:00.0100000" />
                    <UnitTestResult testId="c" testName="Ledger.Tests.LedgerTests.NotRun" outcome="NotExecuted" duration="00:00:00.0000000" />
                """);
        Path file = CSharpTestContexts.write(directory, "results.trx", trx);

        TestResult result = CSharpTestReportParser.parse(file, "parsed from the TRX report");

        assertTrue(result.executed());
        assertEquals(3, result.total());
        assertEquals(1, result.passed());
        assertEquals(1, result.failed());
        assertEquals(1, result.skipped());
        assertEquals(Duration.ofMillis(1510), result.duration());

        TestCaseResult failing = result.cases().stream().filter(c -> c.outcome() == TestOutcome.FAILED)
                .findFirst().orElseThrow();
        assertEquals("Ledger.Tests.LedgerTests", failing.suite(),
                "the class comes from the definition, which is what the requirement mapper also derives");
        assertEquals("Fails", failing.name());
        assertNotNull(failing.failureMessage());
        assertTrue(failing.failureMessage().contains("CODEWITNESS_COUNTEREXAMPLE"),
                "the assertion message reaches the counterexample verbatim");
        assertNotNull(failing.stackTrace());
        assertEquals("test failure", failing.failureType(),
                "a counterexample thrown by a generated test is a test failure the engine decodes itself");
    }

    @Test
    @DisplayName("a failure the test framework classified as an assertion is named as one")
    void classifiesAssertionFailures(@TempDir Path directory) throws IOException {
        Path file = CSharpTestContexts.write(directory, "results.trx", report("""
                    <UnitTestResult testId="a" testName="Ledger.Tests.LedgerTests.Fails" outcome="Failed">
                      <Output>
                        <ErrorInfo>
                          <Message>Assert.Equal() Failure: Values differ</Message>
                        </ErrorInfo>
                      </Output>
                    </UnitTestResult>
                """));

        TestResult result = CSharpTestReportParser.parse(file, "note");
        assertEquals("assertion failure", result.cases().getFirst().failureType());
    }

    @Test
    @DisplayName("a report with no case is not a pass, and neither is an unreadable report")
    void reportsNothingExecuted(@TempDir Path directory) throws IOException {
        Path empty = CSharpTestContexts.write(directory, "empty.trx", report(""));
        TestResult result = CSharpTestReportParser.parse(empty, "note");
        assertFalse(result.executed());
        assertTrue(result.note().contains("no test case"));

        Path broken = CSharpTestContexts.write(directory, "broken.trx", "this is not XML");
        TestResult unreadable = CSharpTestReportParser.parse(broken, "note");
        assertFalse(unreadable.executed());
        assertTrue(unreadable.note().contains("could not be read"));
    }

    @Test
    @DisplayName("a result with no definition falls back to its display name rather than going unattributed")
    void fallsBackToDisplayName(@TempDir Path directory) throws IOException {
        Path file = CSharpTestContexts.write(directory, "results.trx", report("""
                    <UnitTestResult testId="unknown" testName="Other.Namespace.OtherTests.Case" outcome="Failed" />
                """));

        TestResult result = CSharpTestReportParser.parse(file, "note");
        TestCaseResult testCase = result.cases().getFirst();
        assertEquals("Other.Namespace.OtherTests", testCase.suite());
        assertEquals("Case", testCase.name());
        assertTrue(testCase.failureMessage().contains("no failure message"),
                "a failure with no message still says something rather than nothing");
    }

    @Test
    @DisplayName("outcomes that are neither a pass nor a failure are recorded as skipped")
    void mapsOutcomes() {
        assertEquals(TestOutcome.PASSED, CSharpTestReportParser.outcome("Passed"));
        assertEquals(TestOutcome.FAILED, CSharpTestReportParser.outcome("Failed"));
        assertEquals(TestOutcome.ERROR, CSharpTestReportParser.outcome("Error"));
        assertEquals(TestOutcome.ERROR, CSharpTestReportParser.outcome("Timeout"));
        assertEquals(TestOutcome.SKIPPED, CSharpTestReportParser.outcome("NotExecuted"));
        assertEquals(TestOutcome.SKIPPED, CSharpTestReportParser.outcome("Inconclusive"));
        assertEquals(TestOutcome.SKIPPED, CSharpTestReportParser.outcome(null),
                "an outcome the report did not state is not a pass");
    }

    @Test
    @DisplayName("a TRX timespan is read to the millisecond, and a malformed one is zero")
    void parsesTimeSpans() {
        assertEquals(Duration.ofMillis(1500), CSharpTestReportParser.timeSpan("00:00:01.5000000"));
        assertEquals(Duration.ofMillis(3_661_000), CSharpTestReportParser.timeSpan("01:01:01"));
        assertEquals(Duration.ZERO, CSharpTestReportParser.timeSpan("not-a-time"));
        assertEquals(Duration.ZERO, CSharpTestReportParser.timeSpan(null));
    }
}
