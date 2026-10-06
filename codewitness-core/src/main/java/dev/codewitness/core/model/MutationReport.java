package dev.codewitness.core.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Result of mutation testing.
 *
 * <p>The mutation score is only meaningful when mutation testing actually ran. When it did not run
 * ({@code measured == false}) the score is reported as unavailable and no requirement may be marked
 * {@code VERIFIED} on the strength of it.
 */
public record MutationReport(
        boolean measured,
        String tool,
        String note,
        int generated,
        int killed,
        int survived,
        int noCoverage,
        int timedOut,
        List<SurvivingMutant> survivors,
        List<Path> reports) {

    public MutationReport {
        survivors = List.copyOf(survivors == null ? List.of() : survivors);
        reports = List.copyOf(reports == null ? List.of() : reports);
    }

    public static MutationReport notMeasured(String tool, String note) {
        return new MutationReport(false, tool, note, 0, 0, 0, 0, 0, List.of(), List.of());
    }

    /**
     * Mutation score following the PIT convention: killed mutants over all mutants that were
     * covered by tests. Mutants with no coverage are excluded because they say something about
     * coverage rather than about test strength; they are reported separately.
     */
    public double scorePercent() {
        int covered = killed + survived + timedOut;
        return covered == 0 ? 0.0 : (100.0 * killed / covered);
    }

    public String describe() {
        if (!measured) {
            return "mutation testing not performed (" + note + ")";
        }
        return String.format(Locale.ROOT, "%d mutants, %d killed, %d survived, %d no coverage, score %.1f%%",
                generated, killed, survived, noCoverage, scorePercent());
    }
}
