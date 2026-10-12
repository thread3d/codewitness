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
 * End-to-end verification of a deliberately defective SQL schema.
 *
 * <p>This is the SQL counterpart of {@link VerilogEndToEndVerificationTest}, and it is the only
 * end-to-end fixture that needs <em>no</em> toolchain: the schema is loaded into SQLite through the JDBC
 * driver already in the build, so unlike the fixtures that require a compiler or a runtime, this one
 * never skips. That is what makes it the case that keeps SQL covered in every CI job.
 *
 * <p>The fixture's defect is a constraint the schema does not declare. R-001 states a floor the schema
 * never enforces, so the probe built from the requirement - not from the schema - inserts a value below
 * it, finds the insert accepted, and reports the counterexample. R-002 states a bound the schema does
 * enforce, and the same kind of probe passes, which is the one path that can make a SQL requirement
 * {@code VERIFIED}. R-003 is refuted by the project's own assertion script, and R-004 declares nothing
 * executable and stays {@code UNVERIFIED}.
 */
@Tag("e2e")
class SqlEndToEndVerificationTest extends EndToEndSupport {

    @Test
    @DisplayName("a missing constraint is found by a generated probe and by the project's own script")
    void verifiesASqlSchema(@TempDir Path output) throws IOException {
        Path fixture = requireFixture("sql-withdrawal-ledger");

        int exitCode = Main.execute("verify", fixture.toString(),
                "--requirements", fixture.resolve("requirements.md").toString(),
                "--output", output.toString(),
                "--max-duration", "10m",
                "--quiet");

        JsonNode report = readReport(output);
        assertReportLooksComplete(report);
        assertEquals("FAILED", report.path("status").asText(),
                "the fixture contains a deliberately missing constraint, so the run must not pass");
        assertEquals(1, exitCode, "a reproduced counterexample is exit code 1");
        assertEquals("sql", report.path("project").path("language").asText());

        // R-001 states a floor the schema does not enforce. The probe is built from the requirement's own
        // bound, so it inserts a balance below it and finds the database accepts it - which is the defect
        // a probe built from the schema alone could never see.
        JsonNode floor = requirement(report, "R-001");
        assertEquals("FAILED", floor.path("status").asText(), floor.path("rationale").asText());
        JsonNode floorCounterexample = counterexampleFor(report, "R-001");
        assertNotNull(floorCounterexample, "a counterexample must be recorded for R-001");
        assertTrue(floorCounterexample.path("observedBy").asText().contains("cw_r_001"),
                "the counterexample must be the generated probe: " + floorCounterexample.path("observedBy"));
        assertTrue(floorCounterexample.path("actual").asText().contains("balance"),
                "the counterexample must name the statement that was wrongly accepted: "
                        + floorCounterexample.path("actual").asText());

        // R-002 states a bound the schema does enforce, so the same kind of probe passes and the
        // requirement is VERIFIED - the one status that requires an executed experiment.
        assertEquals("VERIFIED", requirement(report, "R-002").path("status").asText(),
                requirement(report, "R-002").path("rationale").asText());

        // R-003 is refuted by the project's own assertion script, and the offending row is the
        // counterexample.
        JsonNode overdraft = requirement(report, "R-003");
        assertEquals("FAILED", overdraft.path("status").asText(), overdraft.path("rationale").asText());
        JsonNode row = counterexampleFor(report, "R-003");
        assertNotNull(row, "a counterexample must be recorded for R-003");
        assertTrue(row.path("actual").asText().contains("balance=-50"), row.path("actual").asText());

        // R-004 declares nothing executable, so it must stay UNVERIFIED rather than be assumed.
        assertEquals("UNVERIFIED", requirement(report, "R-004").path("status").asText(),
                requirement(report, "R-004").path("rationale").asText());

        assertTrue(hasGeneratedTestEvidence(report),
                "the probes that verified R-002 and refuted R-001 must be recorded as executed evidence");

        // The fixture's own scripts exercise the declared bound from both sides, so they kill both schema
        // mutants: the boundary moved one step and the check removed altogether. A suite that ignored the
        // boundary would report them as survivors instead, which is the difference this asserts.
        JsonNode mutation = report.path("mutation");
        assertTrue(mutation.path("measured").asBoolean(), mutation.toString());
        assertEquals(2, mutation.path("generated").asInt(), mutation.toString());
        assertEquals(2, mutation.path("killed").asInt(), mutation.toString());
        assertEquals(0, mutation.path("survived").asInt(), mutation.toString());
        JsonNode tests = report.path("tests");
        assertTrue(tests.path("executed").asBoolean(), tests.toString());
        assertEquals(2, tests.path("total").asInt(), tests.toString());
        assertEquals(1, tests.path("failed").asInt(), tests.toString());

        assertTrue(Files.isRegularFile(runDirectory(output).resolve("report.html")),
                "the HTML report must be written; a runner with no note of its own must not abort it");
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
