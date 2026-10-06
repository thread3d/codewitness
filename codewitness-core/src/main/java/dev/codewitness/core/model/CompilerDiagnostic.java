package dev.codewitness.core.model;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A single compiler diagnostic.
 *
 * @param severity compiler severity
 * @param file     source file, relative to the project root when possible
 * @param line     1-based line number, or {@code null}
 * @param column   1-based column number, or {@code null}
 * @param message  diagnostic message
 * @param code     diagnostic code such as {@code [deprecation]}, or {@code null}
 */
public record CompilerDiagnostic(
        DiagnosticSeverity severity,
        String file,
        Integer line,
        Integer column,
        String message,
        String code) {

    public CompilerDiagnostic {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
    }

    public static CompilerDiagnostic error(String file, Integer line, String message) {
        return new CompilerDiagnostic(DiagnosticSeverity.ERROR, file, line, null, message, null);
    }

    public String location() {
        if (file == null) {
            return "<unknown>";
        }
        return line == null ? file : file + ":" + line;
    }

    @Override
    public String toString() {
        return severity + " " + location() + " " + message;
    }

    /** Convenience for building the file path when the diagnostic refers to a file inside the project. */
    public Path resolve(Path projectRoot) {
        return file == null ? null : projectRoot.resolve(file);
    }
}
