package dev.codewitness.core.diagnostics;

/**
 * How serious a fault in CodeWitness itself is.
 *
 * <p>This is deliberately not the same scale as {@link dev.codewitness.core.model.Severity}, which
 * grades findings about the project under verification. A self-issue is always about the tool: a run
 * that produced it may still have been correct about the code, and a high self-issue never changes a
 * requirement verdict.
 */
public enum SelfIssueSeverity {

    /** The tool could not complete what it was doing; the result may be missing or misleading. */
    CRITICAL,

    /** One phase, strategy or adapter crashed. The rest of the run could continue around it. */
    HIGH,

    /** A part of the tool degraded, for example a report artefact that could not be written. */
    MEDIUM,

    /** A capability gap: CodeWitness saw something it cannot yet verify. */
    LOW
}
