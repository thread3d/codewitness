package dev.codewitness.php;

import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.model.TestResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing of the JUnit XML PHPUnit writes with {@code --log-junit}. */
class PhpTestReportParserTest {

    @Test
    @DisplayName("a realistic PHPUnit report parses its nested suites, normalises the class name and keeps the failure text")
    void parsesNestedPhpunitSuites(@TempDir Path root) throws IOException {
        PhpTestContexts.write(root, "tests/WithdrawalLedgerTest.php", "<?php\n");
        String counterexample = "Failed asserting that 1400 is equal to 1000.\n"
                + "CODEWITNESS_COUNTEREXAMPLE|case=3|seed=42|inputs={amount=1400}|expected=at most 1000"
                + "|actual=1400|invariant=a &lt;= 1000";
        Path report = PhpTestContexts.write(root, "junit.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuites>
                  <testsuite name="/project/phpunit.xml" tests="4" assertions="4" errors="1" failures="1" skipped="1" time="0.875">
                    <testsuite name="withdrawal-ledger" tests="4" assertions="4" errors="1" failures="1" skipped="1" time="0.875">
                      <testsuite name="CodeWitness\\Fixture\\Tests\\WithdrawalLedgerTest" file="/project/tests/WithdrawalLedgerTest.php" tests="4" assertions="4" errors="1" failures="1" skipped="1" time="0.875">
                        <testcase name="testRejectsNegativeAmounts" file="/project/tests/WithdrawalLedgerTest.php" line="19" class="CodeWitness\\Fixture\\Tests\\WithdrawalLedgerTest" classname="CodeWitness.Fixture.Tests.WithdrawalLedgerTest" assertions="1" time="0.25">
                          <failure type="PHPUnit\\Framework\\ExpectationFailedException">%s</failure>
                        </testcase>
                        <testcase name="testAcceptsWithdrawal" file="/project/tests/WithdrawalLedgerTest.php" line="12" class="CodeWitness\\Fixture\\Tests\\WithdrawalLedgerTest" classname="CodeWitness.Fixture.Tests.WithdrawalLedgerTest" assertions="1" time="0.5"/>
                        <testcase name="testAppliesFee" file="/project/tests/WithdrawalLedgerTest.php" line="24" class="CodeWitness\\Fixture\\Tests\\WithdrawalLedgerTest" classname="CodeWitness.Fixture.Tests.WithdrawalLedgerTest" assertions="0" time="0.125">
                          <error type="RuntimeException">no ledger</error>
                        </testcase>
                        <testcase name="testRounding" file="/project/tests/WithdrawalLedgerTest.php" line="30" class="CodeWitness\\Fixture\\Tests\\WithdrawalLedgerTest" classname="CodeWitness.Fixture.Tests.WithdrawalLedgerTest" assertions="0" time="0">
                          <skipped message="not applicable"/>
                        </testcase>
                      </testsuite>
                    </testsuite>
                  </testsuite>
                </testsuites>
                """.formatted(counterexample));

        TestResult result = PhpTestReportParser.parse(List.of(report), root, "test");

        assertTrue(result.executed());
        assertEquals(4, result.total());
        assertEquals(1, result.passed());
        assertEquals(1, result.failed());
        assertEquals(1, result.errors());
        assertEquals(1, result.skipped());

        TestCaseResult failure = failed(result).getFirst();
        assertEquals("CodeWitness.Fixture.Tests.WithdrawalLedgerTest", failure.suite(),
                "the dotted class name is what the engine's requirement mapping derives for a PHP test file");
        assertEquals("testRejectsNegativeAmounts", failure.name());
        assertEquals(TestOutcome.FAILED, failure.outcome());
        assertTrue(failure.failureMessage().contains("CODEWITNESS_COUNTEREXAMPLE"),
                "PHPUnit writes the failure as element text, so the message falls back to it; without that "
                        + "the engine's counterexample would say only 'test failed'");
        assertTrue(failure.stackTrace().contains("CODEWITNESS_COUNTEREXAMPLE"));

        // The aggregate is the sum of the test case times: summing the nested suite times would count a
        // case once per enclosing suite.
        assertEquals(Duration.ofMillis(875), result.duration());
    }

    @Test
    @DisplayName("a report that does carry a message attribute has it used verbatim, ahead of the element text")
    void theMessageAttributeWinsWhenPresent(@TempDir Path root) throws IOException {
        Path report = PhpTestContexts.write(root, "junit.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuites>
                  <testsuite name="withdrawal-ledger" tests="1">
                    <testcase name="testFoo" classname="App.Tests.LedgerTest" time="0.01">
                      <failure message="Failed asserting that 1400 is equal to 1000." type="ExpectationFailedException">the trace</failure>
                    </testcase>
                  </testsuite>
                </testsuites>
                """);

        TestResult result = PhpTestReportParser.parse(List.of(report), root, "test");

        assertEquals("Failed asserting that 1400 is equal to 1000.", result.cases().getFirst().failureMessage());
        assertEquals("the trace", result.cases().getFirst().stackTrace());
    }

    @Test
    @DisplayName("a blank classname falls back to class, and both are normalised to dots")
    void fallsBackToTheClassAttribute(@TempDir Path root) throws IOException {
        Path report = PhpTestContexts.write(root, "junit.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuites>
                  <testsuite name="withdrawal-ledger" tests="1">
                    <testcase name="testFoo" class="App\\Tests\\LedgerTest" classname="" time="0.01"/>
                  </testsuite>
                </testsuites>
                """);

        TestResult result = PhpTestReportParser.parse(List.of(report), root, "test");

        TestCaseResult testCase = result.cases().getFirst();
        assertEquals("App.Tests.LedgerTest", testCase.suite(),
                "a blank classname must fall back to the class attribute, not produce an empty suite");
        assertEquals("testFoo", testCase.name());
        assertEquals(TestOutcome.PASSED, testCase.outcome());
    }

    @Test
    @DisplayName("with neither class attribute the test file's path below the project root names the suite")
    void derivesTheSuiteFromTheFilePath(@TempDir Path root) throws IOException {
        PhpTestContexts.write(root, "tests/LedgerTest.php", "<?php\n");
        Path report = PhpTestContexts.write(root, "junit.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuites>
                  <testsuite name="withdrawal-ledger" tests="1">
                    <testcase name="testFoo" file="%s" time="0.01"/>
                  </testsuite>
                </testsuites>
                """.formatted(root.resolve("tests/LedgerTest.php")));

        TestResult result = PhpTestReportParser.parse(List.of(report), root, "test");

        assertEquals("tests.LedgerTest", result.cases().getFirst().suite());
    }

    @Test
    @DisplayName("an outcome element the parser does not know is skipped, never assumed to have passed")
    void unknownOutcomeElementsAreNotPasses(@TempDir Path root) throws IOException {
        Path report = PhpTestContexts.write(root, "junit.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuites>
                  <testsuite name="suite" tests="2">
                    <testcase name="testWarned" classname="App.Tests.LedgerTest" time="0.01">
                      <warning type="PHPUnit\\Framework\\Warning">a deprecation was triggered</warning>
                    </testcase>
                    <testcase name="testWithOutput" classname="App.Tests.LedgerTest" time="0.01">
                      <system-out>some debug output</system-out>
                    </testcase>
                  </testsuite>
                </testsuites>
                """);

        TestResult result = PhpTestReportParser.parse(List.of(report), root, "test");

        assertEquals(TestOutcome.SKIPPED, result.cases().get(0).outcome(),
                "a case whose outcome this parser does not understand did not demonstrably run");
        assertEquals(TestOutcome.PASSED, result.cases().get(1).outcome(),
                "captured output is not an outcome, so a case that only printed is still a pass");
    }

    @Test
    @DisplayName("a malformed report becomes an error case, so it can never be a silent skip")
    void malformedReportBecomesAnError(@TempDir Path root) throws IOException {
        Path report = PhpTestContexts.write(root, "junit.xml",
                "<?xml version=\"1.0\"?>\n<testsuites><testsuite><testcase name=\"testFoo\"");

        TestResult result = PhpTestReportParser.parse(List.of(report), root, "test");

        assertTrue(result.executed(), "the report was read, it just could not be understood");
        assertEquals(1, result.errors());
        assertEquals("report-parse-failure", result.cases().getFirst().name());
        assertEquals(TestOutcome.ERROR, result.cases().getFirst().outcome());
    }

    @Test
    @DisplayName("a report with no test cases is reported as not executed, never as a pass")
    void emptyReportIsNotExecuted(@TempDir Path root) throws IOException {
        Path report = PhpTestContexts.write(root, "junit.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuites>
                  <testsuite name="withdrawal-ledger" tests="0" failures="0" errors="0" skipped="0" time="0"/>
                </testsuites>
                """);

        TestResult result = PhpTestReportParser.parse(List.of(report), root, "test");

        assertFalse(result.executed());
        assertTrue(result.note().contains("no test cases"), result.note());
    }

    private static List<TestCaseResult> failed(TestResult result) {
        return result.cases().stream().filter(c -> c.outcome() == TestOutcome.FAILED).toList();
    }
}
