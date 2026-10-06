package dev.codewitness.core.engine;

import dev.codewitness.core.config.VerificationConfig;
import dev.codewitness.core.exec.DockerSandbox;
import dev.codewitness.core.exec.ExecutionSandbox;
import dev.codewitness.core.exec.LocalProcessSandbox;
import dev.codewitness.core.exec.ProcessRunner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Chooses the isolation strategy for a run.
 *
 * <p>Docker is used when it is configured and actually reachable; otherwise CodeWitness falls back
 * to process isolation and the report states the weaker level explicitly. It never silently claims
 * container isolation it did not get.
 */
public final class SandboxFactory {

    private SandboxFactory() {
    }

    /**
     * @param config        effective configuration
     * @param workspaceRoot project root inside the run workspace
     * @param runDirectory  run directory (artifacts, logs) that the sandbox may write to
     * @param hostRunner    raw process runner used for the Docker probe
     * @return the sandbox to use
     */
    public static ExecutionSandbox create(
            VerificationConfig config, Path workspaceRoot, Path runDirectory, ProcessRunner hostRunner) {

        List<Path> roots = new ArrayList<>();
        roots.add(workspaceRoot);
        roots.add(runDirectory);
        roots.add(Path.of(System.getProperty("java.io.tmpdir")));

        String mode = config.sandbox().mode() == null ? "local-process" : config.sandbox().mode().trim();
        if ("docker".equalsIgnoreCase(mode)) {
            if (DockerSandbox.isAvailable(hostRunner, runDirectory, "docker")) {
                return new DockerSandbox(workspaceRoot, config.sandbox().image(),
                        config.sandbox().containerWorkdir(), hostRunner);
            }
            LocalProcessSandbox fallback = new LocalProcessSandbox(roots, System.getenv(),
                    config.sandbox().allowNetwork());
            System.err.println("[codewitness] sandbox.mode=docker was requested but no Docker daemon is "
                    + "reachable; falling back to " + fallback.id() + " (" + fallback.isolationLevel() + ")");
            return fallback;
        }
        return new LocalProcessSandbox(roots, System.getenv(), config.sandbox().allowNetwork());
    }
}
