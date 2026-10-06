package dev.codewitness.core.model;

/** Status of a single mutant as reported by a mutation testing framework. */
public enum MutantStatus {
    KILLED,
    SURVIVED,
    NO_COVERAGE,
    TIMED_OUT,
    NON_VIABLE,
    MEMORY_ERROR,
    RUN_ERROR,
    UNKNOWN
}
