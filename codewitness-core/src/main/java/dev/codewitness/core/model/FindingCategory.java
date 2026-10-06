package dev.codewitness.core.model;

/** Category of an analysis finding. Normalised so that the verification core is independent of any
 * single analyzer's output format. */
public enum FindingCategory {
    BUG,
    CORRECTNESS,
    CONCURRENCY,
    SECURITY,
    PERFORMANCE,
    RELIABILITY,
    MAINTAINABILITY,
    STYLE,
    DEPENDENCY,
    UNKNOWN
}
