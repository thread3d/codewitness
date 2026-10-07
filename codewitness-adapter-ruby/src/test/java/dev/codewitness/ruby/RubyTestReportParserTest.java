package dev.codewitness.ruby;

import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.model.TestResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing of the JSON RSpec writes with {@code --format json}.
 *
 * <p>Nothing here needs Ruby or RSpec: RSpec's report is JSON, the parser reads it from text or from a
 * file, and every fact the engine depends on - the suite a result is attributed through, the outcome, the
 * failure text that carries a generated test's counterexample - is asserted against a synthetic report.
 * What the tests are really about is that a result is never invented: an unknown status is an error, a
 * report that could not be read is an error case, and an empty report is "not executed" rather than a pass.
 */
class RubyTestReportParserTest {

    @Test
    @DisplayName("a realistic RSpec report parses its examples, derives the suite from file_path and keeps the failure text")
    void parsesRSpecJsonReport(@TempDir Path root) throws IOException {
        RubyTestContexts.write(root, "spec/ledger_spec.rb", "# the suite under test\n");
        String counterexample = "CODEWITNESS_COUNTEREXAMPLE|case=3|seed=42|inputs={amount=1400}"
                + "|expected=at most 1000|actual=1400|invariant=amount <= 1000";
        Path report = RubyTestContexts.write(root, "rspec.json", """
                {"version":"3.13.6","examples":[
                  {"id":"./spec/ledger_spec.rb[1:1]","description":"totals","full_description":"Ledger totals","status":"passed","file_path":"./spec/ledger_spec.rb","line_number":12,"run_time":0.01,"exception":null},
                  {"id":"./spec/ledger_spec.rb[1:2]","description":"rejects a large withdrawal","full_description":"Ledger rejects a large withdrawal","status":"failed","file_path":"./spec/ledger_spec.rb","line_number":20,"run_time":0.5,"exception":{"class":"RSpec::Expectations::ExpectationNotMetError","message":"%s","backtrace":["/project/spec/ledger_spec.rb:21:in `block (2 levels) in <top (required)>'","/project/spec/ledger_spec.rb:7:in `each'"]}},
                  {"id":"./spec/ledger_spec.rb[1:3]","description":"rounding","full_description":"Ledger rounding","status":"pending","file_path":"./spec/ledger_spec.rb","line_number":30,"run_time":0.0,"exception":null}
                ],"summary":{"duration":1.5,"example_count":3,"failure_count":1,"pending_count":1,"errors_outside_of_examples_count":0},"summary_line":"3 examples, 1 failure, 1 pending"}
                """.formatted(counterexample));

        TestResult result = RubyTestReportParser.parse(List.of(report), root, "test");

        assertTrue(result.executed(), result.note());
        assertEquals(3, result.total());
        assertEquals(1, result.passed(), "example_count minus failures minus pending");
        assertEquals(1, result.failed());
        assertEquals(0, result.errors());
        assertEquals(1, result.skipped());
        assertEquals(Duration.ofMillis(1500), result.duration(), "the total is RSpec's own summary.duration");
        assertEquals(List.of(report), result.reports());

        TestCaseResult pass = result.cases().get(0);
        assertEquals("spec.ledger_spec", pass.suite(),
                "the suite is derived from file_path - the leading ./ dropped, the separators dotted and the "
                        + ".rb extension removed - because that is the identifier RequirementCodeMapper builds "
                        + "for the same file; RSpec's own example id, ./spec/ledger_spec.rb[1:1], would match "
                        + "nothing the engine looks up");
        assertEquals("Ledger totals", pass.name(), "the full description is what --example matches for replay");
        assertEquals(TestOutcome.PASSED, pass.outcome());

        TestCaseResult failure = result.cases().get(1);
        assertEquals(TestOutcome.FAILED, failure.outcome());
        assertEquals("RSpec::Expectations::ExpectationNotMetError", failure.failureType());
        String backtrace = "/project/spec/ledger_spec.rb:21:in `block (2 levels) in <top (required)>'"
                + System.lineSeparator() + "/project/spec/ledger_spec.rb:7:in `each'";
        assertEquals(counterexample + System.lineSeparator() + backtrace, failure.failureMessage(),
                "the message comes first, because that is where a generated test writes its counterexample "
                        + "line, and the backtrace follows it");
        assertTrue(failure.failureMessage().contains("CODEWITNESS_COUNTEREXAMPLE"),
                "without the message the engine's counterexample would say only 'test failed'");
        assertEquals(backtrace, failure.stackTrace());

        assertEquals(TestOutcome.SKIPPED, result.cases().get(2).outcome(),
                "RSpec's pending maps to the model's skipped, not to a pass");
    }

    @Test
    @DisplayName("the totals come from RSpec's summary, and the errors outside the examples are additional to them")
    void totalsComeFromTheSummary(@TempDir Path root) {
        TestResult result = RubyTestReportParser.parse("""
                {"examples":[
                  {"description":"a","full_description":"a","status":"passed","file_path":"spec/a_spec.rb","run_time":0.1},
                  {"description":"b","full_description":"b","status":"passed","file_path":"spec/a_spec.rb","run_time":0.2}
                ],"summary":{"duration":0.3,"example_count":2,"failure_count":0,"pending_count":0,"errors_outside_of_examples_count":3}}
                """, root, "test");

        assertTrue(result.executed(), result.note());
        assertEquals(2, result.total(), "example_count counts the examples, not the errors outside them");
        assertEquals(2, result.passed());
        assertEquals(0, result.failed());
        assertEquals(3, result.errors(),
                "errors_outside_of_examples_count is carried as the error count and is not subtracted from the "
                        + "example total, because those errors are additional to the examples rather than "
                        + "among them");
        assertEquals(0, result.skipped());
    }

    @Test
    @DisplayName("a status the parser does not know is an error, never an assumed pass")
    void unknownStatusIsAnError(@TempDir Path root) {
        TestResult result = RubyTestReportParser.parse("""
                {"examples":[
                  {"description":"warned","status":"warning","file_path":"spec/a_spec.rb","run_time":0.01},
                  {"status":"no-such-status","file_path":"spec/a_spec.rb","run_time":0.01}
                ]}
                """, root, "test");

        assertTrue(result.executed(), result.note());
        assertEquals(2, result.total());
        assertEquals(0, result.passed(),
                "an example whose outcome this parser does not understand did not demonstrably pass");
        assertEquals(2, result.errors());
        assertEquals(TestOutcome.ERROR, result.cases().get(0).outcome());
        assertEquals("warned", result.cases().get(0).name(),
                "a report with no summary falls back to counting the cases");
        assertEquals(TestOutcome.ERROR, result.cases().get(1).outcome());
        assertEquals("unknown", result.cases().get(1).name(),
                "an example that names itself nowhere still has to be reported");
    }

    @Test
    @DisplayName("an absolute file_path outside the project names no requirement, so its suite is unknown")
    void anAbsolutePathOutsideTheProjectIsUnknown(@TempDir Path root) {
        TestResult result = RubyTestReportParser.parse("""
                {"examples":[
                  {"description":"elsewhere","full_description":"elsewhere","status":"passed","file_path":"/elsewhere/spec/a_spec.rb","run_time":0.01},
                  {"description":"here","full_description":"here","status":"passed","file_path":"%s","run_time":0.01}
                ]}
                """.formatted(root.resolve("spec/a_spec.rb")), root, "test");

        assertEquals("unknown", result.cases().get(0).suite(),
                "a dotted absolute path is not a name this project's tests can be attributed through");
        assertEquals("spec.a_spec", result.cases().get(1).suite(),
                "an absolute path below the root is made relative before it is turned into a suite");
    }

    @Test
    @DisplayName("null, blank or example-less input is not executed, never a pass")
    void absentInputIsNotExecuted(@TempDir Path root) {
        TestResult nullInput = RubyTestReportParser.parse((String) null, root, "test");
        TestResult blankInput = RubyTestReportParser.parse("   ", root, "test");
        TestResult noReports = RubyTestReportParser.parse(List.<Path>of(), root, "test");
        TestResult noExamples = RubyTestReportParser.parse("{\"examples\":[]}", root, "test");

        for (TestResult result : List.of(nullInput, blankInput, noReports, noExamples)) {
            assertFalse(result.executed(), result.note());
            assertEquals(0, result.total(), result.note());
            assertEquals(List.of(), result.cases(), result.note());
        }
        assertTrue(nullInput.note().contains("could not be read"), nullInput.note());
        assertTrue(blankInput.note().contains("could not be read"), blankInput.note());
        assertTrue(noReports.note().contains("no examples"), noReports.note());
        assertTrue(noExamples.note().contains("no examples"), noExamples.note());
    }

    @Test
    @DisplayName("a malformed report becomes an error case that says it could not be read, never an exception")
    void malformedReportBecomesAnError(@TempDir Path root) throws IOException {
        Path report = RubyTestContexts.write(root, "rspec.json", "{\"examples\":[");

        TestResult result = RubyTestReportParser.parse(List.of(report), root, "test");

        assertTrue(result.executed(), "the report was read, it just could not be understood");
        assertEquals(1, result.total());
        assertEquals(1, result.errors());
        TestCaseResult failure = result.cases().getFirst();
        assertEquals("report-parse-failure", failure.name());
        assertEquals(TestOutcome.ERROR, failure.outcome());
        assertEquals("rspec.json", failure.suite(),
                "the file's own name, so a reader can tell which report could not be read");
        assertNotNull(failure.failureMessage());

        TestResult fromText = RubyTestReportParser.parse("{not json", root, "test");
        assertTrue(fromText.executed(), "malformed input produces an observation, never an exception");
        assertEquals(1, fromText.errors());
        assertEquals("report-parse-failure", fromText.cases().getFirst().name());
    }

    @Test
    @DisplayName("deleteIfExists removes a stale report and tolerates one that is not there")
    void deletesAStaleReport(@TempDir Path root) throws IOException {
        Path report = RubyTestContexts.write(root, "rspec.json", "{\"examples\":[]}");

        RubyTestReportParser.deleteIfExists(report);

        assertFalse(Files.exists(report),
                "a stale report left behind would be read as this run's results");
        // A second call must be a no-op rather than an IOException: the parser deletes before a run, and
        // most runs have no report to delete.
        RubyTestReportParser.deleteIfExists(report);
    }
}
