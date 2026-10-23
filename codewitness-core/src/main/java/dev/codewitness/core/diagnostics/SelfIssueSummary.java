package dev.codewitness.core.diagnostics;

/**
 * An aggregated view of every occurrence of one self-issue.
 *
 * <p>The journal keeps one row per occurrence so that recurrence is visible, but a human or an agent
 * wants one entry per fault. This record joins those rows by {@link SelfIssueRecord#id()} and keeps
 * the latest context, which is the one most likely to explain the current state of the code.
 *
 * @param id            stable identifier of the issue
 * @param severity      severity from the latest occurrence
 * @param category      category from the latest occurrence
 * @param message       message from the latest occurrence
 * @param errorType     exception class from the latest occurrence, or {@code null}
 * @param source        first actionable application frame from the latest occurrence, or {@code null}
 * @param firstSeen     earliest observation, ISO-8601
 * @param lastSeen      latest observation, ISO-8601
 * @param occurrences   number of observations
 * @param phase         phase from the latest occurrence, or {@code null}
 * @param strategy      strategy from the latest occurrence, or {@code null}
 * @param adapter       adapter from the latest occurrence, or {@code null}
 * @param requirement   requirement from the latest occurrence, or {@code null}
 * @param runId         run from the latest occurrence, or {@code null}
 * @param command       command from the latest occurrence, or {@code null}
 * @param project       project from the latest occurrence, or {@code null}
 * @param detail        stack trace from the latest occurrence, or {@code null}
 * @param resolved      whether a resolution was recorded after the latest occurrence
 * @param resolvedAt    ISO-8601 resolution time when resolved, otherwise {@code null}
 * @param resolutionNote note supplied when the issue was resolved, otherwise {@code null}
 */
public record SelfIssueSummary(
        String id,
        SelfIssueSeverity severity,
        SelfIssueCategory category,
        String message,
        String errorType,
        String source,
        String firstSeen,
        String lastSeen,
        int occurrences,
        String phase,
        String strategy,
        String adapter,
        String requirement,
        String runId,
        String command,
        String project,
        String detail,
        boolean resolved,
        String resolvedAt,
        String resolutionNote) {

    /** @return a stable sort rank; higher severity sorts first. */
    int severityRank() {
        return switch (severity) {
            case CRITICAL -> 4;
            case HIGH -> 3;
            case MEDIUM -> 2;
            case LOW -> 1;
        };
    }
}
