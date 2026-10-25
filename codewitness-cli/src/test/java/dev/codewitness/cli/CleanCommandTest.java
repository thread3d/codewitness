package dev.codewitness.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.codewitness.cli.CliTestSupport.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code codewitness clean} deletes stored run directories. The newest runs are the ones that must
 * survive, because they are the ones a user is most likely to still care about.
 */
class CleanCommandTest {

    @TempDir
    Path tmp;

    private static final String OLDEST = "CW-2026-10-06-AAAAA";
    private static final String MIDDLE = "CW-2026-10-06-BBBBB";
    private static final String NEWEST = "CW-2026-10-06-CCCCC";

    @Test
    void removesRunDirectoriesAndKeepsTheNewestOne() throws IOException {
        createRun(OLDEST);
        createRun(MIDDLE);
        createRun(NEWEST);

        CliTestSupport.Result result =
                CliTestSupport.run("clean", tmp.toString(), "--keep", "1");

        assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
        assertTrue(runs(tmp).resolve(NEWEST).toFile().isDirectory(), "the newest run is kept");
        assertFalse(runs(tmp).resolve(MIDDLE).toFile().exists(), "older runs are deleted");
        assertFalse(runs(tmp).resolve(OLDEST).toFile().exists(), "older runs are deleted");
        assertTrue(result.stdout().contains("Deleted 2 run director(ies), kept 1"), result.stdout());
    }

    @Test
    void keepAllIsTheDefault() throws IOException {
        createRun(OLDEST);
        createRun(NEWEST);

        CliTestSupport.Result result = CliTestSupport.run("clean", tmp.toString());

        assertEquals(0, result.exitCode());
        assertTrue(runs(tmp).resolve(OLDEST).toFile().isDirectory());
        assertTrue(runs(tmp).resolve(NEWEST).toFile().isDirectory());
        assertTrue(result.stdout().contains("Deleted 0 run director(ies), kept 5"), result.stdout());
    }

    @Test
    void allDeletesEveryRun() throws IOException {
        createRun(OLDEST);
        createRun(NEWEST);

        CliTestSupport.Result result = CliTestSupport.run("clean", tmp.toString(), "--all");

        assertEquals(0, result.exitCode());
        assertFalse(runs(tmp).resolve(OLDEST).toFile().exists());
        assertFalse(runs(tmp).resolve(NEWEST).toFile().exists());
        assertTrue(result.stdout().contains("kept 0"), result.stdout());
    }

    @Test
    void withNoRunDirectoryItSaysThereIsNothingToClean() {
        CliTestSupport.Result result = CliTestSupport.run("clean", tmp.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Nothing to clean"), result.stdout());
    }


    @Test
    @DisplayName("--keep keeps the newest runs by time, not by run id")
    void keepUsesModificationTimeNotTheRandomSuffix() throws IOException {
        // A run id is a date plus five random characters, so within one day its name says nothing about
        // when the run happened. Sorting by name made `--keep 1` delete the newest run and keep the oldest,
        // which destroys exactly the run a user asked to preserve. These ids are taken from a real history,
        // where name order and time order disagree.
        String oldestByIdButNewestByTime = "CW-2026-10-25-0QYX6";
        String newestByIdButOldestByTime = "CW-2026-10-25-DVXD5";
        createRun(oldestByIdButNewestByTime);
        createRun(newestByIdButOldestByTime);
        setModified(oldestByIdButNewestByTime, 2_000L);
        setModified(newestByIdButOldestByTime, 1_000L);

        CliTestSupport.Result result = CliTestSupport.run("clean", tmp.toString(), "--keep", "1");

        assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
        assertTrue(runs(tmp).resolve(oldestByIdButNewestByTime).toFile().isDirectory(),
                "the most recent run must survive whatever its random suffix is");
        assertFalse(runs(tmp).resolve(newestByIdButOldestByTime).toFile().exists(),
                "the older run is the one that must be deleted");
    }

    @Test
    @DisplayName("clean removes the index rows of the runs it deletes")
    void cleanAlsoRemovesTheIndexRows() throws IOException, java.sql.SQLException {
        // The history is two stores. Deleting only the directories left rows behind, and `report <id>`
        // then printed the paths of a directory that no longer existed as though the report were there.
        String runId = "CW-2026-10-25-0QYX6";
        createRun(runId);
        Path database = tmp.resolve(".codewitness").resolve("codewitness.db");
        // Opening the store creates the schema; closing it again leaves a real, empty history behind.
        new dev.codewitness.storage.SqliteVerificationStore(database).close();
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
                java.sql.Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO projects (project_id, name, root, language, build_system, "
                    + "build_root, java_version, test_frameworks_json, attributes_json, descriptors_json, "
                    + "modules_json, created_at, updated_at) VALUES ('p1', 'example', '/tmp/example', "
                    + "'java', 'maven', '/tmp/example', '17', '[]', '{}', '[]', '[]', "
                    + "'2026-10-25T00:00:00Z', '2026-10-25T00:00:00Z')");
            statement.executeUpdate("INSERT INTO verification_runs (run_id, project_id, status, overall_status, "
                    + "started_at, finished_at, created_at, updated_at) VALUES ('" + runId + "', 'p1', "
                    + "'COMPLETED', 'VERIFIED', '2026-10-25T00:00:00Z', '2026-10-25T00:01:00Z', "
                    + "'2026-10-25T00:00:00Z', '2026-10-25T00:01:00Z')");
        }

        CliTestSupport.Result result = CliTestSupport.run("clean", tmp.toString(), "--all");

        assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
                java.sql.Statement statement = connection.createStatement();
                java.sql.ResultSet rows = statement.executeQuery(
                        "SELECT COUNT(*) FROM verification_runs WHERE run_id = '" + runId + "'")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1), "a deleted run must not still be listed by `report`");
        }
    }

    private void setModified(String runId, long millis) throws IOException {
        Files.setLastModifiedTime(runs(tmp).resolve(runId),
                java.nio.file.attribute.FileTime.fromMillis(millis));
    }

    private void createRun(String runId) throws IOException {
        write(tmp, ".codewitness/runs/" + runId + "/report.json", "{\"runId\":\"" + runId + "\"}");
        write(tmp, ".codewitness/runs/" + runId + "/artifacts/log.txt", "output");
    }

    private static Path runs(Path root) {
        return root.resolve(".codewitness").resolve("runs");
    }
}
