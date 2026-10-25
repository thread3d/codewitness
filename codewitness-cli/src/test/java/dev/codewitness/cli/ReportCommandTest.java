package dev.codewitness.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static dev.codewitness.cli.CliTestSupport.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code codewitness report} reads stored runs; with no history it must say so rather than fail. */
class ReportCommandTest {

    @TempDir
    Path tmp;

    @Test
    void withNoHistoryItReportsThatAndSucceeds() {
        CliTestSupport.Result result = CliTestSupport.run("report", "--project", tmp.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("No verification history found"), result.stdout());
    }


    @Test
    @DisplayName("a run whose directory is gone says so instead of printing paths that do not exist")
    void aMissingRunDirectoryIsStated() throws IOException, java.sql.SQLException {
        // `clean` used to delete directories and leave the index rows behind, and `report` then printed three
        // file paths as "the full report" for a directory that was no longer there. A run kept only in the
        // index is a real state; presenting it as a report that can be opened is not.
        String runId = "CW-2026-10-25-DVXD5";
        Path database = tmp.resolve(".codewitness").resolve("codewitness.db");
        new dev.codewitness.storage.SqliteVerificationStore(database).close();
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
                java.sql.Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO projects (project_id, name, root, language, build_system, "
                    + "build_root, java_version, test_frameworks_json, attributes_json, descriptors_json, "
                    + "modules_json, created_at, updated_at) VALUES ('p1', 'example', '/tmp/example', "
                    + "'java', 'maven', '/tmp/example', '17', '[]', '{}', '[]', '[]', "
                    + "'2026-10-25T00:00:00Z', '2026-10-25T00:00:00Z')");
            statement.executeUpdate("INSERT INTO verification_runs (run_id, project_id, status, "
                    + "overall_status, started_at, finished_at, report_json, created_at, updated_at) VALUES ('"
                    + runId + "', 'p1', 'COMPLETED', 'VERIFIED', '2026-10-25T00:00:00Z', "
                    + "'2026-10-25T00:01:00Z', '{\"runId\":\"" + runId + "\",\"status\":\"COMPLETED\"}', "
                    + "'2026-10-25T00:00:00Z', '2026-10-25T00:01:00Z')");
        }

        CliTestSupport.Result result = CliTestSupport.run("report", runId, "--project", tmp.toString());

        assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
        assertTrue(result.stdout().contains("is missing"), result.stdout());
        assertTrue(result.stdout().contains("no longer exist"), result.stdout());
        assertFalse(result.stdout().contains("Open the full report"), result.stdout());
    }

    @Test
    void summarisesAStoredRun() throws IOException {
        String runId = "CW-2026-10-06-7F31A";
        write(tmp, ".codewitness/runs/" + runId + "/report.json", """
                {
                  "runId": "%s",
                  "status": "COMPLETED",
                  "statusExplanation": "3 verified, 1 failed"
                }
                """.formatted(runId));
        // A real run writes both files; only a file that exists is offered as a path to open.
        write(tmp, ".codewitness/runs/" + runId + "/report.html", "<html></html>");

        CliTestSupport.Result result = CliTestSupport.run("report", runId, "--project", tmp.toString());

        assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
        assertTrue(result.stdout().contains(runId), result.stdout());
        assertTrue(result.stdout().contains("COMPLETED"), result.stdout());
        assertTrue(result.stdout().contains("3 verified, 1 failed"), result.stdout());
        assertTrue(result.stdout().contains("report.html"), result.stdout());
    }

    @Test
    void printsTheCanonicalJsonWhenAsked() throws IOException {
        String runId = "CW-2026-10-06-7F31A";
        String json = "{\"runId\":\"" + runId + "\",\"status\":\"FAILED\"}";
        write(tmp, ".codewitness/runs/" + runId + "/report.json", json);

        CliTestSupport.Result result =
                CliTestSupport.run("report", runId, "--project", tmp.toString(), "--json");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("\"runId\""), result.stdout());
        assertTrue(result.stdout().contains(runId));
    }

    @Test
    void anUnknownRunIdFails() {
        CliTestSupport.Result result = CliTestSupport.run(
                "report", "CW-2026-10-06-ZZZZZ", "--project", tmp.toString());

        assertEquals(2, result.exitCode());
        assertTrue(result.stderr().contains("no stored report"), result.stderr());
    }
}
