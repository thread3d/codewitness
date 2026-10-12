package dev.codewitness.vhdl;

import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses GHDL's diagnostics from {@code ghdl -a} and {@code ghdl -e} output.
 *
 * <p>GHDL has no machine readable report file, so diagnostics are read from the tool's own output,
 * which has two documented shapes:
 *
 * <pre>
 * rtl/ledger.vhd:12:17:error: no function declarations for operator "+"
 * rtl/ledger.vhd:20:5:warning: universal integer bound must be numeric literal
 * ghdl:error: elaboration of design unit "ledger" failed
 * </pre>
 *
 * <p>A file-anchored line is recorded with its severity, file and line. A location-less {@code ghdl:}
 * line is recorded as well, because an elaboration failure - which is the second half of what
 * "the design does not build" means in VHDL - is reported only that way; the parser keeps the bare
 * prefix out of the message so a report shows the tool's own sentence rather than its stream name.
 */
public final class VhdlDiagnostics {

    /** {@code file:line:column: severity: message}, with the column optional. */
    private static final Pattern DIAGNOSTIC = Pattern.compile(
            "^\\s*(.+?\\.(?:vhd|vhdl)):(\\d+)(?::(\\d+))?:\\s*(error|warning|note):\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);

    /** {@code ghdl:severity: message}, which carries no location. */
    private static final Pattern TOOL_DIAGNOSTIC = Pattern.compile(
            "^\\s*ghdl:(error|warning|note):\\s*(.*)$", Pattern.CASE_INSENSITIVE);

    private VhdlDiagnostics() {
    }

    /** @return every diagnostic in the output, in the order the tool emitted them. */
    public static List<CompilerDiagnostic> parse(String output, Path projectRoot, DiagnosticSeverity defaultSeverity) {
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return diagnostics;
        }
        for (String raw : output.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            Matcher matcher = DIAGNOSTIC.matcher(line);
            if (matcher.matches()) {
                String message = matcher.group(5) == null ? "" : matcher.group(5).strip();
                if (message.isEmpty()) {
                    continue;
                }
                diagnostics.add(new CompilerDiagnostic(severityOf(matcher.group(4), defaultSeverity),
                        relativize(projectRoot, matcher.group(1)), integer(matcher.group(2)),
                        integer(matcher.group(3)), message, null));
                continue;
            }
            Matcher tool = TOOL_DIAGNOSTIC.matcher(line);
            if (tool.matches()) {
                String message = tool.group(2) == null ? "" : tool.group(2).strip();
                if (message.isEmpty()) {
                    continue;
                }
                diagnostics.add(new CompilerDiagnostic(severityOf(tool.group(1), defaultSeverity),
                        null, null, null, message, null));
            }
        }
        return diagnostics;
    }

    private static DiagnosticSeverity severityOf(String label, DiagnosticSeverity fallback) {
        if (label == null || label.isBlank()) {
            return fallback;
        }
        String lower = label.toLowerCase(Locale.ROOT);
        if (lower.startsWith("warning")) {
            return DiagnosticSeverity.WARNING;
        }
        if (lower.startsWith("note")) {
            return DiagnosticSeverity.NOTE;
        }
        return DiagnosticSeverity.ERROR;
    }

    static String relativize(Path projectRoot, String file) {
        String cleaned = file == null ? "" : file.strip();
        try {
            Path path = Path.of(cleaned);
            if (projectRoot != null && path.isAbsolute() && path.startsWith(projectRoot)) {
                return projectRoot.relativize(path).toString();
            }
        } catch (RuntimeException _) {
            // not a usable path: keep the original text
        }
        return cleaned;
    }

    private static Integer integer(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException _) {
            return null;
        }
    }
}
