package dev.codewitness.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static dev.codewitness.cli.CliTestSupport.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void summarisesAStoredRun() throws IOException {
        String runId = "CW-2026-10-06-7F31A";
        write(tmp, ".codewitness/runs/" + runId + "/report.json", """
                {
                  "runId": "%s",
                  "status": "COMPLETED",
                  "statusExplanation": "3 verified, 1 failed"
                }
                """.formatted(runId));

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
