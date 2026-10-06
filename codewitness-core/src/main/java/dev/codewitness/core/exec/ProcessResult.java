package dev.codewitness.core.exec;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of executing an external process.
 *
 * @param command          the argv that was executed
 * @param workingDirectory directory the process ran in
 * @param started          {@code false} when the process could not be started at all
 * @param exitCode         process exit code, or {@code -1} when the process did not start or was killed
 * @param stdout           captured standard output, possibly truncated
 * @param stderr           captured standard error, possibly truncated
 * @param duration         wall-clock duration of the execution
 * @param timedOut         {@code true} when the process was killed because it exceeded its timeout
 * @param failureReason    populated when {@code started} is {@code false}
 */
public record ProcessResult(
        List<String> command,
        Path workingDirectory,
        boolean started,
        int exitCode,
        String stdout,
        String stderr,
        Duration duration,
        boolean timedOut,
        String failureReason) {

    public ProcessResult {
        command = List.copyOf(command);
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
        Objects.requireNonNull(duration, "duration");
    }

    public static ProcessResult notStarted(List<String> command, Path workingDirectory, String reason) {
        return new ProcessResult(command, workingDirectory, false, -1, "", "", Duration.ZERO, false, reason);
    }

    public boolean success() {
        return started && !timedOut && exitCode == 0;
    }

    public String displayCommand() {
        return String.join(" ", command);
    }

    public String combinedOutput() {
        if (stderr.isBlank()) {
            return stdout;
        }
        if (stdout.isBlank()) {
            return stderr;
        }
        return stdout + System.lineSeparator() + stderr;
    }

    /** @return the last {@code lines} lines of the combined output, for reports and prompts. */
    public String tail(int lines) {
        String combined = combinedOutput();
        String[] split = combined.split("\\R");
        if (split.length <= lines) {
            return combined.trim();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = split.length - lines; i < split.length; i++) {
            sb.append(split[i]).append(System.lineSeparator());
        }
        return sb.toString().trim();
    }

    public String describeOutcome() {
        if (!started) {
            return "not started: " + failureReason;
        }
        if (timedOut) {
            return "timed out after " + duration.toSeconds() + "s";
        }
        return "exit code " + exitCode + " in " + duration.toMillis() + "ms";
    }
}
