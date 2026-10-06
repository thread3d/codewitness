package dev.codewitness.storage;

import dev.codewitness.core.result.VerificationReport;

import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for verification runs.
 *
 * <p>A verification result that cannot be read back later is not evidence, so storage is part of the
 * evidence chain rather than a reporting convenience. Implementations must persist a whole report
 * atomically and must never silently drop data: any failure surfaces as a {@link StorageException}.
 *
 * <p>Implementations are expected to be safe for use from multiple threads, but not to be used after
 * {@link #close()}: every method then fails with a {@link StorageException}.
 */
public interface VerificationStore extends AutoCloseable {

    /**
     * Persists a complete verification run in a single transaction, replacing any previously stored
     * run with the same id.
     *
     * <p>Equivalent to {@code save(report, null)}: the canonical JSON report is not available.
     *
     * @param report report to persist
     * @throws StorageException when the run cannot be written
     */
    void save(VerificationReport report);

    /**
     * Persists a complete verification run in a single transaction, together with its canonical JSON
     * representation when the caller has one.
     *
     * <p>The JSON is stored verbatim so that a later report command can reproduce the exact document
     * that was produced by the run, rather than a re-rendering. It is optional: a store is fully
     * usable without it, and {@link #findReportJson(String)} then returns an empty result.
     *
     * <p>Saving a run id that already exists replaces the previous row and all of its dependent rows.
     *
     * @param report     report to persist
     * @param reportJson canonical JSON of the report, or {@code null} when unavailable
     * @throws StorageException when the run cannot be written
     */
    void save(VerificationReport report, String reportJson);

    /**
     * Reads a stored run back into core domain objects.
     *
     * @param runId run identifier
     * @return the stored run, or empty when no run with this id exists
     * @throws StorageException when the run exists but cannot be read
     */
    Optional<StoredRun> findRun(String runId);

    /**
     * Lists verification history, most recent first.
     *
     * <p>Ordering is by run start descending, with runs that have no recorded start time last, and is
     * deterministic for equal start times.
     *
     * @param limit maximum number of entries to return; a non-positive value yields an empty list
     * @return run summaries, most recent first
     * @throws StorageException when the history cannot be read
     */
    List<RunSummary> listRuns(int limit);

    /**
     * Returns the canonical JSON report stored with a run.
     *
     * @param runId run identifier
     * @return the JSON text supplied when the run was saved, or empty when the run is unknown or was
     *         saved without JSON
     * @throws StorageException when the run cannot be read
     */
    Optional<String> findReportJson(String runId);

    /**
     * Releases the underlying database connection. Calling this more than once is harmless, but any
     * other operation afterwards fails with a {@link StorageException}.
     */
    @Override
    void close();
}
