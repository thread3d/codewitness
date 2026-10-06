package dev.codewitness.core.model;

import java.time.Duration;
import java.util.Objects;

/**
 * A single executed test case.
 *
 * @param suite          fully qualified test class name
 * @param name           test method or display name
 * @param outcome        outcome
 * @param duration       execution time
 * @param failureType    exception type of the failure, or {@code null}
 * @param failureMessage failure message, or {@code null}
 * @param stackTrace     full stack trace, or {@code null}
 */
public record TestCaseResult(
        String suite,
        String name,
        TestOutcome outcome,
        Duration duration,
        String failureType,
        String failureMessage,
        String stackTrace) {

    public TestCaseResult {
        Objects.requireNonNull(suite, "suite");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(outcome, "outcome");
        duration = duration == null ? Duration.ZERO : duration;
    }

    public String displayName() {
        return suite + "#" + name;
    }

    public static TestCaseResult passed(String suite, String name, Duration duration) {
        return new TestCaseResult(suite, name, TestOutcome.PASSED, duration, null, null, null);
    }

    public static TestCaseResult failed(
            String suite, String name, Duration duration, String type, String message, String stackTrace) {
        return new TestCaseResult(suite, name, TestOutcome.FAILED, duration, type, message, stackTrace);
    }
}
