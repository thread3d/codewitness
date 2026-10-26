package dev.codewitness.storage;

import dev.codewitness.core.evidence.Evidence;
import dev.codewitness.core.evidence.EvidenceConfidence;
import dev.codewitness.core.evidence.EvidenceStatus;
import dev.codewitness.core.evidence.EvidenceType;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.requirements.ObligationKind;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.RequirementSet;
import dev.codewitness.core.requirements.RequirementStatus;
import dev.codewitness.core.result.Budget;
import dev.codewitness.core.result.Counterexample;
import dev.codewitness.core.result.RequirementVerdict;
import dev.codewitness.core.result.RunStatus;
import dev.codewitness.core.result.VerificationReport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip tests for {@link SqliteVerificationStore}.
 *
 * <p>Every test uses a database under a JUnit {@link TempDir}, so the suite never touches a real
 * CodeWitness history.
 */
class SqliteVerificationStoreTest {

    private static final String RUN_EARLY = "CW-2026-10-06-AAAAA";
    private static final String RUN_MIDDLE = "CW-2026-10-06-BBBBB";
    private static final String RUN_LATE = "CW-2026-10-06-CCCCC";

    /** Tables the storage specification requires. */
    private static final List<String> SPECIFICATION_TABLES = List.of(
            "verification_runs",
            "projects",
            "requirements",
            "obligations",
            "evidence",
            "test_results",
            "findings",
            "counterexamples",
            "tool_runs");

    @TempDir
    Path tempDir;

    @Test
    void savesAndFindsACompleteRun() {
        Path database = database();
        try (SqliteVerificationStore store = new SqliteVerificationStore(database)) {
            VerificationReport report = ReportFixtures.report(RUN_EARLY, tempDir, ReportFixtures.STARTED, 3);
            store.save(report);

            StoredRun stored = store.findRun(RUN_EARLY).orElseThrow();
            assertEquals(RUN_EARLY, stored.runId());
            assertEquals(RunStatus.COMPLETED, stored.run().status());
            assertEquals(RequirementStatus.FAILED, stored.overallStatus());
            assertEquals(tempDir.getFileName().toString(), stored.project().name());
            assertEquals(tempDir.getFileName().toString(), stored.projectInfo().project().name());
            assertEquals(2, stored.requirements().size());
            assertEquals(3, stored.evidence().size());
            assertEquals(1, stored.counterexamples().size());
            assertEquals(2, stored.tests().cases().size());
            assertEquals(2, stored.findings().size());
            assertEquals(2, stored.run().phases().size());
            assertEquals(1, stored.limitations().size());

            assertEquals(ReportFixtures.STARTED, stored.run().startedAt());
            assertEquals(ReportFixtures.STARTED.plusSeconds(270), stored.run().finishedAt());
            assertEquals(ReportFixtures.FINISHED, stored.run().environment().finishedAt());
            assertEquals("17.0.10", stored.run().environment().javaVersion());
            assertEquals("PROCESS", stored.run().environment().isolationLevel());
            assertEquals(tempDir.resolve("report.json"), stored.run().jsonReport().orElseThrow());
            assertEquals(tempDir.resolve("report.html"), stored.run().htmlReport().orElseThrow());
            assertEquals(Budget.defaults().maxGeneratedTests(), stored.run().budget().maxGeneratedTests());
            assertEquals(Duration.ofSeconds(45), stored.run().usage().elapsed());
            assertFalse(stored.run().usage().exhausted());
            assertEquals(List.of("mutation testing was limited to one module"), stored.run().notes());
            assertEquals(ReportFixtures.phases(), stored.run().phases());
        }
    }

    @Test
    void createsTheSpecificationSchemaAndPersistsRowsInEveryTable() throws SQLException {
        Path database = database();
        try (SqliteVerificationStore store = new SqliteVerificationStore(database)) {
            store.save(ReportFixtures.report(RUN_EARLY, tempDir, ReportFixtures.STARTED, 3));
        }

        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            for (String table : SPECIFICATION_TABLES) {
                assertTrue(tableExists(raw, table), "expected table " + table + " in the schema");
            }
        }

        assertEquals(1, countRows(database, "projects"));
        assertEquals(1, countRows(database, "verification_runs"));
        assertEquals(2, countRows(database, "requirements"));
        assertEquals(2, countRows(database, "obligations"));
        assertEquals(3, countRows(database, "evidence"));
        assertEquals(1, countRows(database, "test_results"));
        assertEquals(2, countRows(database, "test_cases"));
        assertEquals(2, countRows(database, "findings"));
        assertEquals(1, countRows(database, "counterexamples"));
        assertEquals(2, countRows(database, "tool_runs"));
    }

    @Test
    void writesEvidenceColumnsThatAReportCanRead() throws SQLException {
        Path database = database();
        try (SqliteVerificationStore store = new SqliteVerificationStore(database)) {
            store.save(ReportFixtures.report(RUN_EARLY, tempDir, ReportFixtures.STARTED, 2));
        }
        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = raw.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT evidence_id, type, confidence, requirement_ids_json, executed_independently"
                                + " FROM evidence ORDER BY rowid")) {
            assertTrue(rs.next(), "expected at least one evidence row");
            assertEquals("EV-1", rs.getString("evidence_id"));
            assertEquals("TEST", rs.getString("type"));
            assertEquals("DETERMINISTIC", rs.getString("confidence"));
            assertEquals("[\"REQ-1\"]", rs.getString("requirement_ids_json"));
            assertEquals(1, rs.getInt("executed_independently"));
        }
    }

    @Test
    void roundTripsEvidenceRequirementsAndCounterexamples() {
        Path database = database();
        try (SqliteVerificationStore store = new SqliteVerificationStore(database)) {
            store.save(ReportFixtures.report(RUN_EARLY, tempDir, ReportFixtures.STARTED, 3));
            StoredRun stored = store.findRun(RUN_EARLY).orElseThrow();

            Evidence evidence = stored.evidenceById("EV-2").orElseThrow();
            assertEquals(EvidenceType.TEST, evidence.type());
            assertEquals(EvidenceStatus.REFUTES, evidence.status());
            assertEquals(EvidenceConfidence.DETERMINISTIC, evidence.confidence());
            assertEquals("executed test case 2", evidence.description());
            assertEquals("maven-surefire", evidence.source());
            assertEquals(List.of("mvn", "-q", "-Dtest=CalcTest", "test"), evidence.command());
            assertEquals("case-2", evidence.inputs().get("case"));
            assertEquals(1000, ((Number) evidence.inputs().get("boundary")).intValue());
            assertEquals(122, ((Number) evidence.metadata().get("durationMs")).intValue());
            assertEquals("output line 2", evidence.output());
            assertEquals("assertion failure", evidence.result());
            assertEquals(Path.of("/tmp/codewitness/artifacts/report-2.xml"), evidence.artifacts().get(0));
            assertEquals(List.of("REQ-1"), evidence.requirementIds());
            assertEquals(List.of("OB-1"), evidence.obligationIds());
            assertEquals(ReportFixtures.STARTED.plusSeconds(2), evidence.timestamp());
            assertEquals("mvn -Dtest=CalcTest#discount test", evidence.reproductionCommand());
            assertTrue(evidence.executedIndependently());
            assertFalse(evidence.generatedByLlm());
            assertTrue(evidence.reproducible());

            Requirement requirement = stored.requirements().require("REQ-1");
            assertEquals("Discount cap", requirement.title());
            assertEquals("The discount must never exceed 1000.", requirement.description());
            assertEquals("requirements.md", requirement.sourceFile());
            assertEquals(12, requirement.sourceLine());
            assertEquals("finance", requirement.attributes().get("owner"));
            assertEquals(1, requirement.properties().size());
            assertEquals("1000", requirement.properties().get(0).stringValue("max"));
            assertEquals(1, requirement.obligations().size());
            assertEquals(ObligationKind.BOUNDARY_VALUE, requirement.obligations().get(0).kind());
            assertEquals("com.example.Calc#discount", requirement.obligations().get(0).target());
            assertEquals(1000, ((Number) requirement.obligations().get(0).parameters().get("limit")).intValue());
            assertEquals(ReportFixtures.requirements().require("REQ-1").contentHash(),
                    requirement.contentHash());

            RequirementVerdict verdict = stored.verdict("REQ-1").orElseThrow();
            assertEquals(RequirementStatus.FAILED, verdict.status());
            assertEquals("a counterexample was reproduced", verdict.rationale());
            assertEquals(List.of("EV-1", "EV-2"), verdict.evidenceIds());
            assertEquals(List.of("CE-1"), verdict.counterexampleIds());
            assertEquals(List.of("behaviour above 1e9 remains unknown"), verdict.unknownAspects());
            assertEquals(1, verdict.obligations().size());
            assertEquals(3, verdict.obligations().get(0).attempts());
            assertEquals(RequirementStatus.FAILED, verdict.obligations().get(0).status());
            assertTrue(verdict.deterministicEvidence());

            Counterexample counterexample = stored.counterexamples().get(0);
            assertEquals("CE-1", counterexample.id());
            assertEquals("REQ-1", counterexample.requirementId());
            assertEquals("OB-1", counterexample.obligationId());
            assertEquals("discount exceeded the cap", counterexample.summary());
            assertEquals(1000.01, ((Number) counterexample.inputs().get("amount")).doubleValue(), 1e-9);
            assertEquals("EUR", counterexample.inputs().get("currency"));
            assertEquals(42L, counterexample.seed());
            assertEquals(Counterexample.DiscoveryMethod.EXISTING_TEST, counterexample.discoveredBy());
            assertEquals(Path.of("/tmp/codewitness/ce/CalcTest.java"),
                    counterexample.reproduction().testArtifact());
            assertEquals(List.of("mvn", "-Dtest=CalcTest#discount", "test"),
                    counterexample.reproduction().command());
            assertEquals("memory", counterexample.reproduction().properties().get("db"));
        }
    }

    @Test
    void reconstructsTheOriginalReport() {
        Path database = database();
        try (SqliteVerificationStore store = new SqliteVerificationStore(database)) {
            VerificationReport report = ReportFixtures.report(RUN_EARLY, tempDir, ReportFixtures.STARTED, 4);
            store.save(report);
            StoredRun stored = store.findRun(RUN_EARLY).orElseThrow();

            VerificationReport rebuilt = stored.toReport();
            assertEquals(report.runId(), rebuilt.runId());
            assertEquals(report.describe(), rebuilt.describe());
            assertEquals(report.overallStatus(), rebuilt.overallStatus());
            assertEquals(report.build(), rebuilt.build());
            assertEquals(report.verdicts(), rebuilt.verdicts());
            assertEquals(report.counterexamples(), rebuilt.counterexamples());
            assertEquals(report.findings(), rebuilt.findings());
            assertEquals(report.coverage(), rebuilt.coverage());
            assertEquals(report.mutation(), rebuilt.mutation());
            assertEquals(report.dependencies(), rebuilt.dependencies());
            assertEquals(report.tests().cases(), rebuilt.tests().cases());
            assertEquals(report.tests().duration(), rebuilt.tests().duration());
            assertEquals(report.tests().reports(), rebuilt.tests().reports());
            assertEquals(report.limitations(), rebuilt.limitations());
            assertEquals(report.run(), rebuilt.run());
        }
    }

    @Test
    void listRunsReturnsMostRecentFirst() {
        Path database = database();
        try (SqliteVerificationStore store = new SqliteVerificationStore(database)) {
            Instant early = Instant.parse("2026-10-06T08:00:00Z");
            Instant middle = Instant.parse("2026-10-06T09:30:00Z");
            Instant late = Instant.parse("2026-10-06T11:45:00Z");
            store.save(ReportFixtures.report(RUN_MIDDLE, tempDir, middle, 1));
            store.save(ReportFixtures.report(RUN_LATE, tempDir, late, 1));
            store.save(ReportFixtures.report(RUN_EARLY, tempDir, early, 1));

            List<RunSummary> runs = store.listRuns(10);
            assertEquals(List.of(RUN_LATE, RUN_MIDDLE, RUN_EARLY),
                    runs.stream().map(RunSummary::runId).toList());
            assertEquals(late, runs.get(0).startedAt());
            assertEquals(late.plusSeconds(270), runs.get(0).finishedAt());
            assertEquals(RequirementStatus.FAILED, runs.get(0).overallStatus());
            assertEquals(RunStatus.COMPLETED, runs.get(0).status());
            assertEquals(tempDir.getFileName().toString(), runs.get(0).projectName());

            assertEquals(List.of(RUN_LATE, RUN_MIDDLE),
                    store.listRuns(2).stream().map(RunSummary::runId).toList());
            assertTrue(store.listRuns(0).isEmpty());
            assertTrue(store.listRuns(-1).isEmpty());
        }
    }

    @Test
    void reSavingTheSameRunReplacesItInsteadOfFailing() throws SQLException {
        Path database = database();
        try (SqliteVerificationStore store = new SqliteVerificationStore(database)) {
            store.save(ReportFixtures.report(RUN_EARLY, tempDir, ReportFixtures.STARTED, 2));
            store.save(ReportFixtures.report(RUN_EARLY, tempDir, ReportFixtures.STARTED, 5));

            StoredRun stored = store.findRun(RUN_EARLY).orElseThrow();
            assertEquals(5, stored.evidence().size());
            assertEquals(1, store.listRuns(10).size());
        }
        assertEquals(1, countRows(database, "verification_runs"));
        assertEquals(5, countRows(database, "evidence"));
        assertEquals(2, countRows(database, "requirements"));
        assertEquals(2, countRows(database, "obligations"));
        assertEquals(2, countRows(database, "tool_runs"));
        assertEquals(1, countRows(database, "counterexamples"));
    }

    @Test
    void storesReportJsonOnlyWhenTheCallerSuppliesIt() {
        Path database = database();
        String json = "{\"runId\":\"" + RUN_EARLY + "\",\"status\":\"COMPLETED\"}";
        try (SqliteVerificationStore store = new SqliteVerificationStore(database)) {
            store.save(ReportFixtures.report(RUN_EARLY, tempDir, ReportFixtures.STARTED, 1), json);
            store.save(ReportFixtures.report(RUN_MIDDLE, tempDir, ReportFixtures.STARTED, 1));

            assertEquals(json, store.findReportJson(RUN_EARLY).orElseThrow());
            assertEquals(json, store.findRun(RUN_EARLY).orElseThrow().reportJson());
            assertTrue(store.findRun(RUN_EARLY).orElseThrow().hasReportJson());

            assertTrue(store.findReportJson(RUN_MIDDLE).isEmpty());
            assertFalse(store.findRun(RUN_MIDDLE).orElseThrow().hasReportJson());
        }

        try (SqliteVerificationStore store = new SqliteVerificationStore(database)) {
            store.save(ReportFixtures.report(RUN_LATE, tempDir, ReportFixtures.STARTED, 1), null);
            assertTrue(store.findReportJson(RUN_LATE).isEmpty());
        }
    }

    @Test
    void findRunReturnsEmptyForAnUnknownRunId() {
        try (SqliteVerificationStore store = new SqliteVerificationStore(database())) {
            assertTrue(store.findRun("CW-2026-10-06-ZZZZZ").isEmpty());
            assertTrue(store.findReportJson("CW-2026-10-06-ZZZZZ").isEmpty());
            assertTrue(store.listRuns(10).isEmpty());
        }
    }

    @Test
    void createsMissingParentDirectoriesAndIsIdempotentOnReopen() throws Exception {
        Path database = tempDir.resolve("deep/nested/history/codewitness.db");
        try (SqliteVerificationStore store = new SqliteVerificationStore(database)) {
            store.save(ReportFixtures.report(RUN_EARLY, tempDir, ReportFixtures.STARTED, 1));
        }
        assertTrue(Files.isRegularFile(database), "database file should exist");

        try (SqliteVerificationStore reopened = new SqliteVerificationStore(database)) {
            assertEquals(1, reopened.listRuns(10).size());
            reopened.save(ReportFixtures.report(RUN_MIDDLE, tempDir, ReportFixtures.STARTED.plusSeconds(60), 2));
            assertEquals(2, reopened.listRuns(10).size());
            assertEquals(2, reopened.findRun(RUN_MIDDLE).orElseThrow().evidence().size());
        }
        assertEquals(2, countRows(database, "verification_runs"));
        assertEquals(1, countRows(database, "projects"));
    }

    @Test
    void closingTheStoreIsIdempotentAndRejectsFurtherUse() {
        Path database = database();
        SqliteVerificationStore store = new SqliteVerificationStore(database);
        store.save(ReportFixtures.report(RUN_EARLY, tempDir, ReportFixtures.STARTED, 1));
        store.close();
        store.close();

        assertThrows(StorageException.class, () -> store.save(ReportFixtures.report(RUN_MIDDLE, tempDir, ReportFixtures.STARTED, 1)));
        assertThrows(StorageException.class, () -> store.findRun(RUN_EARLY));
        assertThrows(StorageException.class, () -> store.findReportJson(RUN_EARLY));
        assertThrows(StorageException.class, () -> store.listRuns(10));
        assertTrue(Files.isRegularFile(database));
    }

    @Test
    void rejectsAReportWithoutAProject() {
        try (SqliteVerificationStore store = new SqliteVerificationStore(database())) {
            VerificationReport report = VerificationReport
                    .builder(RUN_EARLY, null, RequirementSet.empty())
                    .build();
            StorageException failure = assertThrows(StorageException.class, () -> store.save(report));
            assertTrue(failure.getMessage().contains(RUN_EARLY));
        }
    }

    @Test
    void storesRunsWithoutARunRecordOrTestCases() throws SQLException {
        Path database = database();
        try (SqliteVerificationStore store = new SqliteVerificationStore(database)) {
            VerificationReport report = VerificationReport
                    .builder(RUN_LATE, ReportFixtures.project(tempDir), RequirementSet.empty())
                    .tests(TestResult.notExecuted("build failed"))
                    .build();
            store.save(report);

            StoredRun stored = store.findRun(RUN_LATE).orElseThrow();
            assertNull(stored.run());
            assertEquals(RequirementStatus.UNVERIFIED, stored.overallStatus());
            assertFalse(stored.tests().executed());
            assertEquals("build failed", stored.tests().note());
            assertEquals(0, countRows(database, "tool_runs"));
            assertEquals(1, store.listRuns(10).size());
            assertNull(store.listRuns(10).get(0).status());
        }
    }

    @Test
    void storesManyRunsForTheSameProjectWithoutDuplicatingIt() throws SQLException {
        Path database = database();
        try (SqliteVerificationStore store = new SqliteVerificationStore(database)) {
            store.save(ReportFixtures.report(RUN_EARLY, tempDir, ReportFixtures.STARTED, 1));
            store.save(ReportFixtures.report(RUN_MIDDLE, tempDir, ReportFixtures.STARTED.plusSeconds(60), 1));
            store.save(ReportFixtures.report(RUN_LATE, tempDir, ReportFixtures.STARTED.plusSeconds(120), 1));
        }
        assertEquals(1, countRows(database, "projects"));
        assertEquals(3, countRows(database, "verification_runs"));
        assertEquals(3, countRows(database, "evidence"));
    }

    @Test
    void keepsEachRunsOwnProjectPathWhenAnIdentifierIsShared() throws Exception {
        Path database = database();
        Path otherCheckout = tempDir.resolve("checkout-b");
        Files.createDirectories(otherCheckout);
        try (SqliteVerificationStore store = new SqliteVerificationStore(database)) {
            store.save(ReportFixtures.report(RUN_EARLY,
                    ReportFixtures.project(new ProjectRef(tempDir, "demo", "shared-project")),
                    ReportFixtures.STARTED, 1));
            store.save(ReportFixtures.report(RUN_MIDDLE,
                    ReportFixtures.project(new ProjectRef(otherCheckout, "demo", "shared-project")),
                    ReportFixtures.STARTED.plusSeconds(60), 1));

            assertEquals(tempDir.toAbsolutePath().normalize(),
                    store.findRun(RUN_EARLY).orElseThrow().project().root());
            assertEquals(otherCheckout.toAbsolutePath().normalize(),
                    store.findRun(RUN_MIDDLE).orElseThrow().project().root());
        }
        assertEquals(1, countRows(database, "projects"));
        assertEquals(2, countRows(database, "verification_runs"));
    }

    // ------------------------------------------------------------- helpers

    private Path database() {
        return tempDir.resolve("nested/history/codewitness.db");
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Table names cannot be bound as parameters, so the helper only accepts known schema tables. */
    private static long countRows(Path database, String table) throws SQLException {
        List<String> known = new java.util.ArrayList<>(SPECIFICATION_TABLES);
        known.add("test_cases");
        if (!known.contains(table)) {
            throw new IllegalArgumentException("not a CodeWitness table: " + table);
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }



    @Test
    @DisplayName("a write that loses the race for the database either succeeds or says it was contention")
    void aContendedWriteIsRetriedOrExplained() throws Exception {
        // Two runs against the same project is a normal thing to do, and SQLite allows one writer. The loser
        // used to wait out busy_timeout and give up, which lost the whole run and said so in a warning. Now it
        // retries; and if the contention outlasts the retries, the failure must name contention rather than
        // arriving as a generic storage error. Either outcome is correct here - what must not happen is a
        // silent loss, which is what the classifier below rules out.
        Path database = database();
        try (SqliteVerificationStore store = new SqliteVerificationStore(database)) {
            store.save(ReportFixtures.report(RUN_EARLY, tempDir, ReportFixtures.STARTED, 3));
        }
        boolean recorded;
        try (Connection holder = DriverManager.getConnection("jdbc:sqlite:" + database);
                SqliteVerificationStore contender = new SqliteVerificationStore(database, false, 25)) {
            holder.setAutoCommit(true);
            try (Statement statement = holder.createStatement()) {
                statement.execute("BEGIN IMMEDIATE");
            }
            Thread release = new Thread(() -> {
                try {
                    Thread.sleep(250);
                    holder.rollback();
                } catch (Exception ignored) {
                    // the assertions are the evidence
                }
            });
            release.start();
            try {
                contender.save(ReportFixtures.report(RUN_MIDDLE, tempDir, ReportFixtures.STARTED, 3));
                recorded = true;
            } catch (StorageException e) {
                recorded = false;
                assertTrue(StorageUnavailableException.isBusy(e),
                        "a contended write that gives up must name the contention: " + e.getMessage());
            } finally {
                release.join();
            }
        }
        if (recorded) {
            try (SqliteVerificationStore reopened = new SqliteVerificationStore(database, true)) {
                assertTrue(reopened.findRun(RUN_MIDDLE).isPresent(), "a reported success must be readable back");
            }
        }
    }

    @Test
    @DisplayName("contention is distinguished from a restriction the database cannot recover from")
    void contentionIsClassifiedSeparately() throws Exception {
        // Retrying a read-only directory or a corrupt file would only wait, so only contention is retried.
        assertTrue(StorageUnavailableException.isBusy(new SQLException("[SQLITE_BUSY] database is locked")));
        assertTrue(StorageUnavailableException.isBusy(new SQLException("SQLITE_LOCKED: locked")));
        assertFalse(StorageUnavailableException.isBusy(new SQLException("[SQLITE_READONLY] read only")));
        assertTrue(StorageUnavailableException.isUnavailable(new SQLException("[SQLITE_READONLY] read only")));
        assertFalse(StorageUnavailableException.isUnavailable(new SQLException("file is not a database")),
                "a corrupt database is a defect, not a restriction");
    }

    @Test
    @DisplayName("a store opened for reading refuses to write, and says why")
    void aReadOnlyStoreRefusesToWrite() {
        // Reading a history is a different operation from recording one. Where the directory is read-only the
        // alternative was a driver error that reached the user as an internal failure and filed a self-issue
        // against CodeWitness, which was both wrong about the cause and made the history unreadable.
        Path database = database();
        try (SqliteVerificationStore store = new SqliteVerificationStore(database)) {
            store.save(ReportFixtures.report(RUN_EARLY, tempDir, ReportFixtures.STARTED, 3));
        }

        try (SqliteVerificationStore readOnly = new SqliteVerificationStore(database, true)) {
            assertTrue(readOnly.findRun(RUN_EARLY).isPresent(), "reading must work");
            StorageException refused = assertThrows(StorageException.class,
                    () -> readOnly.save(ReportFixtures.report(RUN_EARLY, tempDir, ReportFixtures.STARTED, 3)));
            assertTrue(refused.getMessage().contains("reading"), refused.getMessage());
        }
    }

    @Test
    @DisplayName("a read-only store does not create a database that is not there")
    void aReadOnlyStoreDoesNotCreateADatabase() {
        Path database = tempDir.resolve("absent.db");

        StorageException refused = assertThrows(StorageException.class,
                () -> new SqliteVerificationStore(database, true));

        assertTrue(refused.getMessage().contains("no verification history"), refused.getMessage());
        assertFalse(Files.exists(database), "a read must not create the file it is reading");
    }

    @Test
    @DisplayName("a version 1 database is migrated in place and keeps its rows")
    void anOlderSchemaIsMigrated() throws SQLException {
        // Version 1 keyed requirements by (run_id, requirement_id), which made a run with a repeated
        // requirement heading impossible to store. Version 2 keys it by (run_id, ordinal). SQLite cannot
        // alter a primary key, so the upgrade rebuilds the table; a file that is opened and silently not
        // upgraded would keep failing to record runs, which is the failure mode the version exists to stop.
        Path database = database();
        try (SqliteVerificationStore store = new SqliteVerificationStore(database)) {
            store.save(ReportFixtures.report(RUN_EARLY, tempDir, ReportFixtures.STARTED, 3));
        }
        // Rewrite the table into its version 1 shape, as a file written by 0.1.4 has it.
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE requirements RENAME TO requirements_v2");
            statement.execute("""
                    CREATE TABLE requirements (
                        run_id                  TEXT NOT NULL REFERENCES verification_runs(run_id)
                                                    ON DELETE CASCADE,
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
                    """);
            statement.execute("INSERT INTO requirements (run_id, requirement_id, ordinal, description) "
                    + "SELECT run_id, requirement_id, ordinal, description FROM requirements_v2");
            statement.execute("DROP TABLE requirements_v2");
            statement.execute("PRAGMA user_version = 1");
        }

        try (SqliteVerificationStore store = new SqliteVerificationStore(database)) {
            assertTrue(store.findRun(RUN_EARLY).isPresent(), "the migrated run must still be readable");
        }
        assertEquals(2, userVersion(database), "opening the file must upgrade it");
        assertEquals(2, requirementRows(database), "no requirement row may be lost in the upgrade");
        assertTrue(primaryKeyCoversOrdinal(database),
                "the upgraded table must be keyed by occurrence, not by requirement id");
    }

    private static int requirementRows(Path database) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM requirements")) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }

    private static boolean primaryKeyCoversOrdinal(Path database) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("PRAGMA table_info(requirements)")) {
            boolean idIsKey = false;
            boolean ordinalIsKey = false;
            while (rows.next()) {
                int keyPosition = rows.getInt("pk");
                if (keyPosition > 0) {
                    if ("requirement_id".equals(rows.getString("name"))) {
                        idIsKey = true;
                    }
                    if ("ordinal".equals(rows.getString("name"))) {
                        ordinalIsKey = true;
                    }
                }
            }
            return ordinalIsKey && !idIsKey;
        }
    }

    @Test
    @DisplayName("a new database is stamped with the schema version this build writes")
    void newDatabaseIsStamped() throws SQLException {
        // Without a version there is no way to tell an old file from a current one, and CREATE TABLE IF NOT
        // EXISTS is a silent no-op against a table with an older column set: an added column then makes every
        // later insert fail against a history that already exists.
        Path database = database();
        try (SqliteVerificationStore store = new SqliteVerificationStore(database)) {
            store.save(ReportFixtures.report(RUN_EARLY, tempDir, ReportFixtures.STARTED, 3));
        }
        assertTrue(userVersion(database) >= 1, "the schema version must be recorded in the file");
    }

    @Test
    @DisplayName("a database written by a newer build is refused instead of being written to")
    void aNewerSchemaIsRefused() throws SQLException {
        Path database = database();
        try (SqliteVerificationStore store = new SqliteVerificationStore(database)) {
            store.save(ReportFixtures.report(RUN_EARLY, tempDir, ReportFixtures.STARTED, 3));
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = 999");
        }

        StorageException refused = assertThrows(StorageException.class,
                () -> new SqliteVerificationStore(database));
        assertTrue(refused.getMessage().contains("newer"), refused.getMessage());
    }

    private static int userVersion(Path database) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("PRAGMA user_version")) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }

}
