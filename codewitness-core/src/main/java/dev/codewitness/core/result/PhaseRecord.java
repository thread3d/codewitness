package dev.codewitness.core.result;

import java.time.Duration;
import java.util.List;

/**
 * What happened during one phase of the verification pipeline.
 *
 * @param phase       phase identifier, for example {@code build}
 * @param executed    whether the phase ran at all
 * @param success     whether it completed without infrastructure failure
 * @param duration    wall-clock duration
 * @param summary     one line summary for the console and the report
 * @param evidenceIds evidence produced by the phase
 * @param note        extra detail, including why a phase was skipped
 */
public record PhaseRecord(
        String phase,
        boolean executed,
        boolean success,
        Duration duration,
        String summary,
        List<String> evidenceIds,
        String note) {

    public PhaseRecord {
        evidenceIds = List.copyOf(evidenceIds == null ? List.of() : evidenceIds);
    }
}
