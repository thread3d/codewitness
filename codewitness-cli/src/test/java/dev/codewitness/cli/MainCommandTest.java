package dev.codewitness.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
