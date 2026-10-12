package dev.codewitness.pascal;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** fpcunit's XML is read per test; a program without it is judged by its exit status. */
class PascalTestReportParserTest {

    private static final String XML = """
            <?xml version="1.0" encoding="utf-8"?>
            <TestResults>
              <TestListing>
                <TestSuite Name="" ElapsedTime="00:00:00.000" NumberOfRunTests="4">
                  <TestSuite Name="TWithdrawTest" ElapsedTime="00:00:00.010" NumberOfRunTests="4">
                    <Test Name="TestValid" Result="OK" ElapsedTime="00:00:00.001"/>
                    <Test Name="TestBoundary" Result="Failed" ElapsedTime="00:00:00.002">
                      <Message>TWithdrawTest.TestBoundary: expected: &lt;1&gt; but was: &lt;70&gt;</Message>
                      <ExceptionClass>EAssertionFailedError</ExceptionClass>
                      <ExceptionMessage>expected: &lt;1&gt; but was: &lt;70&gt;</ExceptionMessage>
                    </Test>
                    <Test Name="TestBoom" Result="Error" ElapsedTime="00:00:00.003">
                      <ExceptionClass>Exception</ExceptionClass>
                      <ExceptionMessage>boom &amp; gone</ExceptionMessage>
                    </Test>
                    <Test Name="TestLater" Result="Ignored" ElapsedTime="00:00:00.004"/>
                  </TestSuite>
                </TestSuite>
              </TestListing>
            </TestResults>
            """;

    private static ProcessResult result(int exitCode, String output, boolean timedOut) {
        return new ProcessResult(List.of("cw-test"), Path.of("."), true, exitCode, output, "",
                Duration.ofMillis(12), timedOut, null);
    }

    @Test
    @DisplayName("each fpcunit test becomes a case with its suite, outcome and message")
    void readsXmlPerTest() {
        List<TestCaseResult> cases = PascalTestReportParser.parseXml(XML, "fallback");

        assertEquals(4, cases.size());
        assertEquals("TWithdrawTest", cases.get(0).suite());
        assertEquals(TestOutcome.PASSED, cases.get(0).outcome());
        assertEquals(TestOutcome.FAILED, cases.get(1).outcome());
        assertEquals("expected: <1> but was: <70>", cases.get(1).failureMessage());
        assertEquals("EAssertionFailedError", cases.get(1).failureType());
        assertEquals(TestOutcome.ERROR, cases.get(2).outcome());
        assertEquals("boom & gone", cases.get(2).failureMessage());
        assertEquals(TestOutcome.SKIPPED, cases.get(3).outcome());
        assertEquals(Duration.ofMillis(2), cases.get(1).duration());
    }

    @Test
    @DisplayName("a suite the report does not nest falls back to the supplied name")
    void fallsBackToTheSuppliedSuite() {
        String xml = "<TestResults><Test Name=\"Only\" Result=\"OK\"/></TestResults>";

        List<TestCaseResult> cases = PascalTestReportParser.parseXml(xml, "ledgertests");

        assertEquals("ledgertests", cases.getFirst().suite());
    }

    @Test
    @DisplayName("a nonzero exit is a failure and a zero exit is a pass")
    void judgesByExitStatus() {
        assertEquals(TestOutcome.PASSED, PascalTestReportParser
                .fromExecution("suite", "run", result(0, "all good", false)).outcome());
        TestCaseResult failed = PascalTestReportParser.fromExecution("suite", "run", result(1, "not ok", false));
        assertEquals(TestOutcome.FAILED, failed.outcome());
        assertEquals("TestFailure", failed.failureType());
    }

    @Test
    @DisplayName("a generated test's counterexample line is carried into the failure message")
    void carriesCounterexamples() {
        String output = "unrelated\nCODEWITNESS_COUNTEREXAMPLE|case=3|seed=42|inputs={amount=10}|actual=1\n";

        TestCaseResult case1 = PascalTestReportParser.fromExecution("suite", "run", result(1, output, false));

        assertEquals(TestOutcome.FAILED, case1.outcome());
        assertEquals("Counterexample", case1.failureType());
        assertTrue(case1.failureMessage().contains("CODEWITNESS_COUNTEREXAMPLE"));
    }

    @Test
    @DisplayName("a timeout is an error, not a failed assertion")
    void timeoutIsAnError() {
        TestCaseResult case1 = PascalTestReportParser.fromExecution("suite", "run", result(-1, "", true));

        assertEquals(TestOutcome.ERROR, case1.outcome());
    }

    @Test
    @DisplayName("a test program that does not compile is an error and never a failure")
    void compileFailureIsAnError() {
        TestCaseResult case1 = PascalTestReportParser.compileFailure("suite", "suite", result(1, "Fatal: nope", false));

        assertEquals(TestOutcome.ERROR, case1.outcome());
        assertEquals("CompileError", case1.failureType());
    }

    @Test
    @DisplayName("a plain fpcunit summary is read when no XML was produced")
    void readsPlainSummary() {
        String output = "Time:00.000 N:2 E:0 F:1 I:0\n\nNumber of run tests: 2\n"
                + "Number of errors:    0\nNumber of failures:  1\n";

        Optional<TestCaseResult> summary = PascalTestReportParser.parsePlainSummary(output, "suite");

        assertTrue(summary.isPresent());
        assertEquals(TestOutcome.FAILED, summary.get().outcome());
        assertTrue(PascalTestReportParser.parsePlainSummary("nothing here", "suite").isEmpty());
    }

    @Test
    @DisplayName("a process that never started has no result to report")
    void notStartedIsAnError() {
        ProcessResult notStarted = ProcessResult.notStarted(List.of("fpc"), Path.of("."), "fpc: not found");

        TestCaseResult case1 = PascalTestReportParser.fromExecution("suite", "run", notStarted);

        assertEquals(TestOutcome.ERROR, case1.outcome());
        assertNull(case1.stackTrace());
    }
}
