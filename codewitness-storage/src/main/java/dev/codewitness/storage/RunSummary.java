package dev.codewitness.storage;

import dev.codewitness.core.requirements.RequirementStatus;
import dev.codewitness.core.result.RunStatus;

import java.time.Instant;

/**
 * One row of verification history, as returned by {@link VerificationStore#listRuns(int)}.
 *
 * <p>This is the shape the {@code codewitness runs} style listing needs: which run happened, against
 * which project, how it ended and when. It deliberately carries no evidence so that listing a long
 * history stays cheap; use {@link VerificationStore#findRun(String)} for the full record.
 *
 * @param runId          unique run identifier, for example {@code CW-2026-10-06-7F31A}
 * @param projectName    human readable project name, or {@code null} when the owning project row is
 *                       no longer present
 * @param status         outcome of the verification pipeline, or {@code null} when the report that
 *                       was stored carried no {@code VerificationRun}
 * @param overallStatus  overall requirement status derived from the run's verdicts; never
 *                       {@code null}
 * @param startedAt      run start, or {@code null} when unknown
 * @param finishedAt     run end, or {@code null} when the run never finished or the value is unknown
 */
public record RunSummary(
        String runId,
        String projectName,
        RunStatus status,
        RequirementStatus overallStatus,
        Instant startedAt,
        Instant finishedAt) {
}
