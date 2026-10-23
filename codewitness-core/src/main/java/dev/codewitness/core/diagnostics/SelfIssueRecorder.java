package dev.codewitness.core.diagnostics;

import java.util.Map;

/**
 * Sink for faults CodeWitness finds in itself.
 *
 * <p>Reporting is best effort by contract: a failure to write the journal must never replace the
 * original failure, change a requirement verdict, or make a run exit differently. Implementations
 * therefore swallow their own I/O errors; {@link SelfIssueJournal} keeps the last one for callers
 * that want to print a warning.
 */
@FunctionalInterface
public interface SelfIssueRecorder {

    /** A recorder that discards everything, used when no journal is configured. */
    SelfIssueRecorder NOOP = issue -> {
    };

    /**
     * Records one occurrence.
     *
     * @param issue issue to record; implementations must not throw
     */
    void record(SelfIssueRecord issue);

    /**
     * Builds and records an issue.
     *
     * @param category category of fault
     * @param severity severity of fault
     * @param message  one-line description
     * @param error    exception observed, or {@code null} for a declared limitation
     * @param context  optional fields such as {@link SelfIssueRecord#PHASE}
     */
    default void record(
            SelfIssueCategory category,
            SelfIssueSeverity severity,
            String message,
            Throwable error,
            Map<String, String> context) {
        record(SelfIssueRecord.create(category, severity, message, error, context));
    }
}
