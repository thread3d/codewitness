package dev.codewitness.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static dev.codewitness.cli.CliTestSupport.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code codewitness reproduce} surfaces the command recorded with a counterexample. */
class ReproduceCommandTest {

    @TempDir
    Path tmp;

    private static final String REPORT_JSON = """
            {
              "runId": "CW-2026-10-06-7F31A",
              "counterexamples": [
                {
                  "id": "CE-001",
                  "requirementId": "R-001",
                  "summary": "limit exceeded",
                  "reproduction": {
                    "description": "replay the failing case",
                    "command": ["mvn", "-q", "test", "-Dcodewitness.case=3"]
                  }
                },
                {
                  "id": "CE-002",
                  "requirementId": "R-002",
                  "reproduction": {
                    "description": "replay another case",
                    "command": ["mvn", "-q", "test", "-Dcodewitness.case=7"]
                  }
                }
              ]
            }
            """;

    @Test
    void extractsTheCommandOfTheRequestedCounterexample() {
        assertEquals(java.util.List.of("mvn -q test -Dcodewitness.case=3"),
                ReproduceCommand.extractReproductionCommands(REPORT_JSON, "CE-001"));
        assertEquals(java.util.List.of("mvn -q test -Dcodewitness.case=7"),
                ReproduceCommand.extractReproductionCommands(REPORT_JSON, "CE-002"));
    }

    @Test
    void returnsEveryCommandWhenNoCounterexampleIsSelected() {
        assertEquals(2, ReproduceCommand.extractReproductionCommands(REPORT_JSON, null).size());
    }

    @Test
    void returnsAnEmptyListWhenTheCounterexampleIsAbsent() {
        assertTrue(ReproduceCommand.extractReproductionCommands(REPORT_JSON, "CE-999").isEmpty());
        assertTrue(ReproduceCommand.extractReproductionCommands("{}", "CE-001").isEmpty());
    }

    @Test
    void printsTheReproductionCommandWithoutRunningIt() throws IOException {
        String runId = "CW-2026-10-06-7F31A";
        write(tmp, ".codewitness/runs/" + runId + "/report.json", REPORT_JSON);

        CliTestSupport.Result result = CliTestSupport.run(
                "reproduce", runId, "--counterexample", "CE-001", "--project", tmp.toString());

        assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
        assertTrue(result.stdout().contains("mvn -q test -Dcodewitness.case=3"), result.stdout());
        assertTrue(result.stdout().contains("Add --run to execute"), result.stdout());
    }

    @Test
    void saysWhenTheCounterexampleWasNotRecorded() throws IOException {
        String runId = "CW-2026-10-06-7F31A";
        write(tmp, ".codewitness/runs/" + runId + "/report.json", REPORT_JSON);

        CliTestSupport.Result result = CliTestSupport.run(
                "reproduce", runId, "--counterexample", "CE-999", "--project", tmp.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("No counterexample CE-999 was recorded"), result.stdout());
    }

    @Test
    void failsWhenThereIsNoStoredReport() {
        CliTestSupport.Result result = CliTestSupport.run(
                "reproduce", "CW-2026-10-06-ZZZZZ", "--project", tmp.toString());

        assertEquals(2, result.exitCode());
        assertTrue(result.stderr().contains("no stored report"), result.stderr());
    }
}
