package dev.codewitness.core.requirements;

/**
 * The kind of experiment that can discharge a verification obligation.
 *
 * <p>The kind drives test generation: an {@code UPPER_BOUND} obligation with value 1000 produces
 * boundary tests at 999.99, 1000 and 1000.01.
 */
public enum ObligationKind {
    /** Value exactly at, just below and just above a numeric bound. */
    BOUNDARY_VALUE,
    /** A quantity must never exceed a bound. */
    UPPER_BOUND,
    /** A quantity must never fall below a bound. */
    LOWER_BOUND,
    /** An operation must be rejected under a stated condition. */
    REJECTION,
    /** Behaviour must hold when operations interleave. */
    CONCURRENCY,
    /** Behaviour for null and other edge inputs. */
    NULL_HANDLING,
    /** An invariant must hold across a sequence of operations. */
    INVARIANT,
    /** A property must hold for every input in a domain. */
    PROPERTY,
    /** The same input must always produce the same output. */
    DETERMINISM,
    /** A security property, for example input validation or credential handling. */
    SECURITY,
    /** A property that only static analysis or human review can check. */
    STATIC,
    /**
     * An obligation to be discharged by running a process and observing what it printed and returned.
     *
     * <p>Distinct from {@link #PROPERTY} and {@link #BOUNDARY_VALUE}, which are discharged by executing code inside
     * a generated test. A process obligation is the only way to say anything about a program's own interface —
     * its exit codes, its standard output — because those are properties of a process, not of a method call.
     */
    PROCESS,
    /** An obligation that the system cannot discharge automatically. */
    MANUAL,
    /** Obligation derived by the language model; advisory until executed. */
    LLM_DERIVED,
    /** Anything else. */
    GENERIC
}
