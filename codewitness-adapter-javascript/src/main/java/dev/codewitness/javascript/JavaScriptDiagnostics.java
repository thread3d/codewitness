package dev.codewitness.javascript;

import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the diagnostics the JavaScript syntax checker reports.
 *
 * <p>There is one producer: the built-in syntax check, which runs the runtime's own parser over every
 * source and test file and prints one
 * {@code CWDIAG|SEVERITY|file|line|column|message} line per file it cannot parse. JavaScript has no
 * separate compiler step, so there is no compiler diagnostic format to understand.
 *
 * <p>A diagnostic is only recorded when it has a file, and an unrecognised line is ignored rather than
 * attached to the wrong place: the recorded observation is the runtime's own message.
 */
final class JavaScriptDiagnostics {

    private static final Pattern SYNTAX_SUMMARY = Pattern.compile("^CWSYNTAX\\|(.*)$");

    private JavaScriptDiagnostics() {
    }

    /** Parses {@code CWDIAG|SEVERITY|file|line|column|message} lines emitted by the syntax checker. */
    static List<CompilerDiagnostic> parseHelper(String output, Path projectRoot) {
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        if (output == null) {
            return diagnostics;
        }
        for (String raw : output.split("\\R")) {
            String line = raw.strip();
            if (!line.startsWith("CWDIAG|")) {
                continue;
            }
            String[] parts = line.split("\\|", 6);
            if (parts.length < 6) {
                continue;
            }
            diagnostics.add(new CompilerDiagnostic(severity(parts[1]), relativize(projectRoot, parts[2]),
                    integer(parts[3]), integer(parts[4]), parts[5], null));
        }
        return diagnostics;
    }

    /** @return the syntax checker's own summary line, or {@code null}. */
    static String syntaxSummary(String output) {
        if (output == null) {
            return null;
        }
        for (String raw : output.split("\\R")) {
            Matcher matcher = SYNTAX_SUMMARY.matcher(raw.strip());
            if (matcher.matches()) {
                return matcher.group(1).strip();
            }
        }
        return null;
    }

    private static DiagnosticSeverity severity(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "error" -> DiagnosticSeverity.ERROR;
            case "warning" -> DiagnosticSeverity.WARNING;
            default -> DiagnosticSeverity.NOTE;
        };
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
            int parsed = Integer.parseInt(value.trim());
            return parsed <= 0 ? null : parsed;
        } catch (NumberFormatException _) {
            return null;
        }
    }
}
