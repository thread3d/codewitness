package dev.codewitness.core.exec;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A request to execute an external process inside the verification sandbox.
 *
 * @param command             argv style command; the first element is the executable
 * @param workingDirectory    directory the process runs in; must be inside the sandbox roots
 * @param environment         extra environment variables merged over the sandbox baseline
 * @param timeout             hard wall-clock limit after which the process is force killed
 * @param maxOutputBytes      per-stream capture limit; excess output is truncated, never buffered
 * @param inheritEnvironment  whether the host environment is inherited ({@code false} by default,
 *                            because the project under verification is treated as hostile)
 */
public record ProcessSpec(
        List<String> command,
        Path workingDirectory,
        Map<String, String> environment,
        Duration timeout,
        int maxOutputBytes,
        boolean inheritEnvironment) {

    public static final int DEFAULT_MAX_OUTPUT_BYTES = 256 * 1024;

    public ProcessSpec {
        Objects.requireNonNull(command, "command");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        command = List.copyOf(command);
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        workingDirectory = workingDirectory.toAbsolutePath().normalize();
        environment = environment == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(environment));
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maxOutputBytes <= 0) {
            maxOutputBytes = DEFAULT_MAX_OUTPUT_BYTES;
        }
    }

    public static Builder builder(List<String> command, Path workingDirectory) {
        return new Builder(command, workingDirectory);
    }

    public static Builder builder(String... command) {
        return new Builder(List.of(command), Path.of("."));
    }

    public ProcessSpec withTimeout(Duration newTimeout) {
        return new ProcessSpec(command, workingDirectory, environment, newTimeout, maxOutputBytes, inheritEnvironment);
    }

    /** @return the command rendered for reports and for manual reproduction. */
    public String displayCommand() {
        return String.join(" ", command);
    }

    public static final class Builder {
        private final List<String> command;
        private Path workingDirectory;
        private final Map<String, String> environment = new LinkedHashMap<>();
        private Duration timeout = Duration.ofMinutes(10);
        private int maxOutputBytes = DEFAULT_MAX_OUTPUT_BYTES;
        private boolean inheritEnvironment;

        private Builder(List<String> command, Path workingDirectory) {
            this.command = List.copyOf(command);
            this.workingDirectory = workingDirectory;
        }

        public Builder workingDirectory(Path directory) {
            this.workingDirectory = directory;
            return this;
        }

        public Builder env(String key, String value) {
            this.environment.put(key, value);
            return this;
        }

        /** Merges a map of environment variables over the sandbox baseline. */
        public Builder environment(Map<String, String> values) {
            if (values != null) {
                this.environment.putAll(values);
            }
            return this;
        }

        public Builder timeout(Duration value) {
            this.timeout = value;
            return this;
        }

        public Builder maxOutputBytes(int value) {
            this.maxOutputBytes = value;
            return this;
        }

        public Builder inheritEnvironment(boolean value) {
            this.inheritEnvironment = value;
            return this;
        }

        public ProcessSpec build() {
            return new ProcessSpec(command, workingDirectory, environment, timeout, maxOutputBytes, inheritEnvironment);
        }
    }
}
