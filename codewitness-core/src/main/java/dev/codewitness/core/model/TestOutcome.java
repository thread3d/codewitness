package dev.codewitness.core.model;

/** Outcome of a single test case. */
public enum TestOutcome {
    PASSED,
    FAILED,
    ERROR,
    SKIPPED;

    public boolean isFailure() {
        return this == FAILED || this == ERROR;
    }
}
