package dev.codewitness.core.model;

import java.time.Instant;

/**
 * What was consulted for dependency advisories during a run, if anything.
 *
 * <p>This is deliberately separate from the findings. The important question about a report that
 * shows no vulnerable dependencies is not only "what was found" but "was anything looked for". A run
 * that never consulted an advisory source must be able to say so, instead of presenting an empty
 * result as a clean bill of health.
 *
 * @param performed   whether an advisory source was actually consulted
 * @param source      identifier of the source, for example {@code osv}; {@code none} when not consulted
 * @param endpoint    the service that was asked, recorded for reproducibility
 * @param performedAt when the query was made
 * @param note        what the scan covered, or why it did not happen
 */
public record AdvisoryScan(boolean performed, String source, String endpoint, Instant performedAt, String note) {

    public AdvisoryScan {
        source = source == null || source.isBlank() ? "none" : source;
        note = note == null ? "" : note;
    }

    public static AdvisoryScan notPerformed() {
        return notPerformed("no advisory source was consulted");
    }

    public static AdvisoryScan notPerformed(String note) {
        return new AdvisoryScan(false, "none", null, null, note);
    }

    public static AdvisoryScan performed(String source, String endpoint, Instant performedAt, String note) {
        return new AdvisoryScan(true, source, endpoint, performedAt, note);
    }
}
