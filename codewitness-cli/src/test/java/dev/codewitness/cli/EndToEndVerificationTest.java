package dev.codewitness.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Assumptions;
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
 * End-to-end verification of deliberately defective projects.
 *
 * <p>These tests are the product's central claim in executable form: CodeWitness must find the
 * introduced defect, produce a counterexample that identifies the exact input, associate it with the
 * right requirement, and leave a requirement it cannot execute as {@code UNVERIFIED} rather than
 * pretending it passed.
 *
 * <p>They are tagged {@code e2e} because they compile and run real Maven projects, which needs the
 * network the first time. Run them with {@code mvn -Pe2e test}.
 */
@Tag("e2e")
class EndToEndVerificationTest extends EndToEndSupport {

    @Test
    @DisplayName("a defect introduced behind a requirement is found, explained and associated with that requirement")
    void findsIntroducedDefect(@TempDir Path output) throws IOException {
        Path fixture = requireFixture("withdrawal-ledger");

        int exitCode = Main.execute("verify", fixture.toString(),
                "--requirements", fixture.resolve("requirements.md").toString(),
                "--output", output.toString(),
                "--max-duration", "10m",
                "--quiet");

        JsonNode report = readReport(output);
        assertEquals("FAILED", report.path("status").asText(),
                "the fixture contains deliberately introduced defects, so the run must not pass");

        JsonNode verdict = requirement(report, "R-001");
        assertEquals("FAILED", verdict.path("status").asText(),
                "R-001 requires a rolling 24 hour window; the fixture applies the limit per calendar day");
        assertTrue(verdict.path("rationale").asText().contains("counterexample"),
                "the verdict must say why it failed: " + verdict.path("rationale").asText());

        JsonNode counterexample = counterexampleFor(report, "R-001");
        assertNotNull(counterexample, "a counterexample must be recorded for R-001");
        JsonNode inputs = counterexample.path("inputs");
        assertTrue(inputs.has("t1") && inputs.has("t2"),
                "the counterexample must carry the exact instants that reproduce the violation: " + inputs);
        assertEquals("1400", counterexample.path("actual").asText().replaceAll("\\.0+$", ""),
                "the observed total across midnight must exceed the 1,000 limit");
        assertTrue(counterexample.path("expected").asText().contains("1000"),
                "the counterexample must state what was expected");
        assertTrue(counterexample.path("reproduction").path("command").isArray(),
                "a reproducible counterexample must carry the command that replays it");
        assertFalse(counterexample.path("reproduction").path("command").isEmpty(),
                "the reproduction command must not be empty");

        assertEquals(1, exitCode, "a found counterexample must produce exit code 1 (a requirement failed)");
    }

    @Test
    @DisplayName("a requirement with no executable verification stays UNVERIFIED instead of passing")
    void unverifiableRequirementStaysUnverified(@TempDir Path output) throws IOException {
        Path fixture = requireFixture("withdrawal-ledger");

        Main.execute("verify", fixture.toString(),
                "--requirements", fixture.resolve("requirements.md").toString(),
                "--output", output.toString(),
                "--max-duration", "10m",
                "--quiet");

        JsonNode report = readReport(output);
        JsonNode verdict = requirement(report, "R-005");
        assertEquals("UNVERIFIED", verdict.path("status").asText(),
                "R-005 declares no scenario, so CodeWitness has no way to execute it and must say so");
        assertTrue(verdict.path("rationale").asText().contains("UNVERIFIED")
                        || verdict.path("rationale").asText().contains("no way to execute"),
                "the verdict must explain the gap: " + verdict.path("rationale").asText());
        assertTrue(report.path("limitations").isArray() && report.path("limitations").size() > 0,
                "the report must list what it could not verify");
    }

    @Test
    @DisplayName("a project that does not compile is BLOCKED, never reported as incorrect code")
    void buildFailureIsBlockedNotFailed(@TempDir Path project, @TempDir Path output) throws IOException {
        assumeBuildToolAvailable();
        Files.createDirectories(project.resolve("src/main/java/com/example"));
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>broken</artifactId>
                  <version>1.0.0</version>
                  <properties><maven.compiler.release>17</maven.compiler.release></properties>
                </project>
                """, StandardCharsets.UTF_8);
        // A syntax error, so that compilation - not the code's behaviour - is what fails.
        Files.writeString(project.resolve("src/main/java/com/example/Broken.java"), """
                package com.example;

                public class Broken {
                    public int value() { return "not an int"; }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(project.resolve("requirements.md"), """
                # R-001 The value must be an integer

                The value returned by Broken must be representable as an integer.
                """, StandardCharsets.UTF_8);

        int exitCode = Main.execute("verify", project.toString(),
                "--requirements", project.resolve("requirements.md").toString(),
                "--output", output.toString(),
                "--max-duration", "5m", "--no-mutation", "--quiet");

        JsonNode report = readReport(output);
        assertEquals("BLOCKED", requirement(report, "R-001").path("status").asText(),
                "a compile error must be reported as BLOCKED, not as a code defect");
        assertEquals(2, exitCode, "a blocked verification must produce exit code 2");
        assertTrue(report.path("limitations").toString().contains("could not be built"),
                "the report must explain that the build, not the code, is the problem");
    }

    @Test
    @DisplayName("running without any claims verifies nothing rather than reporting success")
    void withoutRequirementsNothingIsVerified(@TempDir Path output, @TempDir Path emptyRequirements)
            throws IOException {
        Path fixture = requireFixture("withdrawal-ledger");
        // An empty requirements file is the "no claims" case. (Omitting --requirements entirely is not
        // the same thing: the CLI then looks for the conventional requirements.md, which this fixture
        // has.)
        Path empty = emptyRequirements.resolve("empty-requirements.md");
        Files.writeString(empty, "# No requirements yet\n", StandardCharsets.UTF_8);

        // --no-tests keeps this fast; the point is what the report says about having no claims.
        int exitCode = Main.execute("verify", fixture.toString(),
                "--requirements", empty.toString(),
                "--output", output.toString(),
                "--max-duration", "5m", "--no-mutation", "--no-generated-tests", "--no-tests", "--quiet");

        JsonNode report = readReport(output);
        assertEquals(0, report.path("requirements").size(), "no requirements were supplied");
        assertFalse(report.path("status").asText().equals("VERIFIED"),
                "an empty claim set must never be reported as VERIFIED");
        assertEquals(0, exitCode, "nothing was claimed, so nothing failed");
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
