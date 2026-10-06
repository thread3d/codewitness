package dev.codewitness.core.model;

/**
 * A verification capability that a language adapter or verification provider may offer.
 *
 * <p>Capabilities are declared rather than assumed: the engine only schedules a verification phase
 * when some provider claims the corresponding capability. When nothing claims a capability the
 * affected requirements stay {@code UNVERIFIED} instead of silently passing.
 */
public enum Capability {
    INSPECT,
    BUILD,
    TEST,
    COVERAGE,
    MUTATION,
    STATIC_ANALYSIS,
    PROPERTY_TESTING,
    GENERATED_TESTS,
    SECURITY_SCAN,
    DEPENDENCY_SCAN
}
