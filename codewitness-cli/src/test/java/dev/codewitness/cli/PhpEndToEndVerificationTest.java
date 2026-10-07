package dev.codewitness.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end verification of a deliberately defective PHP project.
 *
 * <p>This is the PHP counterpart of {@link EndToEndVerificationTest}: it proves that the same evidence
 * chain works for a language the core knows nothing about. A defect behind a requirement is found by an
 * executed generated test, the counterexample carries the exact inputs, a requirement with no executable
 * verification stays {@code UNVERIFIED}, and a requirement that is genuinely discharged is reported as
 * {@code VERIFIED} - the one status that requires execution.
 *
 * <p>PHP is the one language here whose test tool is not part of the toolchain: the fixture declares
 * PHPUnit in its {@code composer.json}, so the build phase has to resolve it from Packagist before a
 * test can run. That is why the fixture configures {@code sandbox.allow_network: true}, and why the CI
 * job warms the toolchain first - the point of this test is the adapter, not the download.
 */
@Tag("e2e")
class PhpEndToEndVerificationTest extends EndToEndSupport {

    @Test
    @DisplayName("a PHP defect behind a requirement is found and attributed, and an executable requirement passes")
    void verifiesPhpProject(@TempDir Path output) throws IOException {
        assumePhpAvailable();
        Path fixture = requireFixture("php-withdrawal-ledger");

        int exitCode = Main.execute("verify", fixture.toString(),
                "--requirements", fixture.resolve("requirements.md").toString(),
                "--output", output.toString(),
                "--max-duration", "10m",
                "--quiet");

        JsonNode report = readReport(output);
        assertEquals("FAILED", report.path("status").asText(),
                "the fixture contains deliberately introduced defects, so the run must not pass");

        JsonNode rolling = requirement(report, "R-001");
        assertEquals("FAILED", rolling.path("status").asText(),
                "R-001 requires a rolling 24 hour window; the fixture applies the limit per calendar day");
        assertTrue(rolling.path("rationale").asText().contains("counterexample"),
                "the verdict must say why it failed: " + rolling.path("rationale").asText());

        JsonNode counterexample = counterexampleFor(report, "R-001");
        assertNotNull(counterexample, "a counterexample must be recorded for R-001");
        JsonNode inputs = counterexample.path("inputs");
        assertTrue(inputs.has("t1") && inputs.has("t2"),
                "the counterexample must carry the exact instants that reproduce the violation: " + inputs);
        assertTrue(Double.parseDouble(counterexample.path("actual").asText()) > 100000,
                "the observed total across midnight must exceed the 1,000 limit: "
                        + counterexample.path("actual").asText());
        assertFalse(counterexample.path("reproduction").path("command").isEmpty(),
                "a reproducible counterexample must carry the command that replays it");
        assertTrue(counterexample.path("reproduction").path("command").toString().contains("phpunit"),
                "the replay command must be a real PHPUnit invocation: "
                        + counterexample.path("reproduction").path("command"));

        JsonNode negative = requirement(report, "R-003");
        assertEquals("FAILED", negative.path("status").asText(),
                "R-003 requires negative amounts to be rejected; the fixture accepts them");

        JsonNode unverifiable = requirement(report, "R-004");
        assertEquals("UNVERIFIED", unverifiable.path("status").asText(),
                "R-004 declares no scenario, so CodeWitness has no way to execute it and must say so");

        assertEquals("VERIFIED", requirement(report, "R-002").path("status").asText(),
                "R-002 is discharged by an executed generated test, which is the only path to VERIFIED");

        assertEquals(1, exitCode, "a found counterexample must produce exit code 1 (a requirement failed)");
    }

    @Test
    @DisplayName("a PHP project that does not parse is BLOCKED, never reported as incorrect code")
    void syntaxErrorIsBlockedNotFailed(@TempDir Path project, @TempDir Path output) throws IOException {
        assumePhpAvailable();
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/broken.php"), """
                <?php

                function value(: int
                {
                    return 1;
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(project.resolve("requirements.md"), """
                # R-001 The value must be an integer

                The value returned must be representable as an integer.
                """, StandardCharsets.UTF_8);

        int exitCode = Main.execute("verify", project.toString(),
                "--requirements", project.resolve("requirements.md").toString(),
                "--output", output.toString(),
                "--max-duration", "3m", "--no-mutation", "--quiet");

        JsonNode report = readReport(output);
        assertEquals("BLOCKED", requirement(report, "R-001").path("status").asText(),
                "a syntax error must be reported as BLOCKED, not as a code defect");
        assertEquals(2, exitCode, "a blocked verification must produce exit code 2");
        assertTrue(report.path("limitations").toString().contains("could not be built"),
                "the report must explain that the build, not the code, is the problem");
    }

    /** Reads the JSON report written by the most recent run. */
    private JsonNode readReport(Path output) throws IOException {
        List<Path> runs = runDirectories(output);
        assertFalse(runs.isEmpty(), "a run directory must have been created");
        Path report = runs.get(0).resolve("report.json");
        assertTrue(Files.isRegularFile(report), "the canonical JSON report must exist at " + report);
        return new ObjectMapper().readTree(Files.readString(report, StandardCharsets.UTF_8));
    }
}
