package dev.codewitness.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A test result must not hide the reason it is not the whole story.
 *
 * <p>The counters alone are what {@code describe()} reports, and they are what other tests pin. The
 * note is where a runner says that what it parsed is not everything - a package that failed to build,
 * a target whose cases could not be paired with its output. {@code describeWithNote()} is what the
 * evidence record and the report use, so an incomplete run cannot read as a clean one.
 */
class TestResultTest {

    @Test
    @DisplayName("describeWithNote keeps the note; describe stays a stable summary of the counters")
    void noteIsAvailableWhereItIsRead() {
        TestResult result = TestResult.from(
                List.of(new TestCaseResult("com.example.A", "works", TestOutcome.PASSED, Duration.ofMillis(2),
                        null, null, null)),
                Duration.ofMillis(2), List.of(), "go test could not build or run ./broken");

        assertEquals("1 executed, 1 passed, 0 failed, 0 skipped", result.describe());
        assertEquals("1 executed, 1 passed, 0 failed, 0 skipped (go test could not build or run ./broken)",
                result.describeWithNote(),
                "an incomplete run must not read as a clean one where a human looks at it");
    }

    @Test
    @DisplayName("without a note the two descriptions are the same")
    void noNoteMeansNoSuffix() {
        TestResult result = TestResult.from(
                List.of(new TestCaseResult("com.example.A", "works", TestOutcome.PASSED, Duration.ofMillis(2),
                        null, null, null)),
                Duration.ofMillis(2), List.of(), null);

        assertEquals("1 executed, 1 passed, 0 failed, 0 skipped", result.describe());
        assertEquals(result.describe(), result.describeWithNote());
    }

    @Test
    @DisplayName("a result that never executed says so instead of reporting zero failures")
    void notExecutedIsExplicit() {
        TestResult result = TestResult.notExecuted("no test source directory exists");

        assertTrue(result.describe().contains("not executed"), result.describe());
        assertTrue(result.describe().contains("no test source directory exists"), result.describe());
        assertEquals(result.describe(), result.describeWithNote(),
                "the reason is already in the description; it is not repeated");
    }
}
