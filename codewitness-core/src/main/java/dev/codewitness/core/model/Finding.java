package dev.codewitness.core.model;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A normalised static-analysis finding.
 *
 * <p>Deliberately mirrors the field set required by the specification
 * ({@code severity, category, file, line, message}) while carrying enough provenance to trace a
 * finding back to the analyzer, rule and raw output that produced it.
 *
 * @param analyzer   identifier of the analyzer that produced the finding
 * @param ruleId     analyzer specific rule identifier, or {@code null} when not applicable
 * @param severity   normalised severity
 * @param category   normalised category
 * @param message    human readable description
 * @param file       file the finding refers to, relative to the project root when possible
 * @param line       1-based line number, or {@code null} when unknown
 * @param module     module name the finding belongs to, or {@code null}
 * @param attributes additional analyzer specific detail
 */
public record Finding(
        String analyzer,
        String ruleId,
        Severity severity,
        FindingCategory category,
        String message,
        String file,
        Integer line,
        String module,
        Map<String, String> attributes) {

    public Finding {
        Objects.requireNonNull(analyzer, "analyzer");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(message, "message");
        attributes = attributes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(attributes));
    }

    public static Finding of(String analyzer, Severity severity, FindingCategory category, String message) {
        return new Finding(analyzer, null, severity, category, message, null, null, null, Map.of());
    }

    public String location() {
        if (file == null) {
            return "<unknown>";
        }
        return line == null ? file : file + ":" + line;
    }

    /**
     * @return {@code true} when a deterministic tool produced this finding.
     *
     * <p>A compiler, a linter or a vet tool reports a fact about the code, so a serious finding from
     * one may refute a requirement. The pattern-matching heuristic rule tables are a different kind
     * of thing: they are documented as having a known false-positive profile, so their findings are
     * advisory and must never fail a requirement on their own. The heuristic analyzers identify
     * themselves with an analyzer id containing {@code heuristic} (for example
     * {@code python-heuristics}, {@code go-heuristics}); anything else is a real tool.
     */
    public boolean fromDeterministicTool() {
        return !analyzer.toLowerCase(Locale.ROOT).contains("heuristic");
    }

    /** Stable identity used for de-duplication across analyzers. */
    public String fingerprint() {
        return analyzer + '|' + ruleId + '|' + file + '|' + line + '|' + message;
    }
}
