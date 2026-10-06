package dev.codewitness.core.exec;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Container based sandbox: project code runs inside a Docker container with a read-write mount of
 * the project directory only, no network and no host credentials.
 *
 * <p>The container strategy is only used when it is explicitly configured and Docker is available;
 * {@link #isAvailable()} probes for a usable Docker daemon. When Docker is unavailable the caller is
 * expected to fall back to {@link LocalProcessSandbox} and the report records the weaker isolation
 * level.
 */
public final class DockerSandbox implements ExecutionSandbox {

    private final Path hostProjectRoot;
    private final String image;
    private final String containerWorkdir;
    private final ProcessRunner hostRunner;
    private final Map<String, String> extraEnvironment;

    public DockerSandbox(Path hostProjectRoot, String image, String containerWorkdir, ProcessRunner hostRunner) {
        this(hostProjectRoot, image, containerWorkdir, hostRunner, Map.of());
    }

    public DockerSandbox(
            Path hostProjectRoot,
            String image,
            String containerWorkdir,
            ProcessRunner hostRunner,
            Map<String, String> extraEnvironment) {
        this.hostProjectRoot = hostProjectRoot.toAbsolutePath().normalize();
        this.image = image;
        this.containerWorkdir = containerWorkdir;
        this.hostRunner = hostRunner;
        this.extraEnvironment = Map.copyOf(extraEnvironment);
    }

    /** Probes the local Docker daemon. Never throws. */
    public static boolean isAvailable(ProcessRunner runner, Path workingDirectory, String dockerExecutable) {
        ProcessResult result = runner.run(ProcessSpec.builder(List.of(dockerExecutable, "info", "--format", "{{.ServerVersion}}"),
                        workingDirectory)
                .timeout(Duration.ofSeconds(20))
                .build());
        return result.success() && !result.stdout().isBlank();
    }

    @Override
    public String id() {
        return "docker";
    }

    @Override
    public IsolationLevel isolationLevel() {
        return IsolationLevel.CONTAINER;
    }

    @Override
    public String description() {
        return "Docker container (" + image + ") with the project mounted at " + containerWorkdir
                + ", network disabled (--network=none), no host credentials and dropped capabilities.";
    }

    @Override
    public ProcessRunner runner() {
        return spec -> {
            if (!permits(spec.workingDirectory())) {
                return ProcessResult.notStarted(spec.command(), spec.workingDirectory(),
                        "working directory is outside the mounted project root");
            }
            String relative = hostProjectRoot.relativize(spec.workingDirectory().toAbsolutePath().normalize()).toString();
            String containerDirectory = relative.isEmpty() ? containerWorkdir
                    : containerWorkdir + "/" + relative.replace('\\', '/');

            List<String> command = new ArrayList<>(List.of(
                    "docker", "run", "--rm",
                    "--network=none",
                    "--cap-drop=ALL",
                    "--security-opt=no-new-privileges",
                    "-v", hostProjectRoot + ":" + containerWorkdir,
                    "-w", containerDirectory,
                    "-e", "HOME=" + containerWorkdir + "/.codewitness/home"));
            for (Map.Entry<String, String> entry : extraEnvironment.entrySet()) {
                command.add("-e");
                command.add(entry.getKey() + "=" + entry.getValue());
            }
            for (Map.Entry<String, String> entry : spec.environment().entrySet()) {
                command.add("-e");
                command.add(entry.getKey() + "=" + entry.getValue());
            }
            command.add(image);
            command.addAll(spec.command());

            ProcessSpec dockerSpec = new ProcessSpec(command, hostProjectRoot, Map.of(), spec.timeout(),
                    spec.maxOutputBytes(), false);
            return hostRunner.run(dockerSpec);
        };
    }

    @Override
    public boolean permits(Path path) {
        return path.toAbsolutePath().normalize().startsWith(hostProjectRoot);
    }

    @Override
    public String toString() {
        return "DockerSandbox[" + image + " -> " + hostProjectRoot + "/" + containerWorkdir.toLowerCase(Locale.ROOT) + "]";
    }
}
