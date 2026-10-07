package dev.codewitness.csharp;

import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the .NET compiler's diagnostics from {@code dotnet build} output.
 *
 * <p>MSBuild has no report file for an ordinary build, but it does have a documented, stable line
 * shape for a diagnostic the compiler attributed to a source position:
 *
 * <pre>
 * /src/Ledger/Ledger.cs(13,33): error CS1002: ; expected [/src/Ledger/Ledger.csproj]
 * /src/Ledger/Ledger.cs(21,9): warning CS0219: The variable 'total' is assigned but its value is never used
 * </pre>
 *
 * <p>The parser is deliberately narrow: it records a line only when it carries a file, a line and a
 * column, a severity and a message. That is what keeps the noise MSBuild prints around the
 * diagnostics - the restore logs, the project banners, the summary, and the NuGet audit warnings that
 * have no source position at all - out of the findings, without having to enumerate the noise.
 *
 * <p>The trailing {@code [project.csproj]} MSBuild appends to a diagnostic is removed rather than kept
 * in the message, because it names the project, not the defect, and every diagnostic already carries
 * the file it is in.
 */
public final class CSharpDiagnostics {

    /** {@code file(line,column): severity CODE: message [project]}. */
    private static final Pattern DIAGNOSTIC = Pattern.compile(
            "^\\s*(.+?)\\((\\d+)\\s*,\\s*(\\d+)\\)\\s*:\\s*(error|warning)\\s+([A-Za-z]{2,4}\\d{3,5})?\\s*:?\\s*(.*)$");

    /** The project MSBuild appends in square brackets at the end of a diagnostic. */
    private static final Pattern TRAILING_PROJECT = Pattern.compile("\\s*\\[[^\\[\\]]*\\.csproj\\]\\s*$");

    private CSharpDiagnostics() {
    }

    /** @return every diagnostic in the output, in the order the compiler emitted them. */
    public static List<CompilerDiagnostic> parse(String output, Path projectRoot) {
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return diagnostics;
        }
        for (String raw : output.split("\\R")) {
            Matcher matcher = DIAGNOSTIC.matcher(raw);
            if (!matcher.matches()) {
                continue;
            }
            String message = matcher.group(6) == null ? "" : matcher.group(6).strip();
            message = TRAILING_PROJECT.matcher(message).replaceFirst("").strip();
            if (message.isEmpty()) {
                // A position with no message is not a diagnostic a reader can act on; the tool's own
                // summary lines are the usual source of such a match.
                continue;
            }
            diagnostics.add(new CompilerDiagnostic(
                    severity(matcher.group(4)),
                    relativize(projectRoot, matcher.group(1)),
                    integer(matcher.group(2)),
                    integer(matcher.group(3)),
                    message,
                    matcher.group(5)));
        }
        return List.copyOf(diagnostics);
    }

    static DiagnosticSeverity severity(String value) {
        return "error".equalsIgnoreCase(value) ? DiagnosticSeverity.ERROR : DiagnosticSeverity.WARNING;
    }

    static String relativize(Path projectRoot, String file) {
        String cleaned = file.strip();
        if (cleaned.startsWith("./")) {
            cleaned = cleaned.substring(2);
        }
        try {
            Path path = Path.of(cleaned);
            if (projectRoot != null && path.isAbsolute() && path.startsWith(projectRoot)) {
                return projectRoot.relativize(path).toString();
            }
        } catch (RuntimeException _) {
            // not a usable path: keep the original text, which is still the tool's own words
        }
        return cleaned;
    }

    private static Integer integer(String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException _) {
            return null;
        }
    }
}
