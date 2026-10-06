package dev.codewitness.core.result;

/** Overall status of a verification run. */
public enum RunStatus {
    /** The pipeline ran to completion; individual requirements may still have failed. */
    COMPLETED,
    /** The pipeline could not complete because of a project or infrastructure problem. */
    BLOCKED,
    /** The pipeline failed because of an internal error. */
    FAILED,
    /** The run was stopped early, for example because the budget was exhausted. */
    ABORTED
}
