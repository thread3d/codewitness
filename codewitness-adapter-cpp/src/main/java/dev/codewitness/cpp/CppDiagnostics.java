package dev.codewitness.cpp;

import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses C and C++ compiler diagnostics from a {@code cmake --build} transcript.
 *
 * <p>A C or C++ compiler prints its diagnostics in one of two documented shapes, and both are read here:
 *
 * <pre>
 * /src/ledger.cpp:13:33: error: expected ';' after expression
 * /src/ledger.cpp:21:9: warning: unused variable 'total' [-Wunused-variable]
 * /src/ledger.cpp(13,33): error C2143: syntax error: missing ';' before '}'
 * /src/ledger.cpp(13): error C2143: syntax error: missing ';' before '}'
 * </pre>
 *
 * <p>The parser is deliberately narrow: it records a line only when it carries a file, a line number, a
 * severity and a message. That is what keeps the noise a build prints around the diagnostics - the CMake
 * configure log, the make or ninja progress lines, the compiler's {@code In file included from} trail, the
 * source echo and its caret, and the {@code Error 1} summary - out of the findings without having to
 * enumerate the noise.
 *
 * <p>A linker failure has no source position at all, and it is the one compiler error that routinely is
 * not attached to a line. It is reported as a single diagnostic with no file, because a build that fails to
 * link failed to build, and a report that showed only the diagnostics the compiler managed to attribute
 * would show nothing at all.
 */
public final class CppDiagnostics {

    /** {@code file:line:column: severity: message}, the clang and gcc shape. */
    private static final Pattern WITH_COLUMN = Pattern.compile(
            "^\\s*(.+?):(\\d+):(\\d+):\\s*(fatal error|error|warning|note)\\s*:\\s*(.*)$");

    /** {@code file:line: severity: message}, which gcc prints when it has no column. */
    private static final Pattern WITH_LINE = Pattern.compile(
            "^\\s*(.+?):(\\d+):\\s*(fatal error|error|warning|note)\\s*:\\s*(.*)$");

    /** {@code file(line,column): error C1234: message}, the MSVC shape, with the column optional. */
    private static final Pattern MSVC = Pattern.compile(
            "^\\s*(.+?)\\((\\d+)\\s*(?:,\\s*(\\d+))?\\)\\s*:\\s*(fatal error|error|warning)\\s*([A-Za-z]{1,4}\\d{3,5})?\\s*:\\s*(.*)$");

    /** A linker failure, which names no source position. */
    private static final List<Pattern> LINKER = List.of(
            Pattern.compile("(?m)^\\s*Undefined symbols for architecture .*$"),
            Pattern.compile("(?m)^\\s*duplicate symbol .*$"),
            Pattern.compile("(?m)^\\s*ld: .*error.*$"),
            Pattern.compile("(?m)^.*LNK\\d{4}: .*$"),
            Pattern.compile("(?m)^\\s*collect2: error: .*$"));

    private CppDiagnostics() {
    }

    /** @return every diagnostic in the output, in the order the compiler emitted them. */
    public static List<CompilerDiagnostic> parse(String output, Path projectRoot) {
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return diagnostics;
        }
        for (String raw : output.split("\\R")) {
            CompilerDiagnostic diagnostic = parseLine(raw, projectRoot);
            if (diagnostic != null) {
                diagnostics.add(diagnostic);
            }
        }
        return List.copyOf(diagnostics);
    }

    /** @return the diagnostic one line carries, or {@code null} when the line is not one. */
    static CompilerDiagnostic parseLine(String line, Path projectRoot) {
        Matcher column = WITH_COLUMN.matcher(line);
        if (column.matches()) {
            return diagnostic(column.group(4), projectRoot, column.group(1),
                    integer(column.group(2)), integer(column.group(3)), column.group(5), null);
        }
        Matcher msvc = MSVC.matcher(line);
        if (msvc.matches()) {
            return diagnostic(msvc.group(4), projectRoot, msvc.group(1), integer(msvc.group(2)),
                    integer(msvc.group(3)), msvc.group(6), msvc.group(5));
        }
        Matcher single = WITH_LINE.matcher(line);
        if (single.matches()) {
            return diagnostic(single.group(3), projectRoot, single.group(1), integer(single.group(2)),
                    null, single.group(4), null);
        }
        return null;
    }

    /**
     * @return one diagnostic for a linker failure, with no source position, or an empty list when the
     *     output carries none.
     */
    public static List<CompilerDiagnostic> linkerErrors(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        for (Pattern pattern : LINKER) {
            Matcher matcher = pattern.matcher(output);
            if (matcher.find()) {
                String message = matcher.group().strip();
                if (!message.isEmpty()) {
                    diagnostics.add(new CompilerDiagnostic(DiagnosticSeverity.ERROR, null, null, null,
                            truncate(message), null));
                }
            }
        }
        return List.copyOf(diagnostics);
    }

    private static CompilerDiagnostic diagnostic(
            String severity, Path projectRoot, String file, Integer line, Integer column, String message, String code) {
        String text = message == null ? "" : message.strip();
        if (text.isEmpty()) {
            // A position with no message is not a diagnostic a reader can act on; a tool's own summary
            // lines are the usual source of such a match.
            return null;
        }
        DiagnosticSeverity normalised = switch (severity.toLowerCase(java.util.Locale.ROOT)) {
            case "error", "fatal error" -> DiagnosticSeverity.ERROR;
            case "warning" -> DiagnosticSeverity.WARNING;
            default -> null;
        };
        if (normalised == null) {
            // A note is context for a diagnostic that is already reported, not a finding of its own.
            return null;
        }
        return new CompilerDiagnostic(normalised, relativize(projectRoot, file), line, column, text, code);
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
            // not a usable path: keep the original text, which is still the compiler's own words
        }
        return cleaned;
    }

    private static Integer integer(String value) {
        if (value == null) {
            // The MSVC shape makes the column optional, so an absent group is "no column", not a crash.
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException _) {
            return null;
        }
    }

    private static String truncate(String value) {
        return value.length() <= 500 ? value : value.substring(0, 500) + "...";
    }
}
