package dev.codewitness.core.model;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Aggregated result of running a project's test suite.
 *
 * @param executed  {@code false} when tests could not be executed at all (for example a failed build)
 * @param note      explanation when tests were not executed, or extra context
 * @param cases     individual test case results
 * @param reports   raw report files the results were parsed from
 */
public record TestResult(
        boolean executed,
        String note,
        int total,
        int passed,
        int failed,
        int errors,
        int skipped,
        Duration duration,
        List<TestCaseResult> cases,
        List<Path> reports) {

    public TestResult {
        cases = List.copyOf(cases == null ? List.of() : cases);
        reports = List.copyOf(reports == null ? List.of() : reports);
        duration = duration == null ? Duration.ZERO : duration;
    }

    public static TestResult notExecuted(String note) {
        return new TestResult(false, note, 0, 0, 0, 0, 0, Duration.ZERO, List.of(), List.of());
    }

    /** Builds an aggregate from individual cases, computing all counters. */
    public static TestResult from(List<TestCaseResult> cases, Duration duration, List<Path> reports, String note) {
        int passed = 0;
        int failed = 0;
        int errors = 0;
        int skipped = 0;
        for (TestCaseResult c : cases) {
            switch (c.outcome()) {
                case PASSED -> passed++;
                case FAILED -> failed++;
                case ERROR -> errors++;
                case SKIPPED -> skipped++;
            }
        }
        return new TestResult(true, note, cases.size(), passed, failed, errors, skipped,
                duration, cases, reports);
    }

    public boolean anyFailure() {
        return failed > 0 || errors > 0;
    }

    public List<TestCaseResult> failures() {
        List<TestCaseResult> result = new ArrayList<>();
        for (TestCaseResult c : cases) {
            if (c.outcome().isFailure()) {
                result.add(c);
            }
        }
        return List.copyOf(result);
    }

    public String describe() {
        if (!executed) {
            return "tests not executed (" + note + ")";
        }
        return total + " executed, " + passed + " passed, " + failures().size() + " failed, " + skipped + " skipped";
    }
}
