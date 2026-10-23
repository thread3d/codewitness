package dev.codewitness.cli;

import dev.codewitness.core.diagnostics.SelfIssueJournal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The command line surface: exit codes and the fact that the tool identifies itself. */
class MainCommandTest {

    @Test
    void versionSubcommandSucceedsAndPrintsCodewitness() {
        CliTestSupport.Result result = CliTestSupport.run("version");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("codewitness"), result.stdout());
        assertTrue(result.stdout().contains("java"), result.stdout());
    }

    @Test
    void helpSucceeds() {
        CliTestSupport.Result result = CliTestSupport.run("--help");

        assertEquals(0, result.exitCode());
        assertTrue(result.mentions("codewitness"), result.stdout() + result.stderr());
        assertTrue(result.mentions("verify"), "the help must list the subcommands");
        assertTrue(result.mentions("mcp"), "the help must list the MCP server");
    }

    @Test
    void versionFlagSucceeds() {
        CliTestSupport.Result result = CliTestSupport.run("--version");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("codewitness"), result.stdout());
    }

    @Test
    void anUnknownSubcommandReturnsNonZero() {
        CliTestSupport.Result result = CliTestSupport.run("frobnicate");

        assertNotEquals(0, result.exitCode(), "an unknown command must not look like success");
        assertTrue(result.mentions("frobnicate") || result.mentions("Unmatched"),
                result.stdout() + result.stderr());
    }

    @Test
    void noArgumentsPrintsUsageWithoutFailing() {
        CliTestSupport.Result result = CliTestSupport.run();

        assertEquals(0, result.exitCode());
        assertTrue(result.mentions("codewitness"), result.stdout() + result.stderr());
    }

    @Test
    void durationAndStatusMarkersAreStable() {
        assertEquals("5s", CliSupport.duration(java.time.Duration.ofSeconds(5)));
        assertEquals("1m5s", CliSupport.duration(java.time.Duration.ofSeconds(65)));
        assertEquals("\u2713", CliSupport.marker(dev.codewitness.core.requirements.RequirementStatus.VERIFIED));
        assertEquals("\u2717", CliSupport.marker(dev.codewitness.core.requirements.RequirementStatus.FAILED));
        assertEquals("?", CliSupport.marker(dev.codewitness.core.requirements.RequirementStatus.UNVERIFIED));
    }

    @Test
    void anUnknownOptionIsAUsageErrorAndExitsThree() {
        CliTestSupport.Result result = CliTestSupport.run("verify", "--not-an-option");

        assertEquals(3, result.exitCode(),
                "3 is documented as 'usage error'; picocli's default 2 would collide with BLOCKED");
    }

    @Test
    void aMissingRequiredParameterIsAUsageErrorAndExitsThree() {
        CliTestSupport.Result result = CliTestSupport.run("reproduce");

        assertEquals(3, result.exitCode());
    }

    @Test
    void aBadConfigurationExitsThreeWithAMessageRatherThanAStackTrace(@TempDir Path tmp) throws IOException {
        Path config = tmp.resolve("broken.yaml");
        Files.writeString(config, "verification:\n  max_durationn: 5m\n", StandardCharsets.UTF_8);

        CliTestSupport.Result result = CliTestSupport.run("verify", tmp.toString(), "--config", config.toString());

        assertEquals(3, result.exitCode(), result.stdout() + result.stderr());
        assertTrue(result.stderr().contains("max_durationn"),
                "the message must name the offending key: " + result.stderr());
        assertFalse(result.stderr().contains("\tat dev.codewitness"),
                "a configuration mistake is not a stack trace: " + result.stderr());
    }

    @Test
    void anUnexpectedFailureIsWrittenToTheProjectsSelfIssueLog(@TempDir Path project) throws IOException {
        // A corrupt index is a failure of the tool's own input handling, not a verification result. The
        // command must still exit 3, but it must also leave a durable, readable record behind.
        Files.createDirectories(project.resolve(".codewitness"));
        Files.writeString(project.resolve(".codewitness/codewitness.db"), "not a sqlite database",
                StandardCharsets.UTF_8);

        CliTestSupport.Result result = CliTestSupport.run("report", "--project", project.toString());

        assertEquals(3, result.exitCode(), result.stdout() + result.stderr());
        Path digest = SelfIssueJournal.defaultDirectory(project).resolve(SelfIssueJournal.MARKDOWN_FILE);
        assertTrue(Files.isRegularFile(digest), "no self-issue digest was written next to " + project);
        String markdown = Files.readString(digest, StandardCharsets.UTF_8);
        assertTrue(markdown.contains("INTERNAL_ERROR"), markdown);
        assertTrue(result.stderr().contains("self-issue log"), result.stderr());
    }
}
