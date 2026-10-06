package dev.codewitness.go;

import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the Go toolchain's diagnostics from {@code go build} and {@code go vet} output.
 *
 * <p>Go has no machine readable report file for a plain build, so diagnostics are read from the
 * compiler's own output, which is a single documented shape:
 *
 * <pre>
 * # example.com/ledger
 * ./ledger.go:42:9: undefined: balance
 * ./ledger_test.go:17:20: too many arguments in call to New
 * </pre>
 *
 * <p>The parser is deliberately narrow: only a line of the form {@code file:line:column: message} is
 * recorded, and everything that is not a diagnostic (the {@code # package} headers, {@code go:}
 * progress lines, test result lines) is ignored. The severity comes from the command that produced
 * the output, because Go does not label a line as a warning or an error: a build diagnostic is an
 * error, and a vet diagnostic is a reported concern rather than a build failure.
 */
public final class GoDiagnostics {

    /** {@code file:line:column: message}, optionally with the leading {@code ./} Go uses for relative paths. */
    private static final Pattern DIAGNOSTIC = Pattern.compile("^\\s*(.+?\\.go):(\\d+):(\\d+):\\s*(.+)$");

    private GoDiagnostics() {
    }

    /** @return every diagnostic in the output, in the order the toolchain emitted them. */
    public static List<CompilerDiagnostic> parse(String output, Path projectRoot, DiagnosticSeverity severity) {
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return diagnostics;
        }
        for (String raw : output.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("#") || line.startsWith("go:") || line.isEmpty()) {
                continue;
            }
            Matcher matcher = DIAGNOSTIC.matcher(line);
            if (matcher.matches()) {
                diagnostics.add(new CompilerDiagnostic(severity, relativize(projectRoot, matcher.group(1)),
                        integer(matcher.group(2)), integer(matcher.group(3)), matcher.group(4).strip(), null));
            }
        }
        return diagnostics;
    }

    /** @return the output with the {@code # package} context headers removed, for compact notes. */
    static String stripHeaders(String output) {
        if (output == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : output.split("\\R", -1)) {
            if (line.strip().startsWith("#")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString().strip();
    }

    static String relativize(Path projectRoot, String file) {
        String cleaned = file.strip();
        // go vet prefixes a diagnostic that its driver could not attribute with "vet: ", which is a
        // tool label rather than part of the path; dropping it keeps the finding's file usable.
        if (cleaned.startsWith("vet:")) {
            cleaned = cleaned.substring("vet:".length()).strip();
        }
        if (cleaned.startsWith("./")) {
            cleaned = cleaned.substring(2);
        }
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
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException _) {
            return null;
        }
    }
}
