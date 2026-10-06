package dev.codewitness.core.result;

import dev.codewitness.core.model.ProjectRef;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The durable record of one verification run.
 *
 * @param runId        unique run identifier
 * @param project      project that was verified
 * @param status       overall run status
 * @param phases       per-phase records, in execution order
 * @param environment  environment snapshot for reproducibility
 * @param budget       budget that applied to the run
 * @param usage        budget actually consumed
 * @param workspace    isolated workspace used for the run
 * @param reportJson   path of the canonical JSON report, when written
 * @param reportHtml   path of the HTML report, when written
 * @param startedAt    run start
 * @param finishedAt   run end
 * @param notes        free-form notes, for example limitations that affected the run
 */
public record VerificationRun(
        String runId,
        ProjectRef project,
        RunStatus status,
        List<PhaseRecord> phases,
        EnvironmentSnapshot environment,
        Budget budget,
        BudgetUsage usage,
        Path workspace,
        Path reportJson,
        Path reportHtml,
        Instant startedAt,
        Instant finishedAt,
        List<String> notes) {

    public VerificationRun {
        phases = List.copyOf(phases == null ? List.of() : phases);
        notes = List.copyOf(notes == null ? List.of() : notes);
    }

    public Optional<Path> jsonReport() {
        return Optional.ofNullable(reportJson);
    }

    public Optional<Path> htmlReport() {
        return Optional.ofNullable(reportHtml);
    }
}
