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
 * End-to-end verification of a deliberately defective Pascal unit.
 *
 * <p>The fixture's defect is one cent of drift in an accepted withdrawal, which violates R-001
 * (a withdrawal reduces the balance by exactly the amount) and R-003 (a withdrawal of exactly the
 * balance empties the account). R-002, the rejection branch, is correct and should be verified. R-004
 * declares nothing executable and must stay unverified rather than be assumed.
 *
 * <p>The project's own fpcunit suite asserts both the arithmetic and the boundary, so two of its four
 * tests fail on the defective code - which the report must say, and which is also what makes the
 * mutation phase meaningful: each mutant of the arithmetic or the boundary is caught by a failing test.
 *
 * <p>Free Pascal and its bundled fpcunit are the only tools required. The test skips when no compiler is
 * installed rather than failing spuriously.
 */
@Tag("e2e")
class PascalEndToEndVerificationTest extends EndToEndSupport {

    @Test
    @DisplayName("a one-cent defect is refuted by a generated test and by the project's own suite")
    void verifiesAPascalUnit(@TempDir Path output) throws IOException {
        assumePascalAvailable();
        Path fixture = requireFixture("pascal-withdrawal-ledger");

        int exitCode = Main.execute("verify", fixture.toString(),
                "--requirements", fixture.resolve("requirements.md").toString(),
                "--output", output.toString(),
                "--max-duration", "10m",
                "--quiet");

        JsonNode report = readReport(output);
        assertReportLooksComplete(report);
        assertEquals("FAILED", report.path("status").asText(),
                "the fixture contains a one-cent defect, so the run must not pass");
        assertEquals(1, exitCode, "a reproduced counterexample is exit code 1");
        assertEquals("pascal", report.path("project").path("language").asText());

        // R-001: the accepted branch subtracts an extra cent, which a generated test finds by comparing the
        // result with the balance minus the amount.
        JsonNode arithmetic = requirement(report, "R-001");
        assertEquals("FAILED", arithmetic.path("status").asText(), arithmetic.path("rationale").asText());
        JsonNode counterexample = counterexampleFor(report, "R-001");
        assertNotNull(counterexample, "a counterexample must be recorded for R-001");
        assertFalse(counterexample.path("actual").asText().isBlank(),
                "the counterexample must carry what was observed");

        // R-002: the rejection branch is correct, so the generated test passes and the requirement is
        // VERIFIED - the one status that requires an executed experiment.
        assertEquals("VERIFIED", requirement(report, "R-002").path("status").asText(),
                requirement(report, "R-002").path("rationale").asText());

        // R-003: the boundary is accepted, but the balance left behind is -1 rather than 0.
        JsonNode boundary = requirement(report, "R-003");
        assertEquals("FAILED", boundary.path("status").asText(), boundary.path("rationale").asText());
        assertNotNull(counterexampleFor(report, "R-003"), "a counterexample must be recorded for R-003");

        // R-004 declares nothing executable, so it must stay UNVERIFIED rather than be assumed.
        assertEquals("UNVERIFIED", requirement(report, "R-004").path("status").asText(),
                requirement(report, "R-004").path("rationale").asText());

        // The project's own suite runs, and the defect makes two of its four tests fail.
        JsonNode tests = report.path("tests");
        assertTrue(tests.path("executed").asBoolean(), tests.toString());
        assertEquals(4, tests.path("total").asInt(), tests.toString());
        assertEquals(2, tests.path("failed").asInt(), tests.toString());

        // Every mutant of the arithmetic or the boundary is caught by a failing test, so none survives.
        JsonNode mutation = report.path("mutation");
        assertTrue(mutation.path("measured").asBoolean(), mutation.toString());
        assertEquals(3, mutation.path("generated").asInt(), mutation.toString());
        assertEquals(3, mutation.path("killed").asInt(), mutation.toString());
        assertEquals(0, mutation.path("survived").asInt(), mutation.toString());

        assertTrue(hasGeneratedTestEvidence(report),
                "the generated tests that verified R-002 and refuted R-001 must be recorded as executed evidence");
        assertTrue(Files.isRegularFile(runDirectory(output).resolve("report.html")),
                "the HTML report must be written");
    }

    private static boolean hasGeneratedTestEvidence(JsonNode report) {
        for (JsonNode node : report.path("evidence")) {
            if ("GENERATED_TEST".equals(node.path("type").asText())) {
                return true;
            }
        }
        return false;
    }

    /** @return the most recent run directory. */
    private Path runDirectory(Path output) {
        List<Path> runs = runDirectories(output);
        assertFalse(runs.isEmpty(), "a run directory must have been created");
        return runs.get(0);
    }

    /** Reads the JSON report written by the most recent run. */
    private JsonNode readReport(Path output) throws IOException {
        Path report = runDirectory(output).resolve("report.json");
        assertTrue(Files.isRegularFile(report), "the canonical JSON report must exist at " + report);
        return new ObjectMapper().readTree(Files.readString(report, StandardCharsets.UTF_8));
    }
}
