package dev.codewitness.core.diagnostics;

/**
 * The kind of fault CodeWitness recorded about itself.
 *
 * <p>The categories are narrow on purpose. A verification finding about the project - a failing test,
 * a surviving mutant, a dependency advisory, a build error - is never one of these; those are the
 * product working. A self-issue is a defect, crash, or declared limitation in the tool.
 */
public enum SelfIssueCategory {

    /** An unexpected exception escaped the command or the pipeline. */
    INTERNAL_ERROR,

    /** A verification phase threw instead of returning an outcome. */
    PHASE_FAILURE,

    /** A verification strategy threw while handling a requirement. */
    STRATEGY_FAILURE,

    /** A language adapter, provider or database fixture threw. */
    ADAPTER_FAILURE,

    /** The run completed but one of its report artefacts could not be written. */
    REPORT_FAILURE,

    /** A language was detected but nothing in this build can verify it. */
    CAPABILITY_GAP
}
