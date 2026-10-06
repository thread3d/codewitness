package dev.codewitness.core.model;

import dev.codewitness.core.exec.ProcessResult;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of building (compiling and packaging) a project.
 *
 * <p>A failed build is a statement about the build, never about the correctness of the code. The
 * engine maps a failed build to {@code BLOCKED}, not to {@code FAILED}.
 */
public final class BuildResult {

    private final boolean attempted;
    private final boolean success;
    private final int exitCode;
    private final Duration duration;
    private final List<String> command;
    private final String output;
    private final List<CompilerDiagnostic> diagnostics;
    private final List<Path> artifacts;
    private final boolean timedOut;
    private final String failureReason;

    private BuildResult(Builder builder) {
        this.attempted = builder.attempted;
        this.success = builder.success;
        this.exitCode = builder.exitCode;
        this.duration = builder.duration;
        this.command = List.copyOf(builder.command);
        this.output = builder.output;
        this.diagnostics = List.copyOf(builder.diagnostics);
        this.artifacts = List.copyOf(builder.artifacts);
        this.timedOut = builder.timedOut;
        this.failureReason = builder.failureReason;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static BuildResult notAttempted(String reason) {
        return builder().attempted(false).failureReason(reason).build();
    }

    /** Builds a result from a process execution, without compiler diagnostics. */
    public static BuildResult from(ProcessResult process) {
        return from(process, List.of());
    }

    public static BuildResult from(ProcessResult process, List<CompilerDiagnostic> diagnostics) {
        return builder()
                .attempted(true)
                .success(process.success())
                .exitCode(process.exitCode())
                .duration(process.duration())
                .command(process.command())
                .output(process.combinedOutput())
                .diagnostics(diagnostics)
                .timedOut(process.timedOut())
                .failureReason(process.started() ? null : process.failureReason())
                .build();
    }

    public boolean attempted() {
        return attempted;
    }

    public boolean success() {
        return success;
    }

    public int exitCode() {
        return exitCode;
    }

    public Duration duration() {
        return duration;
    }

    public List<String> command() {
        return command;
    }

    public String output() {
        return output;
    }

    public List<CompilerDiagnostic> diagnostics() {
        return diagnostics;
    }

    public List<CompilerDiagnostic> errors() {
        return diagnostics.stream().filter(d -> d.severity() == DiagnosticSeverity.ERROR).toList();
    }

    public List<CompilerDiagnostic> warnings() {
        return diagnostics.stream().filter(d -> d.severity() == DiagnosticSeverity.WARNING).toList();
    }

    public List<Path> artifacts() {
        return artifacts;
    }

    public boolean timedOut() {
        return timedOut;
    }

    public String failureReason() {
        return failureReason;
    }

    public String describe() {
        if (!attempted) {
            return "build not attempted (" + failureReason + ")";
        }
        if (timedOut) {
            return "build timed out after " + duration.toSeconds() + "s";
        }
        if (success) {
            return "build succeeded in " + duration.toMillis() + "ms";
        }
        return "build failed with exit code " + exitCode
                + (failureReason == null ? "" : " (" + failureReason + ")");
    }

    public static final class Builder {
        private boolean attempted = true;
        private boolean success;
        private int exitCode = -1;
        private Duration duration = Duration.ZERO;
        private List<String> command = new ArrayList<>();
        private String output = "";
        private List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        private List<Path> artifacts = new ArrayList<>();
        private boolean timedOut;
        private String failureReason;

        public Builder attempted(boolean value) {
            this.attempted = value;
            return this;
        }

        public Builder success(boolean value) {
            this.success = value;
            return this;
        }

        public Builder exitCode(int value) {
            this.exitCode = value;
            return this;
        }

        public Builder duration(Duration value) {
            this.duration = Objects.requireNonNull(value);
            return this;
        }

        public Builder command(List<String> value) {
            this.command = value == null ? List.of() : value;
            return this;
        }

        public Builder output(String value) {
            this.output = value == null ? "" : value;
            return this;
        }

        public Builder diagnostics(List<CompilerDiagnostic> value) {
            this.diagnostics = value == null ? List.of() : value;
            return this;
        }

        public Builder artifacts(List<Path> value) {
            this.artifacts = value == null ? List.of() : value;
            return this;
        }

        public Builder timedOut(boolean value) {
            this.timedOut = value;
            return this;
        }

        public Builder failureReason(String value) {
            this.failureReason = value;
            return this;
        }

        public BuildResult build() {
            return new BuildResult(this);
        }
    }
}
