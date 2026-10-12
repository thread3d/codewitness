package dev.codewitness.verilog;

import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Icarus Verilog's diagnostics from {@code iverilog} and {@code vvp} output.
 *
 * <p>Icarus has no machine readable report file, so diagnostics are read from the compiler's own
 * output, which is a single documented shape:
 *
 * <pre>
 * ledger.v:12: error: Unable to bind wire/reg/memory `balance' in `ledger'
 * ledger.v:20: warning: implicit truncation of a 32-bit expression
 * ledger_tb.v:8: syntax error
 * </pre>
 *
 * <p>A line's severity is taken from its own label when it carries one ({@code error}, {@code warning},
 * {@code note} or the bare {@code syntax error}) and from the command that produced it otherwise: a
 * build diagnostic is an error, because the compile failed. The parser is deliberately narrow - only a
 * line of the form {@code file:line[:column]: message} is recorded - so a VCD dump header, a
 * {@code $display} line from a testbench or the toolchain's own banner is never mistaken for a
 * diagnostic.
 */
public final class VerilogDiagnostics {

    /** {@code file:line: message} or {@code file:line:column: message}, with an optional severity label. */
    private static final Pattern DIAGNOSTIC =
            Pattern.compile("^\\s*(.+?\\.(?:v|sv|vh|svh)):(\\d+)(?::(\\d+))?:\\s*(?:(error|warning|note|syntax error):?\\s*)?(.*)$",
                    Pattern.CASE_INSENSITIVE);

    private VerilogDiagnostics() {
    }

    /** @return every diagnostic in the output, in the order the toolchain emitted them. */
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
            if (!matcher.matches()) {
                continue;
            }
            String label = matcher.group(4);
            String message = matcher.group(5) == null ? "" : matcher.group(5).strip();
            if (message.isEmpty()) {
                // A parse error is printed as `file.v:LINE: syntax error` with no message after the
                // label, so the label is the message. Without this the single most common Icarus
                // diagnostic would be silently dropped from a failed build's diagnostic list.
                message = label == null ? "" : label.strip();
            }
            if (message.isEmpty()) {
                continue;
            }
            DiagnosticSeverity severity = severityOf(label, defaultSeverity);
            diagnostics.add(new CompilerDiagnostic(severity, relativize(projectRoot, matcher.group(1)),
                    integer(matcher.group(2)), integer(matcher.group(3)), message, null));
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
