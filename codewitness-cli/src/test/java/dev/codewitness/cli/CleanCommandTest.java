package dev.codewitness.cli;

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

    private void createRun(String runId) throws IOException {
        write(tmp, ".codewitness/runs/" + runId + "/report.json", "{\"runId\":\"" + runId + "\"}");
        write(tmp, ".codewitness/runs/" + runId + "/artifacts/log.txt", "output");
    }

    private static Path runs(Path root) {
        return root.resolve(".codewitness").resolve("runs");
    }
}
