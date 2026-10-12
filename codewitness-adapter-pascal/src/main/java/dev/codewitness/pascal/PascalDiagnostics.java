package dev.codewitness.pascal;

import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Free Pascal's diagnostics into the engine's normalised form.
 *
 * <p>The compiler writes one diagnostic per line as {@code file(line,column) Severity: message}, for
 * example {@code ledger.pas(42,7) Error: Identifier not found "Withdraw"}. The closing banner lines
 * ({@code Fatal: Compilation aborted}, {@code Error: .../ppcx64 returned an error exitcode}) and the
 * linker's own messages carry no source location and are dropped: they say the build failed, which the
 * exit status already says, without saying anything about the code that the located diagnostics do not.
 */
public final class PascalDiagnostics {

    private static final Pattern DIAGNOSTIC = Pattern.compile(
            "^\\s*(.+?)\\((\\d+)(?:,(\\d+))?\\)\\s*(Fatal|Error|Warning|Note|Hint):\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);

    private PascalDiagnostics() {
    }

    /**
     * @param output          the compiler's combined output
     * @param projectRoot     project directory, used to make reported paths relative
     * @param defaultSeverity severity for a line that carries no label
     * @return the located diagnostics, in output order
     */
    public static List<CompilerDiagnostic> parse(String output, Path projectRoot, DiagnosticSeverity defaultSeverity) {
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return diagnostics;
        }
        for (String rawLine : output.split("\\R")) {
            String line = rawLine.strip();
            Matcher matcher = DIAGNOSTIC.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String file = matcher.group(1).strip();
            Integer lineNumber = integer(matcher.group(2));
            Integer column = integer(matcher.group(3));
            String label = matcher.group(4);
            String message = matcher.group(5) == null ? "" : matcher.group(5).strip();
            if (message.isEmpty()) {
                message = label;
            }
            if (message.isEmpty()) {
                continue;
            }
            diagnostics.add(new CompilerDiagnostic(
                    severityOf(label, defaultSeverity),
                    relativize(projectRoot, file),
                    lineNumber,
                    column,
                    message,
                    null));
        }
        return List.copyOf(diagnostics);
    }

    private static DiagnosticSeverity severityOf(String label, DiagnosticSeverity fallback) {
        if (label == null || label.isBlank()) {
            return fallback;
        }
        String lower = label.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("warning")) {
            return DiagnosticSeverity.WARNING;
        }
        if (lower.startsWith("note") || lower.startsWith("hint")) {
            return DiagnosticSeverity.NOTE;
        }
        return DiagnosticSeverity.ERROR;
    }

    /** @return the file made relative to the project root when it lies below it, or the text as-is. */
    static String relativize(Path projectRoot, String file) {
        if (projectRoot == null || file == null || file.isBlank()) {
            return file;
        }
        try {
            Path path = Path.of(file);
            if (path.isAbsolute()) {
                Path root = projectRoot.toAbsolutePath().normalize();
                Path absolute = path.toAbsolutePath().normalize();
                if (absolute.startsWith(root)) {
                    return root.relativize(absolute).toString();
                }
            }
        } catch (RuntimeException _) {
            // A path the platform cannot parse is reported as the compiler spelled it.
        }
        return file;
    }

    private static Integer integer(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException _) {
            return null;
        }
    }
}
