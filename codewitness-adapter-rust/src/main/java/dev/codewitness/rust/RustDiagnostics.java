package dev.codewitness.rust;

import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses rustc diagnostics from Cargo's human readable output.
 *
 * <p>Cargo has no machine readable report file for a plain build, so the diagnostics are read from
 * rustc's own output in the format rustc documents:
 *
 * <pre>
 * warning: unused variable: `total`
 *  --&gt; src/lib.rs:42:9
 * error[E0308]: mismatched types
 *  --&gt; src/lib.rs:17:20
 * </pre>
 *
 * <p>The parser is deliberately narrow: a diagnostic is only recorded once its location line is
 * seen, and an unmatched header is dropped rather than attached to the wrong file. Anything that is
 * not a diagnostic (the {@code = note:} and {@code = help:} annotations, the progress lines) is
 * ignored, so the recorded observation is rustc's message and not a reading of its log.
 */
final class RustDiagnostics {

    private static final Pattern HEADER = Pattern.compile(
            "^(error|warning)(?:\\[([A-Za-z0-9_]+)\\])?:\\s*(.+)$");
    private static final Pattern LOCATION = Pattern.compile(
            "^\\s*-->\\s*(.+?):(\\d+):(\\d+)\\s*$");

    private RustDiagnostics() {
    }

    /** @return every located diagnostic in the output, in the order rustc emitted them. */
    static List<CompilerDiagnostic> parse(String output, Path projectRoot) {
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return diagnostics;
        }
        DiagnosticSeverity severity = null;
        String code = null;
        String message = null;
        for (String raw : output.split("\\R")) {
            String line = raw.stripTrailing();
            Matcher header = HEADER.matcher(line.strip());
            if (header.matches()) {
                severity = header.group(1).equals("error") ? DiagnosticSeverity.ERROR : DiagnosticSeverity.WARNING;
                code = header.group(2);
                message = header.group(3).strip();
                continue;
            }
            Matcher location = LOCATION.matcher(line);
            if (location.matches() && severity != null) {
                diagnostics.add(new CompilerDiagnostic(severity, relativize(projectRoot, location.group(1)),
                        integer(location.group(2)), integer(location.group(3)), message, code));
                // A header describes one diagnostic; a second location line before the next header
                // belongs to a note, so the header is consumed here.
                severity = null;
                code = null;
                message = null;
            }
        }
        return diagnostics;
    }

    static String relativize(Path projectRoot, String file) {
        try {
            Path path = Path.of(file.strip());
            if (projectRoot != null && path.isAbsolute() && path.startsWith(projectRoot)) {
                return projectRoot.relativize(path).toString();
            }
        } catch (RuntimeException _) {
            // not a usable path: keep the original text
        }
        return file.strip();
    }

    private static Integer integer(String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException _) {
            return null;
        }
    }
}
