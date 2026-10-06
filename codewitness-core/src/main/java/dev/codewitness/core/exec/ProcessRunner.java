package dev.codewitness.core.exec;

/** Executes external processes. Implementations must never throw for a failed process; a failure is
 * represented by {@link ProcessResult#started()} being {@code false}. */
@FunctionalInterface
public interface ProcessRunner {

    ProcessResult run(ProcessSpec spec);

    /** A runner that refuses to execute anything. Useful as a safe default in tests. */
    static ProcessRunner denying(String reason) {
        return spec -> ProcessResult.notStarted(spec.command(), spec.workingDirectory(), reason);
    }
}
