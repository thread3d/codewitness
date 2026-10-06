package dev.codewitness.storage;

import dev.codewitness.core.evidence.Evidence;
import dev.codewitness.core.evidence.EvidenceConfidence;
import dev.codewitness.core.evidence.EvidenceStatus;
import dev.codewitness.core.evidence.EvidenceType;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.DependencyVulnerability;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.ModuleInfo;
import dev.codewitness.core.model.MutantStatus;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.model.SurvivingMutant;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.requirements.ObligationKind;
import dev.codewitness.core.requirements.ObligationOrigin;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.RequirementProperty;
import dev.codewitness.core.requirements.RequirementSet;
import dev.codewitness.core.requirements.RequirementStatus;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.result.Budget;
import dev.codewitness.core.result.BudgetUsage;
import dev.codewitness.core.result.Counterexample;
import dev.codewitness.core.result.EnvironmentSnapshot;
import dev.codewitness.core.result.ObligationOutcome;
import dev.codewitness.core.result.PhaseRecord;
import dev.codewitness.core.result.RequirementVerdict;
import dev.codewitness.core.result.RunStatus;
import dev.codewitness.core.result.VerificationReport;
import dev.codewitness.core.result.VerificationRun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * SQLite backed {@link VerificationStore}.
 *
 * <p>The database is a single file that the caller names. The constructor creates the parent
 * directory, opens the connection, enables foreign key enforcement and creates the schema when it is
 * absent; every statement is idempotent, so opening the same file repeatedly is safe.
 *
 * <p>Schema: one row in {@code projects} per project, one row in {@code verification_runs} per run,
 * and dependent rows in {@code requirements}, {@code obligations}, {@code evidence},
 * {@code test_results} (+ {@code test_cases}), {@code findings}, {@code counterexamples} and
 * {@code tool_runs}. Complex structures that have no relational shape - property maps, evidence
 * inputs and metadata, counterexample inputs, per-class coverage - are stored as JSON text written by
 * {@link Json}. All SQL is prepared; values are never concatenated into statements.
 *
 * <p>Saving is atomic: a run and every dependent row are written in one transaction, and a failure
 * rolls the whole transaction back before a {@link StorageException} is raised. Saving an existing
 * run id replaces the previous contents of that run rather than failing.
 *
 * <p>Instances own one JDBC connection and serialise access to it, so a single store may be shared
 * between threads. {@link #close()} releases the connection.
 */
public final class SqliteVerificationStore implements VerificationStore {

    private static final String JDBC_URL_PREFIX = "jdbc:sqlite:";

    /**
     * DDL executed on open. Every statement uses {@code IF NOT EXISTS} so that opening an existing
     * database is a no-op and so that a partially created schema is completed on the next open.
     */
    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS projects (
                project_id            TEXT PRIMARY KEY,
                name                  TEXT NOT NULL,
                root                  TEXT NOT NULL,
                language              TEXT NOT NULL DEFAULT 'unknown',
                build_system          TEXT NOT NULL DEFAULT 'none',
                build_root            TEXT,
                java_version          TEXT,
                test_frameworks_json  TEXT NOT NULL DEFAULT '[]',
                attributes_json       TEXT NOT NULL DEFAULT '{}',
                descriptors_json      TEXT NOT NULL DEFAULT '[]',
                modules_json          TEXT NOT NULL DEFAULT '[]',
                created_at            TEXT NOT NULL,
                updated_at            TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS verification_runs (
                run_id                                 TEXT PRIMARY KEY,
                project_id                             TEXT REFERENCES projects(project_id) ON DELETE SET NULL,
                status                                 TEXT,
                overall_status                         TEXT NOT NULL,
                started_at                             TEXT,
                finished_at                            TEXT,
                workspace                              TEXT,
                report_json_path                       TEXT,
                report_html_path                       TEXT,
                isolation_level                        TEXT,
                env_codewitness_version                TEXT,
                env_java_version                       TEXT,
                env_java_vendor                        TEXT,
                env_operating_system                   TEXT,
                env_build_tool                         TEXT,
                env_build_tool_version                 TEXT,
                env_source_revision                    TEXT,
                env_requirements_hash                  TEXT,
                env_configuration_hash                 TEXT,
                env_llm_model                          TEXT,
                env_sandbox_description                TEXT,
                env_project_path                       TEXT,
                env_started_at                         TEXT,
                env_finished_at                        TEXT,
                budget_max_duration_ms                 INTEGER,
                budget_max_test_generation_iterations  INTEGER,
                budget_max_generated_tests             INTEGER,
                budget_max_mutation_time_ms            INTEGER,
                budget_max_llm_calls                   INTEGER,
                usage_elapsed_ms                       INTEGER,
                usage_test_generation_iterations       INTEGER,
                usage_generated_tests                  INTEGER,
                usage_llm_calls                        INTEGER,
                usage_exhausted                        INTEGER,
                usage_exhausted_reason                 TEXT,
                notes_json                             TEXT NOT NULL DEFAULT '[]',
                limitations_json                       TEXT NOT NULL DEFAULT '[]',
                build_json                             TEXT,
                coverage_json                          TEXT,
                mutation_json                          TEXT,
                dependencies_json                      TEXT,
                report_json                            TEXT,
                created_at                             TEXT NOT NULL,
                updated_at                             TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS requirements (
                run_id                  TEXT NOT NULL REFERENCES verification_runs(run_id) ON DELETE CASCADE,
                requirement_id          TEXT NOT NULL,
                ordinal                 INTEGER NOT NULL,
                title                   TEXT,
                description             TEXT NOT NULL,
                source_file             TEXT,
                source_line             INTEGER,
                content_hash            TEXT,
                attributes_json         TEXT NOT NULL DEFAULT '{}',
                properties_json         TEXT NOT NULL DEFAULT '[]',
                status                  TEXT,
                rationale               TEXT,
                evidence_ids_json       TEXT NOT NULL DEFAULT '[]',
                unknown_aspects_json    TEXT NOT NULL DEFAULT '[]',
                counterexample_ids_json TEXT NOT NULL DEFAULT '[]',
                deterministic_evidence  INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (run_id, requirement_id)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS obligations (
                run_id                  TEXT NOT NULL REFERENCES verification_runs(run_id) ON DELETE CASCADE,
                obligation_id           TEXT NOT NULL,
                requirement_id          TEXT NOT NULL,
                ordinal                 INTEGER NOT NULL,
                description             TEXT NOT NULL,
                kind                    TEXT NOT NULL,
                origin                  TEXT NOT NULL,
                target                  TEXT,
                parameters_json         TEXT NOT NULL DEFAULT '{}',
                advisory_only           INTEGER NOT NULL DEFAULT 0,
                status                  TEXT,
                rationale               TEXT,
                evidence_ids_json       TEXT NOT NULL DEFAULT '[]',
                counterexample_ids_json TEXT NOT NULL DEFAULT '[]',
                attempts                INTEGER NOT NULL DEFAULT 0
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS evidence (
                run_id                   TEXT NOT NULL REFERENCES verification_runs(run_id) ON DELETE CASCADE,
                evidence_id              TEXT NOT NULL,
                type                     TEXT NOT NULL,
                status                   TEXT,
                confidence               TEXT,
                description              TEXT,
                source                   TEXT,
                timestamp                TEXT,
                command_json             TEXT NOT NULL DEFAULT '[]',
                inputs_json              TEXT NOT NULL DEFAULT '{}',
                output                   TEXT,
                result                   TEXT,
                metadata_json            TEXT NOT NULL DEFAULT '{}',
                artifacts_json           TEXT NOT NULL DEFAULT '[]',
                requirement_ids_json     TEXT NOT NULL DEFAULT '[]',
                obligation_ids_json      TEXT NOT NULL DEFAULT '[]',
                generated_by_llm         INTEGER NOT NULL DEFAULT 0,
                executed_independently   INTEGER NOT NULL DEFAULT 1,
                reproducible             INTEGER NOT NULL DEFAULT 1,
                reproduction_command     TEXT,
                PRIMARY KEY (run_id, evidence_id)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS test_results (
                run_id      TEXT PRIMARY KEY REFERENCES verification_runs(run_id) ON DELETE CASCADE,
                executed    INTEGER NOT NULL,
                note        TEXT,
                total       INTEGER NOT NULL DEFAULT 0,
                passed      INTEGER NOT NULL DEFAULT 0,
                failed      INTEGER NOT NULL DEFAULT 0,
                errors      INTEGER NOT NULL DEFAULT 0,
                skipped     INTEGER NOT NULL DEFAULT 0,
                duration_ms INTEGER,
                reports_json TEXT NOT NULL DEFAULT '[]'
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS test_cases (
                run_id          TEXT NOT NULL REFERENCES test_results(run_id) ON DELETE CASCADE,
                ordinal         INTEGER NOT NULL,
                suite           TEXT NOT NULL,
                name            TEXT NOT NULL,
                outcome         TEXT NOT NULL,
                duration_ms     INTEGER,
                failure_type    TEXT,
                failure_message TEXT,
                stack_trace     TEXT,
                PRIMARY KEY (run_id, ordinal)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS findings (
                run_id          TEXT NOT NULL REFERENCES verification_runs(run_id) ON DELETE CASCADE,
                ordinal         INTEGER NOT NULL,
                analyzer        TEXT NOT NULL,
                rule_id         TEXT,
                severity        TEXT NOT NULL,
                category        TEXT NOT NULL,
                message         TEXT NOT NULL,
                file            TEXT,
                line            INTEGER,
                module          TEXT,
                attributes_json TEXT NOT NULL DEFAULT '{}',
                fingerprint     TEXT,
                PRIMARY KEY (run_id, ordinal)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS counterexamples (
                run_id              TEXT NOT NULL REFERENCES verification_runs(run_id) ON DELETE CASCADE,
                counterexample_id   TEXT NOT NULL,
                requirement_id      TEXT,
                obligation_id       TEXT,
                summary             TEXT,
                inputs_json         TEXT NOT NULL DEFAULT '{}',
                expected            TEXT,
                actual              TEXT,
                invariant_violated  TEXT,
                observed_by         TEXT,
                evidence_id         TEXT,
                seed                INTEGER,
                reproduction_json   TEXT,
                discovered_by       TEXT,
                PRIMARY KEY (run_id, counterexample_id)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS tool_runs (
                run_id            TEXT NOT NULL REFERENCES verification_runs(run_id) ON DELETE CASCADE,
                ordinal           INTEGER NOT NULL,
                phase             TEXT NOT NULL,
                status            TEXT NOT NULL,
                executed          INTEGER NOT NULL,
                success           INTEGER NOT NULL,
                duration_ms       INTEGER,
                summary           TEXT,
                note              TEXT,
                evidence_ids_json TEXT NOT NULL DEFAULT '[]',
                PRIMARY KEY (run_id, ordinal)
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_verification_runs_started ON verification_runs(started_at)",
            "CREATE INDEX IF NOT EXISTS idx_verification_runs_project ON verification_runs(project_id)",
            "CREATE INDEX IF NOT EXISTS idx_requirements_requirement ON requirements(requirement_id)",
            "CREATE INDEX IF NOT EXISTS idx_obligations_obligation ON obligations(obligation_id)",
            "CREATE INDEX IF NOT EXISTS idx_evidence_evidence ON evidence(evidence_id)",
            "CREATE INDEX IF NOT EXISTS idx_counterexamples_requirement ON counterexamples(requirement_id)");

    private final Path databaseFile;
    private final Connection connection;
    private final Object lock = new Object();
    private boolean closed;

    /**
     * Opens (creating when necessary) the verification database at the given path.
     *
     * <p>Missing parent directories are created. The schema is created when absent and foreign key
     * enforcement is enabled for the connection.
     *
     * @param databaseFile database file; {@code :memory:} style URLs are not accepted, use a real path
     * @throws StorageException when directories cannot be created, the driver is unavailable or the
     *                          schema cannot be applied
     */
    public SqliteVerificationStore(Path databaseFile) {
        this.databaseFile = Objects.requireNonNull(databaseFile, "databaseFile").toAbsolutePath().normalize();
        Connection opened = null;
        try {
            Path parent = this.databaseFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            opened = DriverManager.getConnection(JDBC_URL_PREFIX + this.databaseFile);
            configure(opened);
            createSchema(opened);
            this.connection = opened;
        } catch (SQLException | IOException e) {
            closeQuietly(opened);
            throw new StorageException("failed to open verification database " + this.databaseFile, e);
        }
    }

    /** @return the absolute path of the database file backing this store. */
    public Path databaseFile() {
        return databaseFile;
    }

    @Override
    public void save(VerificationReport report) {
        save(report, null);
    }

    @Override
    public void save(VerificationReport report, String reportJson) {
        Objects.requireNonNull(report, "report");
        String runId = report.runId();
        if (runId == null || runId.isBlank()) {
            throw new StorageException("cannot persist a verification report without a run id");
        }
        synchronized (lock) {
            requireOpen();
            ProjectRef projectRef = resolveProjectRef(report);
            ProjectInfo projectInfo = report.project() != null
                    ? report.project()
                    : ProjectInfo.builder(projectRef).build();
            inTransaction("failed to persist verification run " + runId, () -> {
                upsertProject(projectInfo);
                String createdAt = existingCreatedAt(runId);
                deleteRun(runId);
                insertRun(report, projectInfo.project(), reportJson, createdAt);
                insertRequirements(report);
                insertObligations(report);
                insertEvidence(report);
                insertTests(report);
                insertFindings(report);
                insertCounterexamples(report);
                insertToolRuns(report);
            });
        }
    }

    @Override
    public Optional<StoredRun> findRun(String runId) {
        Objects.requireNonNull(runId, "runId");
        synchronized (lock) {
            requireOpen();
            try {
                return readRun(runId);
            } catch (SQLException e) {
                throw new StorageException("failed to read verification run " + runId, e);
            } catch (IllegalArgumentException e) {
                throw new StorageException("stored data for verification run " + runId + " is corrupt", e);
            }
        }
    }

    @Override
    public List<RunSummary> listRuns(int limit) {
        synchronized (lock) {
            requireOpen();
            if (limit <= 0) {
                return List.of();
            }
            String sql = """
                    SELECT r.run_id AS run_id,
                           p.name AS project_name,
                           r.status AS status,
                           r.overall_status AS overall_status,
                           r.started_at AS started_at,
                           r.finished_at AS finished_at
                    FROM verification_runs r
                    LEFT JOIN projects p ON p.project_id = r.project_id
                    ORDER BY COALESCE(r.started_at, '') DESC, r.run_id DESC
                    LIMIT ?
                    """;
            try {
                List<RunSummary> summaries = new ArrayList<>();
                for (Map<String, Object> row : query(sql, limit)) {
                    summaries.add(new RunSummary(
                            Json.string(row, "run_id"),
                            Json.string(row, "project_name"),
                            enumValue(RunStatus.class, Json.string(row, "status")),
                            overallStatus(Json.string(row, "overall_status")),
                            instant(Json.string(row, "started_at")),
                            instant(Json.string(row, "finished_at"))));
                }
                return List.copyOf(summaries);
            } catch (SQLException e) {
                throw new StorageException("failed to list verification runs", e);
            }
        }
    }

    @Override
    public Optional<String> findReportJson(String runId) {
        Objects.requireNonNull(runId, "runId");
        synchronized (lock) {
            requireOpen();
            try {
                List<Map<String, Object>> rows = query(
                        "SELECT report_json FROM verification_runs WHERE run_id = ?", runId);
                if (rows.isEmpty()) {
                    return Optional.empty();
                }
                return Optional.ofNullable(Json.string(rows.getFirst(), "report_json"));
            } catch (SQLException e) {
                throw new StorageException("failed to read report JSON for run " + runId, e);
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            try {
                connection.close();
            } catch (SQLException e) {
                throw new StorageException("failed to close verification database " + databaseFile, e);
            }
        }
    }

    // ------------------------------------------------------------------ schema

    private static void configure(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = NORMAL");
        }
    }

    private static void createSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String ddl : SCHEMA) {
                statement.execute(ddl);
            }
        }
    }

    // ------------------------------------------------------------- writes

    private void upsertProject(ProjectInfo project) throws SQLException {
        String sql = """
                INSERT INTO projects (
                    project_id, name, root, language, build_system, build_root, java_version,
                    test_frameworks_json, attributes_json, descriptors_json, modules_json,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(project_id) DO UPDATE SET
                    name = excluded.name,
                    root = excluded.root,
                    language = excluded.language,
                    build_system = excluded.build_system,
                    build_root = excluded.build_root,
                    java_version = excluded.java_version,
                    test_frameworks_json = excluded.test_frameworks_json,
                    attributes_json = excluded.attributes_json,
                    descriptors_json = excluded.descriptors_json,
                    modules_json = excluded.modules_json,
                    updated_at = excluded.updated_at
                """;
        Instant now = Instant.now();
        ProjectRef ref = project.project();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, ref.identifier());
            ps.setString(i++, ref.name());
            ps.setString(i++, ref.root().toString());
            ps.setString(i++, project.language().id());
            ps.setString(i++, project.buildSystem().id());
            ps.setString(i++, project.buildRoot() == null ? null : project.buildRoot().toString());
            ps.setString(i++, project.javaVersion().orElse(null));
            ps.setString(i++, Json.write(List.copyOf(project.testFrameworks())));
            ps.setString(i++, Json.write(project.attributes()));
            ps.setString(i++, Json.write(project.detectedDescriptors()));
            ps.setString(i++, Json.write(moduleMaps(project.modules())));
            ps.setString(i++, now.toString());
            ps.setString(i++, now.toString());
            ps.executeUpdate();
        }
    }

    private String existingCreatedAt(String runId) throws SQLException {
        List<Map<String, Object>> rows = query(
                "SELECT created_at FROM verification_runs WHERE run_id = ?", runId);
        return rows.isEmpty() ? null : Json.string(rows.getFirst(), "created_at");
    }

    private void deleteRun(String runId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM verification_runs WHERE run_id = ?")) {
            ps.setString(1, runId);
            ps.executeUpdate();
        }
    }

    private void insertRun(VerificationReport report, ProjectRef projectRef, String reportJson,
            String createdAt) throws SQLException {
        String sql = """
                INSERT INTO verification_runs (
                    run_id, project_id, status, overall_status, started_at, finished_at,
                    workspace, report_json_path, report_html_path, isolation_level,
                    env_codewitness_version, env_java_version, env_java_vendor, env_operating_system,
                    env_build_tool, env_build_tool_version, env_source_revision, env_requirements_hash,
                    env_configuration_hash, env_llm_model,
                    env_sandbox_description, env_project_path, env_started_at, env_finished_at,
                    budget_max_duration_ms, budget_max_test_generation_iterations,
                    budget_max_generated_tests, budget_max_mutation_time_ms, budget_max_llm_calls,
                    usage_elapsed_ms, usage_test_generation_iterations, usage_generated_tests,
                    usage_llm_calls, usage_exhausted, usage_exhausted_reason,
                    notes_json, limitations_json, build_json, coverage_json, mutation_json,
                    dependencies_json, report_json, created_at, updated_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?
                )
                """;
        VerificationRun run = report.run();
        EnvironmentSnapshot environment = run == null ? null : run.environment();
        Budget budget = run == null ? null : run.budget();
        BudgetUsage usage = run == null ? null : run.usage();
        Instant startedAt = run == null ? null : run.startedAt();
        Instant finishedAt = run == null ? null : run.finishedAt();
        if (startedAt == null && environment != null) {
            startedAt = environment.startedAt();
        }
        if (finishedAt == null && environment != null) {
            finishedAt = environment.finishedAt();
        }
        Instant now = Instant.now();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, report.runId());
            ps.setString(i++, projectRef == null ? null : projectRef.identifier());
            setStringOrNull(ps, i++, run == null || run.status() == null ? null : run.status().name());
            ps.setString(i++, report.overallStatus().name());
            setStringOrNull(ps, i++, startedAt == null ? null : startedAt.toString());
            setStringOrNull(ps, i++, finishedAt == null ? null : finishedAt.toString());
            setStringOrNull(ps, i++, path(run == null ? null : run.workspace()));
            setStringOrNull(ps, i++, path(run == null ? null : run.reportJson()));
            setStringOrNull(ps, i++, path(run == null ? null : run.reportHtml()));
            setStringOrNull(ps, i++, environment == null ? null : environment.isolationLevel());
            setStringOrNull(ps, i++, environment == null ? null : environment.codewitnessVersion());
            setStringOrNull(ps, i++, environment == null ? null : environment.javaVersion());
            setStringOrNull(ps, i++, environment == null ? null : environment.javaVendor());
            setStringOrNull(ps, i++, environment == null ? null : environment.operatingSystem());
            setStringOrNull(ps, i++, environment == null ? null : environment.buildTool());
            setStringOrNull(ps, i++, environment == null ? null : environment.buildToolVersion());
            setStringOrNull(ps, i++, environment == null ? null : environment.sourceRevision());
            setStringOrNull(ps, i++, environment == null ? null : environment.requirementsHash());
            setStringOrNull(ps, i++, environment == null ? null : environment.configurationHash());
            setStringOrNull(ps, i++, environment == null ? null : environment.llmModel());
            setStringOrNull(ps, i++, environment == null ? null : environment.sandboxDescription());
            setStringOrNull(ps, i++, environment == null ? null : environment.projectPath());
            setStringOrNull(ps, i++, environment == null || environment.startedAt() == null
                    ? null : environment.startedAt().toString());
            setStringOrNull(ps, i++, environment == null || environment.finishedAt() == null
                    ? null : environment.finishedAt().toString());
            if (budget == null) {
                ps.setNull(i++, Types.INTEGER);
                ps.setNull(i++, Types.INTEGER);
                ps.setNull(i++, Types.INTEGER);
                ps.setNull(i++, Types.INTEGER);
                ps.setNull(i++, Types.INTEGER);
            } else {
                ps.setLong(i++, budget.maxDuration().toMillis());
                ps.setInt(i++, budget.maxTestGenerationIterations());
                ps.setInt(i++, budget.maxGeneratedTests());
                ps.setLong(i++, budget.maxMutationTime().toMillis());
                ps.setInt(i++, budget.maxLlmCalls());
            }
            if (usage == null) {
                ps.setNull(i++, Types.INTEGER);
                ps.setNull(i++, Types.INTEGER);
                ps.setNull(i++, Types.INTEGER);
                ps.setNull(i++, Types.INTEGER);
                ps.setNull(i++, Types.INTEGER);
                ps.setNull(i++, Types.VARCHAR);
            } else {
                ps.setLong(i++, usage.elapsed().toMillis());
                ps.setInt(i++, usage.testGenerationIterations());
                ps.setInt(i++, usage.generatedTests());
                ps.setInt(i++, usage.llmCalls());
                ps.setInt(i++, usage.exhausted() ? 1 : 0);
                setStringOrNull(ps, i++, usage.exhaustedReason());
            }
            ps.setString(i++, Json.write(run == null ? List.of() : run.notes()));
            ps.setString(i++, Json.write(report.limitations()));
            setStringOrNull(ps, i++, jsonOrNull(buildMap(report.build())));
            setStringOrNull(ps, i++, jsonOrNull(coverageMap(report.coverage())));
            setStringOrNull(ps, i++, jsonOrNull(mutationMap(report.mutation())));
            setStringOrNull(ps, i++, jsonOrNull(dependencyMap(report.dependencies())));
            setStringOrNull(ps, i++, reportJson);
            ps.setString(i++, createdAt == null ? now.toString() : createdAt);
            ps.setString(i++, now.toString());
            ps.executeUpdate();
        }
    }

    private void insertRequirements(VerificationReport report) throws SQLException {
        RequirementSet requirements = report.requirements();
        if (requirements == null || requirements.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO requirements (
                    run_id, requirement_id, ordinal, title, description, source_file, source_line,
                    content_hash, attributes_json, properties_json, status, rationale,
                    evidence_ids_json, unknown_aspects_json, counterexample_ids_json,
                    deterministic_evidence
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int ordinal = 0;
            for (Requirement requirement : requirements.requirements()) {
                RequirementVerdict verdict = report.verdict(requirement.id()).orElse(null);
                int i = 1;
                ps.setString(i++, report.runId());
                ps.setString(i++, requirement.id());
                ps.setInt(i++, ordinal++);
                setStringOrNull(ps, i++, requirement.title());
                ps.setString(i++, requirement.description());
                setStringOrNull(ps, i++, requirement.sourceFile());
                ps.setInt(i++, requirement.sourceLine());
                setStringOrNull(ps, i++, requirement.contentHash());
                ps.setString(i++, Json.write(requirement.attributes()));
                ps.setString(i++, Json.write(propertyMaps(requirement.properties())));
                if (verdict == null) {
                    ps.setNull(i++, Types.VARCHAR);
                    ps.setNull(i++, Types.VARCHAR);
                    ps.setString(i++, "[]");
                    ps.setString(i++, "[]");
                    ps.setString(i++, "[]");
                    ps.setInt(i++, 0);
                } else {
                    ps.setString(i++, verdict.status() == null ? null : verdict.status().name());
                    setStringOrNull(ps, i++, verdict.rationale());
                    ps.setString(i++, Json.write(verdict.evidenceIds()));
                    ps.setString(i++, Json.write(verdict.unknownAspects()));
                    ps.setString(i++, Json.write(verdict.counterexampleIds()));
                    ps.setInt(i++, verdict.deterministicEvidence() ? 1 : 0);
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertObligations(VerificationReport report) throws SQLException {
        RequirementSet requirements = report.requirements();
        Map<String, VerificationObligation> obligations = new LinkedHashMap<>();
        if (requirements != null) {
            for (Requirement requirement : requirements.requirements()) {
                for (VerificationObligation obligation : requirement.obligations()) {
                    obligations.putIfAbsent(obligation.id(), obligation);
                }
            }
        }
        Map<String, ObligationOutcome> outcomes = new LinkedHashMap<>();
        for (RequirementVerdict verdict : report.verdicts()) {
            for (ObligationOutcome outcome : verdict.obligations()) {
                outcomes.putIfAbsent(outcome.obligationId(), outcome);
                obligations.computeIfAbsent(outcome.obligationId(), id -> new VerificationObligation(
                        id,
                        verdict.requirementId() == null ? "" : verdict.requirementId(),
                        outcome.rationale() == null ? "" : outcome.rationale(),
                        ObligationKind.GENERIC,
                        ObligationOrigin.ANALYZER_DERIVED,
                        null,
                        Map.of(),
                        false));
            }
        }
        if (obligations.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO obligations (
                    run_id, obligation_id, requirement_id, ordinal, description, kind, origin,
                    target, parameters_json, advisory_only, status, rationale, evidence_ids_json,
                    counterexample_ids_json, attempts
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int ordinal = 0;
            for (VerificationObligation obligation : obligations.values()) {
                ObligationOutcome outcome = outcomes.get(obligation.id());
                int i = 1;
                ps.setString(i++, report.runId());
                ps.setString(i++, obligation.id());
                ps.setString(i++, obligation.requirementId());
                ps.setInt(i++, ordinal++);
                setStringOrNull(ps, i++, obligation.description());
                ps.setString(i++, obligation.kind() == null ? null : obligation.kind().name());
                ps.setString(i++, obligation.origin() == null ? null : obligation.origin().name());
                setStringOrNull(ps, i++, obligation.target());
                ps.setString(i++, Json.write(obligation.parameters()));
                ps.setInt(i++, obligation.advisoryOnly() ? 1 : 0);
                if (outcome == null) {
                    ps.setNull(i++, Types.VARCHAR);
                    ps.setNull(i++, Types.VARCHAR);
                    ps.setString(i++, "[]");
                    ps.setString(i++, "[]");
                    ps.setInt(i++, 0);
                } else {
                    ps.setString(i++, outcome.status() == null ? null : outcome.status().name());
                    setStringOrNull(ps, i++, outcome.rationale());
                    ps.setString(i++, Json.write(outcome.evidenceIds()));
                    ps.setString(i++, Json.write(outcome.counterexampleIds()));
                    ps.setInt(i++, outcome.attempts());
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertEvidence(VerificationReport report) throws SQLException {
        if (report.evidence().isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO evidence (
                    run_id, evidence_id, type, status, confidence, description, source, timestamp,
                    command_json, inputs_json, output, result, metadata_json, artifacts_json,
                    requirement_ids_json, obligation_ids_json, generated_by_llm,
                    executed_independently, reproducible, reproduction_command
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (Evidence evidence : report.evidence()) {
                int i = 1;
                ps.setString(i++, report.runId());
                ps.setString(i++, evidence.id());
                ps.setString(i++, evidence.type() == null ? null : evidence.type().name());
                ps.setString(i++, evidence.status() == null ? null : evidence.status().name());
                ps.setString(i++, evidence.confidence() == null ? null : evidence.confidence().name());
                setStringOrNull(ps, i++, evidence.description());
                setStringOrNull(ps, i++, evidence.source());
                setStringOrNull(ps, i++, evidence.timestamp() == null ? null : evidence.timestamp().toString());
                ps.setString(i++, Json.write(evidence.command()));
                ps.setString(i++, Json.write(evidence.inputs()));
                setStringOrNull(ps, i++, evidence.output());
                setStringOrNull(ps, i++, evidence.result());
                ps.setString(i++, Json.write(evidence.metadata()));
                ps.setString(i++, Json.write(evidence.artifacts()));
                ps.setString(i++, Json.write(evidence.requirementIds()));
                ps.setString(i++, Json.write(evidence.obligationIds()));
                ps.setInt(i++, evidence.generatedByLlm() ? 1 : 0);
                ps.setInt(i++, evidence.executedIndependently() ? 1 : 0);
                ps.setInt(i++, evidence.reproducible() ? 1 : 0);
                setStringOrNull(ps, i++, evidence.reproductionCommand());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertTests(VerificationReport report) throws SQLException {
        TestResult tests = report.tests();
        if (tests == null) {
            return;
        }
        String resultSql = """
                INSERT INTO test_results (
                    run_id, executed, note, total, passed, failed, errors, skipped, duration_ms,
                    reports_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(resultSql)) {
            int i = 1;
            ps.setString(i++, report.runId());
            ps.setInt(i++, tests.executed() ? 1 : 0);
            setStringOrNull(ps, i++, tests.note());
            ps.setInt(i++, tests.total());
            ps.setInt(i++, tests.passed());
            ps.setInt(i++, tests.failed());
            ps.setInt(i++, tests.errors());
            ps.setInt(i++, tests.skipped());
            ps.setLong(i++, tests.duration().toMillis());
            ps.setString(i++, Json.write(tests.reports()));
            ps.executeUpdate();
        }
        if (tests.cases().isEmpty()) {
            return;
        }
        String caseSql = """
                INSERT INTO test_cases (
                    run_id, ordinal, suite, name, outcome, duration_ms, failure_type,
                    failure_message, stack_trace
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(caseSql)) {
            int ordinal = 0;
            for (TestCaseResult testCase : tests.cases()) {
                int i = 1;
                ps.setString(i++, report.runId());
                ps.setInt(i++, ordinal++);
                ps.setString(i++, testCase.suite());
                ps.setString(i++, testCase.name());
                ps.setString(i++, testCase.outcome() == null ? null : testCase.outcome().name());
                ps.setLong(i++, testCase.duration().toMillis());
                setStringOrNull(ps, i++, testCase.failureType());
                setStringOrNull(ps, i++, testCase.failureMessage());
                setStringOrNull(ps, i++, testCase.stackTrace());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertFindings(VerificationReport report) throws SQLException {
        if (report.findings().isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO findings (
                    run_id, ordinal, analyzer, rule_id, severity, category, message, file, line,
                    module, attributes_json, fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int ordinal = 0;
            for (Finding finding : report.findings()) {
                int i = 1;
                ps.setString(i++, report.runId());
                ps.setInt(i++, ordinal++);
                ps.setString(i++, finding.analyzer());
                setStringOrNull(ps, i++, finding.ruleId());
                ps.setString(i++, finding.severity() == null ? null : finding.severity().name());
                ps.setString(i++, finding.category() == null ? null : finding.category().name());
                ps.setString(i++, finding.message());
                setStringOrNull(ps, i++, finding.file());
                if (finding.line() == null) {
                    ps.setNull(i++, Types.INTEGER);
                } else {
                    ps.setInt(i++, finding.line());
                }
                setStringOrNull(ps, i++, finding.module());
                ps.setString(i++, Json.write(finding.attributes()));
                ps.setString(i++, finding.fingerprint());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertCounterexamples(VerificationReport report) throws SQLException {
        if (report.counterexamples().isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO counterexamples (
                    run_id, counterexample_id, requirement_id, obligation_id, summary, inputs_json,
                    expected, actual, invariant_violated, observed_by, evidence_id, seed,
                    reproduction_json, discovered_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (Counterexample counterexample : report.counterexamples()) {
                int i = 1;
                ps.setString(i++, report.runId());
                ps.setString(i++, counterexample.id());
                setStringOrNull(ps, i++, counterexample.requirementId());
                setStringOrNull(ps, i++, counterexample.obligationId());
                setStringOrNull(ps, i++, counterexample.summary());
                ps.setString(i++, Json.write(counterexample.inputs()));
                setStringOrNull(ps, i++, counterexample.expected());
                setStringOrNull(ps, i++, counterexample.actual());
                setStringOrNull(ps, i++, counterexample.invariantViolated());
                setStringOrNull(ps, i++, counterexample.observedBy());
                setStringOrNull(ps, i++, counterexample.evidenceId());
                if (counterexample.seed() == null) {
                    ps.setNull(i++, Types.INTEGER);
                } else {
                    ps.setLong(i++, counterexample.seed());
                }
                ps.setString(i++, counterexample.reproduction() == null
                        ? null : Json.write(reproductionMap(counterexample.reproduction())));
                setStringOrNull(ps, i++, counterexample.discoveredBy() == null
                        ? null : counterexample.discoveredBy().name());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertToolRuns(VerificationReport report) throws SQLException {
        VerificationRun run = report.run();
        if (run == null || run.phases().isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO tool_runs (
                    run_id, ordinal, phase, status, executed, success, duration_ms, summary, note,
                    evidence_ids_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int ordinal = 0;
            for (PhaseRecord phase : run.phases()) {
                int i = 1;
                ps.setString(i++, report.runId());
                ps.setInt(i++, ordinal++);
                ps.setString(i++, phase.phase());
                ps.setString(i++, phaseStatus(phase));
                ps.setInt(i++, phase.executed() ? 1 : 0);
                ps.setInt(i++, phase.success() ? 1 : 0);
                ps.setLong(i++, phase.duration() == null ? 0L : phase.duration().toMillis());
                setStringOrNull(ps, i++, phase.summary());
                setStringOrNull(ps, i++, phase.note());
                ps.setString(i++, Json.write(phase.evidenceIds()));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // -------------------------------------------------------------- reads

    private Optional<StoredRun> readRun(String runId) throws SQLException {
        List<Map<String, Object>> runRows = query(
                "SELECT * FROM verification_runs WHERE run_id = ?", runId);
        if (runRows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> runRow = runRows.getFirst();
        Map<String, Object> projectRow = readProjectRow(runRow);
        ProjectRef projectRef = storedProjectRef(runRow, projectRow, runId);
        ProjectInfo projectInfo = storedProjectInfo(projectRow, projectRef);

        List<Map<String, Object>> obligationRows = query(
                "SELECT * FROM obligations WHERE run_id = ? ORDER BY rowid", runId);
        Map<String, List<VerificationObligation>> obligationsByRequirement = new LinkedHashMap<>();
        Map<String, List<ObligationOutcome>> outcomesByRequirement = new LinkedHashMap<>();
        for (Map<String, Object> row : obligationRows) {
            String requirementId = Json.string(row, "requirement_id");
            obligationsByRequirement
                    .computeIfAbsent(requirementId, key -> new ArrayList<>())
                    .add(readObligation(row));
            if (Json.string(row, "status") != null) {
                outcomesByRequirement
                        .computeIfAbsent(requirementId, key -> new ArrayList<>())
                        .add(readOutcome(row));
            }
        }

        List<Requirement> requirements = new ArrayList<>();
        List<RequirementVerdict> verdicts = new ArrayList<>();
        for (Map<String, Object> row : query(
                "SELECT * FROM requirements WHERE run_id = ? ORDER BY ordinal", runId)) {
            String requirementId = Json.string(row, "requirement_id");
            List<VerificationObligation> obligations =
                    obligationsByRequirement.getOrDefault(requirementId, List.of());
            requirements.add(readRequirement(row, obligations));
            if (Json.string(row, "status") != null) {
                verdicts.add(readVerdict(row, outcomesByRequirement.getOrDefault(requirementId, List.of())));
            }
        }

        List<Evidence> evidence = new ArrayList<>();
        for (Map<String, Object> row : query(
                "SELECT * FROM evidence WHERE run_id = ? ORDER BY rowid", runId)) {
            evidence.add(readEvidence(row));
        }

        List<Counterexample> counterexamples = new ArrayList<>();
        for (Map<String, Object> row : query(
                "SELECT * FROM counterexamples WHERE run_id = ? ORDER BY rowid", runId)) {
            counterexamples.add(readCounterexample(row));
        }

        List<Finding> findings = new ArrayList<>();
        for (Map<String, Object> row : query(
                "SELECT * FROM findings WHERE run_id = ? ORDER BY ordinal", runId)) {
            findings.add(readFinding(row));
        }

        TestResult tests = readTests(runId);

        List<PhaseRecord> phases = new ArrayList<>();
        for (Map<String, Object> row : query(
                "SELECT * FROM tool_runs WHERE run_id = ? ORDER BY ordinal", runId)) {
            phases.add(readPhase(row));
        }

        VerificationRun run = readRunRecord(runRow, projectRef, phases);

        return Optional.of(new StoredRun(
                runId,
                projectRef,
                projectInfo,
                run,
                overallStatus(Json.string(runRow, "overall_status")),
                new RequirementSet(requirements),
                verdicts,
                evidence,
                counterexamples,
                tests,
                findings,
                readBuild(Json.string(runRow, "build_json")),
                readCoverage(Json.string(runRow, "coverage_json")),
                readMutation(Json.string(runRow, "mutation_json")),
                readDependencies(Json.string(runRow, "dependencies_json")),
                Json.strings(Json.parse(Json.string(runRow, "limitations_json"))),
                Json.string(runRow, "report_json")));
    }

    private Map<String, Object> readProjectRow(Map<String, Object> runRow) throws SQLException {
        String projectId = Json.string(runRow, "project_id");
        if (projectId == null) {
            return null;
        }
        List<Map<String, Object>> rows = query("SELECT * FROM projects WHERE project_id = ?", projectId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private TestResult readTests(String runId) throws SQLException {
        List<Map<String, Object>> rows = query(
                "SELECT * FROM test_results WHERE run_id = ?", runId);
        if (rows.isEmpty()) {
            return TestResult.notExecuted("not stored");
        }
        Map<String, Object> row = rows.getFirst();
        List<TestCaseResult> cases = new ArrayList<>();
        for (Map<String, Object> caseRow : query(
                "SELECT * FROM test_cases WHERE run_id = ? ORDER BY ordinal", runId)) {
            cases.add(readTestCase(caseRow));
        }
        return new TestResult(
                Json.integer(row, "executed", 0) != 0,
                Json.string(row, "note"),
                Json.integer(row, "total", 0),
                Json.integer(row, "passed", 0),
                Json.integer(row, "failed", 0),
                Json.integer(row, "errors", 0),
                Json.integer(row, "skipped", 0),
                Duration.ofMillis(Json.longValue(row, "duration_ms", 0L)),
                cases,
                paths(Json.strings(Json.parse(Json.string(row, "reports_json")))));
    }

    // --------------------------------------------------- domain reconstruction

    /**
     * Resolves the project reference for a stored run.
     *
     * <p>A run records the exact project path it verified in its environment snapshot. That path wins
     * over the project row, because one project identifier can be shared by several checkouts and the
     * row always describes the most recent one. The project row can also disappear through an external
     * delete (the run's foreign key is {@code ON DELETE SET NULL}); the recorded path is then the only
     * remaining source, so the run stays readable instead of failing.
     */
    private static ProjectRef storedProjectRef(Map<String, Object> runRow, Map<String, Object> projectRow,
            String runId) {
        String projectId = Json.string(runRow, "project_id");
        Path recordedRoot = recordedProjectPath(runRow);
        if (projectRow != null) {
            Path storedRoot = path(Json.string(projectRow, "root"));
            return new ProjectRef(
                    recordedRoot == null ? storedRoot : recordedRoot,
                    Json.string(projectRow, "name", projectId == null ? runId : projectId),
                    projectId == null ? runId : projectId);
        }
        ProjectRef fallback = ProjectRef.of(recordedRoot == null ? Path.of(".") : recordedRoot);
        return new ProjectRef(fallback.root(), fallback.name(),
                projectId == null ? fallback.identifier() : projectId);
    }

    private static Path recordedProjectPath(Map<String, Object> runRow) {
        String recordedPath = Json.string(runRow, "env_project_path");
        if (recordedPath == null || recordedPath.isBlank() || "unknown".equals(recordedPath)) {
            return null;
        }
        return path(recordedPath);
    }

    private static ProjectInfo storedProjectInfo(Map<String, Object> projectRow, ProjectRef ref) {
        if (projectRow == null) {
            return ProjectInfo.builder(ref).build();
        }
        ProjectInfo.Builder builder = ProjectInfo.builder(ref)
                .language(LanguageId.of(Json.string(projectRow, "language", "unknown")))
                .buildSystem(BuildSystemId.of(Json.string(projectRow, "build_system", "none")));
        String buildRoot = Json.string(projectRow, "build_root");
        if (buildRoot != null) {
            builder.buildRoot(path(buildRoot));
        }
        String javaVersion = Json.string(projectRow, "java_version");
        if (javaVersion != null) {
            builder.javaVersion(javaVersion);
        }
        for (String framework : Json.strings(Json.parse(Json.string(projectRow, "test_frameworks_json")))) {
            builder.addTestFramework(framework);
        }
        for (Map.Entry<String, String> entry
                : Json.stringMap(Json.parse(Json.string(projectRow, "attributes_json"))).entrySet()) {
            builder.attribute(entry.getKey(), entry.getValue());
        }
        for (String descriptor : Json.strings(Json.parse(Json.string(projectRow, "descriptors_json")))) {
            builder.addDetectedDescriptor(path(descriptor));
        }
        for (Object moduleRow : Json.array(Json.parse(Json.string(projectRow, "modules_json")))) {
            builder.addModule(readModule(Json.object(moduleRow)));
        }
        return builder.build();
    }

    private static ModuleInfo readModule(Map<String, Object> row) {
        String name = Json.string(row, "name");
        String path = Json.string(row, "path");
        if (name == null || path == null) {
            throw new StorageException("stored module row is incomplete");
        }
        return new ModuleInfo(
                name,
                path(path),
                Json.string(row, "packaging", "unknown"),
                paths(Json.strings(row.get("sourceDirs"))),
                paths(Json.strings(row.get("testSourceDirs"))),
                Json.string(row, "javaVersion"));
    }

    private static Requirement readRequirement(Map<String, Object> row, List<VerificationObligation> obligations) {
        String id = Json.string(row, "requirement_id");
        String description = Json.string(row, "description");
        if (id == null || description == null) {
            throw new StorageException("stored requirement row is incomplete");
        }
        Requirement.Builder builder = Requirement.builder(id, description);
        String title = Json.string(row, "title");
        if (title != null) {
            builder.title(title);
        }
        String sourceFile = Json.string(row, "source_file");
        if (sourceFile != null) {
            builder.source(sourceFile, Json.integer(row, "source_line", -1));
        }
        for (Object property : Json.array(Json.parse(Json.string(row, "properties_json")))) {
            builder.property(readProperty(Json.object(property)));
        }
        for (Map.Entry<String, String> entry
                : Json.stringMap(Json.parse(Json.string(row, "attributes_json"))).entrySet()) {
            builder.attribute(entry.getKey(), entry.getValue());
        }
        builder.obligations(obligations);
        return builder.build();
    }

    private static RequirementProperty readProperty(Map<String, Object> row) {
        return new RequirementProperty(
                Json.string(row, "type", "unknown"),
                Json.object(row.get("values")),
                Json.string(row, "rawText"));
    }

    private static VerificationObligation readObligation(Map<String, Object> row) {
        String id = Json.string(row, "obligation_id");
        if (id == null) {
            throw new StorageException("stored obligation row is incomplete");
        }
        return new VerificationObligation(
                id,
                Json.string(row, "requirement_id", ""),
                Json.string(row, "description", ""),
                enumValue(ObligationKind.class, Json.string(row, "kind"), ObligationKind.GENERIC),
                enumValue(ObligationOrigin.class, Json.string(row, "origin"), ObligationOrigin.ANALYZER_DERIVED),
                Json.string(row, "target"),
                Json.object(Json.parse(Json.string(row, "parameters_json"))),
                Json.integer(row, "advisory_only", 0) != 0);
    }

    private static ObligationOutcome readOutcome(Map<String, Object> row) {
        return new ObligationOutcome(
                Json.string(row, "obligation_id"),
                overallStatus(Json.string(row, "status")),
                Json.string(row, "rationale"),
                Json.strings(Json.parse(Json.string(row, "evidence_ids_json"))),
                Json.strings(Json.parse(Json.string(row, "counterexample_ids_json"))),
                Json.integer(row, "attempts", 0));
    }

    private static RequirementVerdict readVerdict(Map<String, Object> row, List<ObligationOutcome> outcomes) {
        return new RequirementVerdict(
                Json.string(row, "requirement_id"),
                overallStatus(Json.string(row, "status")),
                Json.string(row, "rationale"),
                Json.strings(Json.parse(Json.string(row, "evidence_ids_json"))),
                outcomes,
                Json.strings(Json.parse(Json.string(row, "unknown_aspects_json"))),
                Json.strings(Json.parse(Json.string(row, "counterexample_ids_json"))),
                Json.integer(row, "deterministic_evidence", 0) != 0);
    }

    private static Evidence readEvidence(Map<String, Object> row) {
        String id = Json.string(row, "evidence_id");
        EvidenceType type = enumValue(EvidenceType.class, Json.string(row, "type"), EvidenceType.PROJECT_INSPECTION);
        if (id == null) {
            throw new StorageException("stored evidence row is incomplete");
        }
        Evidence.Builder builder = Evidence.builder(id, type);
        String runId = Json.string(row, "run_id");
        if (runId != null) {
            builder.runId(runId);
        }
        EvidenceStatus status = enumValue(EvidenceStatus.class, Json.string(row, "status"), null);
        if (status != null) {
            builder.status(status);
        }
        EvidenceConfidence confidence =
                enumValue(EvidenceConfidence.class, Json.string(row, "confidence"), null);
        if (confidence != null) {
            builder.confidence(confidence);
        }
        String description = Json.string(row, "description");
        if (description != null) {
            builder.description(description);
        }
        String source = Json.string(row, "source");
        if (source != null) {
            builder.source(source);
        }
        Instant timestamp = instant(Json.string(row, "timestamp"));
        if (timestamp != null) {
            builder.timestamp(timestamp);
        }
        builder.command(Json.strings(Json.parse(Json.string(row, "command_json"))));
        builder.inputs(Json.object(Json.parse(Json.string(row, "inputs_json"))));
        String output = Json.string(row, "output");
        if (output != null) {
            builder.output(output);
        }
        String result = Json.string(row, "result");
        if (result != null) {
            builder.result(result);
        }
        builder.metadata(Json.object(Json.parse(Json.string(row, "metadata_json"))));
        builder.artifacts(paths(Json.strings(Json.parse(Json.string(row, "artifacts_json")))));
        builder.requirements(Json.strings(Json.parse(Json.string(row, "requirement_ids_json"))));
        builder.obligations(Json.strings(Json.parse(Json.string(row, "obligation_ids_json"))));
        builder.generatedByLlm(Json.integer(row, "generated_by_llm", 0) != 0);
        builder.executedIndependently(Json.integer(row, "executed_independently", 1) != 0);
        builder.reproducible(Json.integer(row, "reproducible", 1) != 0);
        String reproductionCommand = Json.string(row, "reproduction_command");
        if (reproductionCommand != null) {
            builder.reproductionCommand(reproductionCommand);
        }
        return builder.build();
    }

    private static Counterexample readCounterexample(Map<String, Object> row) {
        String id = Json.string(row, "counterexample_id");
        if (id == null) {
            throw new StorageException("stored counterexample row is incomplete");
        }
        String reproductionJson = Json.string(row, "reproduction_json");
        Counterexample.ReproductionRecipe recipe = reproductionJson == null
                ? null
                : readReproduction(Json.object(Json.parse(reproductionJson)));
        return new Counterexample(
                id,
                Json.string(row, "requirement_id"),
                Json.string(row, "obligation_id"),
                Json.string(row, "summary"),
                Json.object(Json.parse(Json.string(row, "inputs_json"))),
                Json.string(row, "expected"),
                Json.string(row, "actual"),
                Json.string(row, "invariant_violated"),
                Json.string(row, "observed_by"),
                Json.string(row, "evidence_id"),
                Json.longObject(row, "seed"),
                recipe,
                enumValue(Counterexample.DiscoveryMethod.class, Json.string(row, "discovered_by"), null));
    }

    private static Counterexample.ReproductionRecipe readReproduction(Map<String, Object> row) {
        String artifact = Json.string(row, "testArtifact");
        return new Counterexample.ReproductionRecipe(
                Json.string(row, "description"),
                Json.strings(row.get("command")),
                artifact == null ? null : path(artifact),
                Json.longObject(row, "seed"),
                Json.stringMap(row.get("properties")));
    }

    private static TestCaseResult readTestCase(Map<String, Object> row) {
        return new TestCaseResult(
                Json.string(row, "suite", ""),
                Json.string(row, "name", ""),
                enumValue(TestOutcome.class, Json.string(row, "outcome"), TestOutcome.SKIPPED),
                Duration.ofMillis(Json.longValue(row, "duration_ms", 0L)),
                Json.string(row, "failure_type"),
                Json.string(row, "failure_message"),
                Json.string(row, "stack_trace"));
    }

    private static Finding readFinding(Map<String, Object> row) {
        return new Finding(
                Json.string(row, "analyzer", "unknown"),
                Json.string(row, "rule_id"),
                enumValue(Severity.class, Json.string(row, "severity"), Severity.INFO),
                enumValue(FindingCategory.class, Json.string(row, "category"), FindingCategory.UNKNOWN),
                Json.string(row, "message", ""),
                Json.string(row, "file"),
                Json.intObject(row, "line"),
                Json.string(row, "module"),
                Json.stringMap(Json.parse(Json.string(row, "attributes_json"))));
    }

    private static PhaseRecord readPhase(Map<String, Object> row) {
        return new PhaseRecord(
                Json.string(row, "phase", "unknown"),
                Json.integer(row, "executed", 0) != 0,
                Json.integer(row, "success", 0) != 0,
                Duration.ofMillis(Json.longValue(row, "duration_ms", 0L)),
                Json.string(row, "summary"),
                Json.strings(Json.parse(Json.string(row, "evidence_ids_json"))),
                Json.string(row, "note"));
    }

    private static VerificationRun readRunRecord(Map<String, Object> row, ProjectRef projectRef,
            List<PhaseRecord> phases) {
        String statusName = Json.string(row, "status");
        if (statusName == null) {
            return null;
        }
        EnvironmentSnapshot environment = new EnvironmentSnapshot(
                Json.string(row, "env_codewitness_version"),
                Json.string(row, "env_java_version"),
                Json.string(row, "env_java_vendor"),
                Json.string(row, "env_operating_system"),
                Json.string(row, "env_build_tool"),
                Json.string(row, "env_build_tool_version"),
                Json.string(row, "env_source_revision"),
                Json.string(row, "env_requirements_hash"),
                Json.string(row, "env_configuration_hash"),
                Json.string(row, "env_llm_model"),
                Json.string(row, "isolation_level"),
                Json.string(row, "env_sandbox_description"),
                Json.string(row, "env_project_path"),
                instant(Json.string(row, "env_started_at")),
                instant(Json.string(row, "env_finished_at")));
        Budget budget = row.get("budget_max_duration_ms") instanceof Number
                ? new Budget(
                        Duration.ofMillis(Json.longValue(row, "budget_max_duration_ms", Budget.defaults().maxDuration().toMillis())),
                        Json.integer(row, "budget_max_test_generation_iterations", 0),
                        Json.integer(row, "budget_max_generated_tests", 0),
                        Duration.ofMillis(Json.longValue(row, "budget_max_mutation_time_ms", 0L)),
                        Json.integer(row, "budget_max_llm_calls", 0))
                : null;
        BudgetUsage usage = row.get("usage_elapsed_ms") instanceof Number
                ? new BudgetUsage(
                        Duration.ofMillis(Json.longValue(row, "usage_elapsed_ms", 0L)),
                        Json.integer(row, "usage_test_generation_iterations", 0),
                        Json.integer(row, "usage_generated_tests", 0),
                        Json.integer(row, "usage_llm_calls", 0),
                        Json.integer(row, "usage_exhausted", 0) != 0,
                        Json.string(row, "usage_exhausted_reason"))
                : null;
        return new VerificationRun(
                Json.string(row, "run_id"),
                projectRef,
                enumValue(RunStatus.class, statusName, RunStatus.COMPLETED),
                phases,
                environment,
                budget,
                usage,
                nullablePath(Json.string(row, "workspace")),
                nullablePath(Json.string(row, "report_json_path")),
                nullablePath(Json.string(row, "report_html_path")),
                instant(Json.string(row, "started_at")),
                instant(Json.string(row, "finished_at")),
                Json.strings(Json.parse(Json.string(row, "notes_json"))));
    }

    // ------------------------------------------------------- JSON projections

    private static List<Object> moduleMaps(List<ModuleInfo> modules) {
        List<Object> result = new ArrayList<>();
        for (ModuleInfo module : modules) {
            result.add(map(
                    "name", module.name(),
                    "path", module.path().toString(),
                    "packaging", module.packaging(),
                    "sourceDirs", strings(module.sourceDirs()),
                    "testSourceDirs", strings(module.testSourceDirs()),
                    "javaVersion", module.javaVersion()));
        }
        return result;
    }

    private static List<Object> propertyMaps(List<RequirementProperty> properties) {
        List<Object> result = new ArrayList<>();
        for (RequirementProperty property : properties) {
            result.add(map(
                    "type", property.type(),
                    "values", property.values(),
                    "rawText", property.rawText()));
        }
        return result;
    }

    private static Map<String, Object> buildMap(VerificationReport.BuildSummary build) {
        if (build == null) {
            return null;
        }
        return map(
                "attempted", build.attempted(),
                "success", build.success(),
                "command", build.command(),
                "durationMillis", build.durationMillis(),
                "errors", build.errorCount(),
                "warnings", build.warningCount(),
                "note", build.note());
    }

    private static Map<String, Object> coverageMap(CoverageReport coverage) {
        if (coverage == null) {
            return null;
        }
        return map(
                "measured", coverage.measured(),
                "tool", coverage.tool(),
                "note", coverage.note(),
                "lineCovered", coverage.lineCovered(),
                "lineTotal", coverage.lineTotal(),
                "branchCovered", coverage.branchCovered(),
                "branchTotal", coverage.branchTotal(),
                "perClassLineCoverage", coverage.perClassLineCoverage(),
                "reports", strings(coverage.reports()));
    }

    private static Map<String, Object> mutationMap(MutationReport mutation) {
        if (mutation == null) {
            return null;
        }
        List<Object> survivors = new ArrayList<>();
        for (SurvivingMutant survivor : mutation.survivors()) {
            survivors.add(map(
                    "id", survivor.id(),
                    "status", survivor.status(),
                    "mutator", survivor.mutator(),
                    "className", survivor.className(),
                    "method", survivor.method(),
                    "line", survivor.line(),
                    "description", survivor.description(),
                    "killingTest", survivor.killingTest()));
        }
        return map(
                "measured", mutation.measured(),
                "tool", mutation.tool(),
                "note", mutation.note(),
                "generated", mutation.generated(),
                "killed", mutation.killed(),
                "survived", mutation.survived(),
                "noCoverage", mutation.noCoverage(),
                "timedOut", mutation.timedOut(),
                "survivors", survivors,
                "reports", strings(mutation.reports()));
    }

    private static Map<String, Object> dependencyMap(DependencyReport dependencies) {
        if (dependencies == null) {
            return null;
        }
        List<Object> vulnerabilities = new ArrayList<>();
        for (DependencyVulnerability vulnerability : dependencies.vulnerabilities()) {
            vulnerabilities.add(map(
                    "dependency", vulnerability.dependency(),
                    "version", vulnerability.version(),
                    "vulnerabilityId", vulnerability.vulnerabilityId(),
                    "severity", vulnerability.severity(),
                    "description", vulnerability.description(),
                    "source", vulnerability.source()));
        }
        return map(
                "analysed", dependencies.analysed(),
                "tool", dependencies.tool(),
                "note", dependencies.note(),
                "totalDependencies", dependencies.totalDependencies(),
                "directDependencies", dependencies.directDependencies(),
                "vulnerabilities", vulnerabilities);
    }

    private static Map<String, Object> reproductionMap(Counterexample.ReproductionRecipe recipe) {
        return map(
                "description", recipe.description(),
                "command", recipe.command(),
                "testArtifact", recipe.testArtifact() == null ? null : recipe.testArtifact().toString(),
                "seed", recipe.seed(),
                "properties", recipe.properties());
    }

    private static VerificationReport.BuildSummary readBuild(String json) {
        if (json == null || json.isBlank()) {
            return VerificationReport.BuildSummary.notAttempted("not stored");
        }
        Map<String, Object> row = Json.object(Json.parse(json));
        return new VerificationReport.BuildSummary(
                Json.bool(row, "attempted", false),
                Json.bool(row, "success", false),
                Json.strings(row.get("command")),
                Json.longValue(row, "durationMillis", 0L),
                Json.integer(row, "errors", 0),
                Json.integer(row, "warnings", 0),
                Json.string(row, "note"));
    }

    private static CoverageReport readCoverage(String json) {
        if (json == null || json.isBlank()) {
            return CoverageReport.notMeasured("unknown", "not stored");
        }
        Map<String, Object> row = Json.object(Json.parse(json));
        return new CoverageReport(
                Json.bool(row, "measured", false),
                Json.string(row, "tool", "unknown"),
                Json.string(row, "note"),
                Json.integer(row, "lineCovered", 0),
                Json.integer(row, "lineTotal", 0),
                Json.integer(row, "branchCovered", 0),
                Json.integer(row, "branchTotal", 0),
                Json.doubleMap(row.get("perClassLineCoverage")),
                paths(Json.strings(row.get("reports"))));
    }

    private static MutationReport readMutation(String json) {
        if (json == null || json.isBlank()) {
            return MutationReport.notMeasured("unknown", "not stored");
        }
        Map<String, Object> row = Json.object(Json.parse(json));
        List<SurvivingMutant> survivors = new ArrayList<>();
        for (Object survivor : Json.array(row.get("survivors"))) {
            Map<String, Object> map = Json.object(survivor);
            survivors.add(new SurvivingMutant(
                    Json.string(map, "id"),
                    enumValue(MutantStatus.class,
                            Json.string(map, "status"), MutantStatus.UNKNOWN),
                    Json.string(map, "mutator"),
                    Json.string(map, "className"),
                    Json.string(map, "method"),
                    Json.integer(map, "line", 0),
                    Json.string(map, "description"),
                    Json.string(map, "killingTest")));
        }
        return new MutationReport(
                Json.bool(row, "measured", false),
                Json.string(row, "tool", "unknown"),
                Json.string(row, "note"),
                Json.integer(row, "generated", 0),
                Json.integer(row, "killed", 0),
                Json.integer(row, "survived", 0),
                Json.integer(row, "noCoverage", 0),
                Json.integer(row, "timedOut", 0),
                survivors,
                paths(Json.strings(row.get("reports"))));
    }

    private static DependencyReport readDependencies(String json) {
        if (json == null || json.isBlank()) {
            return DependencyReport.notAnalysed("unknown", "not stored");
        }
        Map<String, Object> row = Json.object(Json.parse(json));
        List<DependencyVulnerability> vulnerabilities = new ArrayList<>();
        for (Object vulnerability : Json.array(row.get("vulnerabilities"))) {
            Map<String, Object> map = Json.object(vulnerability);
            vulnerabilities.add(new DependencyVulnerability(
                    Json.string(map, "dependency", "unknown"),
                    Json.string(map, "version"),
                    Json.string(map, "vulnerabilityId"),
                    enumValue(Severity.class, Json.string(map, "severity"), Severity.INFO),
                    Json.string(map, "description"),
                    Json.string(map, "source")));
        }
        return new DependencyReport(
                Json.bool(row, "analysed", false),
                Json.string(row, "tool", "unknown"),
                Json.string(row, "note"),
                Json.integer(row, "totalDependencies", 0),
                Json.strings(row.get("directDependencies")),
                vulnerabilities);
    }

    // ------------------------------------------------------------- utilities

    /** A unit of work executed inside a single transaction. */
    @FunctionalInterface
    private interface SqlWork {
        void run() throws SQLException;
    }

    /**
     * Runs {@code work} in one transaction. A failure rolls the transaction back and surfaces as a
     * {@link StorageException} (checked failures) or is rethrown with the rollback failure attached
     * (unchecked failures). Nothing is ever swallowed.
     */
    private void inTransaction(String description, SqlWork work) {
        StorageException failure = null;
        try {
            connection.setAutoCommit(false);
            work.run();
            connection.commit();
        } catch (SQLException e) {
            failure = new StorageException(description, e);
            rollbackInto(failure);
        } catch (RuntimeException | Error e) {
            rollbackInto(e);
            throw e;
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException e) {
                if (failure != null) {
                    failure.addSuppressed(e);
                } else {
                    throw new StorageException(description + ": failed to restore auto-commit", e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void rollbackInto(Throwable target) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            target.addSuppressed(e);
        }
    }

    private List<Map<String, Object>> query(String sql, Object... parameters) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                ps.setObject(index + 1, parameters[index]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int columns = meta.getColumnCount();
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int column = 1; column <= columns; column++) {
                        row.put(meta.getColumnLabel(column), rs.getObject(column));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    private ProjectRef resolveProjectRef(VerificationReport report) {
        if (report.project() != null) {
            return report.project().project();
        }
        if (report.run() != null && report.run().project() != null) {
            return report.run().project();
        }
        throw new StorageException(
                "cannot persist verification run " + report.runId() + ": the report carries no project");
    }

    private static String jsonOrNull(Object value) {
        return value == null ? null : Json.write(value);
    }

    private static void setStringOrNull(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    private static String path(Path value) {
        return value == null ? null : value.toString();
    }

    private static Path nullablePath(String value) {
        return value == null ? null : path(value);
    }

    private static Path path(String value) {
        if (value == null) {
            throw new StorageException("stored path value is missing");
        }
        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            throw new StorageException("stored path value is invalid: " + value, e);
        }
    }

    private static List<Path> paths(List<String> values) {
        List<Path> result = new ArrayList<>(values.size());
        for (String value : values) {
            result.add(path(value));
        }
        return result;
    }

    private static List<String> strings(List<Path> values) {
        List<String> result = new ArrayList<>(values.size());
        for (Path value : values) {
            result.add(value.toString());
        }
        return result;
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            throw new StorageException("stored timestamp is not an instant: " + value, e);
        }
    }

    private static RequirementStatus overallStatus(String value) {
        return enumValue(RequirementStatus.class, value, RequirementStatus.UNVERIFIED);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return enumValue(type, value, null);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new StorageException("stored value '" + value + "' is not a valid " + type.getSimpleName(), e);
        }
    }

    private static String phaseStatus(PhaseRecord phase) {
        if (!phase.executed()) {
            return "SKIPPED";
        }
        return phase.success() ? "SUCCEEDED" : "FAILED";
    }

    private static Map<String, Object> map(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("map() requires an even number of arguments");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            result.put((String) keyValues[index], keyValues[index + 1]);
        }
        return result;
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException _) {
            // The open failure that led here is the interesting one; a secondary close failure on a
            // half-initialised connection adds no information.
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new StorageException("verification store " + databaseFile + " is closed");
        }
    }
}
