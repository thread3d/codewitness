package dev.codewitness.core.evidence;

/**
 * How much weight a piece of evidence carries.
 *
 * <p>This is what keeps an LLM's opinion from being mistaken for a test result. Only
 * {@link #DETERMINISTIC} evidence - a compiler exit code, an executed test, a static analysis run -
 * can move a requirement to {@code VERIFIED} or {@code FAILED} on its own.
 */
public enum EvidenceConfidence {
    /** Reproducible execution of a tool whose result is a fact: compiler, test runner, analyzer. */
    DETERMINISTIC,
    /** Deterministic observation with a small amount of inference involved. */
    HIGH,
    /** Observation with meaningful uncertainty: partial coverage, heuristic analysis. */
    MEDIUM,
    /** Weak signal, for example a heuristic pattern match or a single random scenario. */
    LOW,
    /** Advisory only: language model output, human opinion. Never proof. */
    ADVISORY;

    public boolean isDeterministic() {
        return this == DETERMINISTIC;
    }

    /**
     * @return {@code true} when evidence with this confidence is strong enough to contradict a claim
     *
     * <p>{@code HIGH} qualifies because such an observation is still a real observation; its weakness
     * is that its <em>relevance</em> was inferred, for example "this failing test mentions the
     * requirement". Evidence at this level should therefore be turned into an explicit
     * counterexample rather than used as a bare refutation, which is exactly what
     * {@code VerdictCalculator} requires: it only fails a requirement on deterministic refutation or
     * on a recorded counterexample. The two rules are deliberately different, and this method
     * describes the evidence rather than the verdict policy.
     */
    public boolean canRefute() {
        return this == DETERMINISTIC || this == HIGH;
    }
}
