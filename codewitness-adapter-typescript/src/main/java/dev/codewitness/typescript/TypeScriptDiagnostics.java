package dev.codewitness.typescript;

import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses TypeScript diagnostics from the compiler and from the runtime's type stripper.
 *
 * <p>Two producers are understood, because the adapter has two builds:
 *
 * <ul>
 *   <li>{@code tsc}, which prints {@code src/ledger.ts(17,5): error TS2322: ...};</li>
 *   <li>the built-in syntax check, which prints one {@code CWDIAG|SEVERITY|file|line|column|message}
 *       line per file the runtime cannot parse.</li>
 * </ul>
 *
 * <p>A diagnostic is only recorded when it has a file, and an unrecognised line is ignored rather than
 * attached to the wrong place: the recorded observation is the tool's own message.
 */
final class TypeScriptDiagnostics {

    private static final Pattern TSC_LOCATED = Pattern.compile(
            "^(.*?)\\((\\d+),(\\d+)\\):\\s+(error|warning|message)\\s+(TS\\d+):\\s*(.*)$");
    private static final Pattern TSC_GLOBAL = Pattern.compile(
            "^(error|warning)\\s+(TS\\d+):\\s*(.*)$");
    private static final Pattern SYNTAX_SUMMARY = Pattern.compile("^CWSYNTAX\\|(.*)$");

    private TypeScriptDiagnostics() {
    }

    /** @return every diagnostic in {@code tsc}'s output. */
    static List<CompilerDiagnostic> parseTsc(String output, Path projectRoot) {
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return diagnostics;
        }
        for (String raw : output.split("\\R")) {
            String line = raw.strip();
            Matcher located = TSC_LOCATED.matcher(line);
            if (located.matches()) {
                diagnostics.add(new CompilerDiagnostic(severity(located.group(4)),
                        relativize(projectRoot, located.group(1)), integer(located.group(2)),
                        integer(located.group(3)), located.group(6).strip(), located.group(5)));
                continue;
            }
            Matcher global = TSC_GLOBAL.matcher(line);
            if (global.matches()) {
                diagnostics.add(new CompilerDiagnostic(severity(global.group(1)), null, null, null,
                        global.group(3).strip(), global.group(2)));
            }
        }
        return diagnostics;
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
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
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
