package dev.codewitness.core.exec;

/**
 * The strength of the isolation boundary that applies to executed project code.
 *
 * <p>CodeWitness executes arbitrary project code; the isolation level is reported verbatim in every
 * verification report so that a reader can judge how much trust the evidence deserves.
 */
public enum IsolationLevel {
    /** No isolation: project code runs with the privileges of the CodeWitness process. */
    NONE,
    /**
     * Process isolation: sanitised environment, isolated working directory, output caps and hard
     * timeouts. This limits accidental damage and resource exhaustion but is <em>not</em> a security
     * boundary against hostile code.
     */
    PROCESS,
    /** Container isolation: project code runs in a container with dropped capabilities and no network. */
    CONTAINER
}
