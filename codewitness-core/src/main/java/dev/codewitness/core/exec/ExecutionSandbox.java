package dev.codewitness.core.exec;

import java.nio.file.Path;

/**
 * The boundary inside which project code is executed.
 *
 * <p>CodeWitness treats a project under verification as potentially hostile. Everything that runs
 * project code - builds, tests, generated tests, analyzers - goes through a sandbox so that the
 * isolation strategy is a single, auditable decision rather than an accident of where
 * {@code ProcessBuilder} happened to be called.
 */
public interface ExecutionSandbox extends AutoCloseable {

    /** Short identifier of the strategy, for example {@code local-process} or {@code docker}. */
    String id();

    IsolationLevel isolationLevel();

    /** Human readable description included in reports, including the limitations of the strategy. */
    String description();

    /** The runner used to execute external commands such as the project's build tool. */
    ProcessRunner runner();

    /** @return {@code true} when {@code path} may be used as a working directory inside the sandbox. */
    boolean permits(Path path);

    @Override
    default void close() {
        // nothing to release by default
    }
}
