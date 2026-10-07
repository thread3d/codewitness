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
 * End-to-end verification of a deliberately defective C++ project.
 *
 * <p>This is the C and C++ counterpart of {@link GoEndToEndVerificationTest}: it proves that the same
 * evidence chain works for a CMake build. A defect behind a requirement is found by a generated C++ program
 * that CodeWitness compiled with the project's own compiler and flags, the counterexample carries the exact
 * inputs and a command that replays it, a requirement with no executable verification stays
 * {@code UNVERIFIED}, and a requirement that is genuinely discharged is reported as {@code VERIFIED} - the
 * one status that requires execution.
 *
 * <p>It needs CMake and CTest, which the fixture drives through the project's own {@code CMakeLists.txt},
 * and the fixture's sandbox configuration forbids the network.
 */
@Tag("e2e")
class CppEndToEndVerificationTest extends EndToEndSupport {

    @Test
    @DisplayName("two C++ defects behind requirements are found and attributed, and an executable requirement passes")
    void verifiesCppProject(@TempDir Path output) throws IOException {
        assumeCmakeAvailable();
        Path fixture = requireFixture("cpp-withdrawal-ledger");

        int exitCode = Main.execute("verify", fixture.toString(),
                "--requirements", fixture.resolve("requirements.md").toString(),
                "--output", output.toString(),
                "--max-duration", "10m",
                "--no-mutation",
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
        assertTrue(Long.parseLong(counterexample.path("actual").asText()) > 100_000,
                "the observed total across midnight must exceed the 100,000 limit: "
                        + counterexample.path("actual").asText());
        assertFalse(counterexample.path("reproduction").path("command").isEmpty(),
                "a reproducible counterexample must carry the command that replays it");
        assertTrue(counterexample.path("reproduction").path("command").toString().contains("CODEWITNESS_SEED"),
                "the replay command must pin the seed and the case the run produced: "
                        + counterexample.path("reproduction").path("command"));

        JsonNode negative = requirement(report, "R-003");
        assertEquals("FAILED", negative.path("status").asText(),
                "R-003 requires negative amounts to be rejected; the fixture accepts them");

        assertEquals("UNVERIFIED", requirement(report, "R-004").path("status").asText(),
                "R-004 declares no scenario, so CodeWitness has no way to execute it and must say so");

        assertEquals("VERIFIED", requirement(report, "R-002").path("status").asText(),
                "R-002 is discharged by an executed generated test, which is the only path to VERIFIED");

        assertEquals(1, exitCode, "a found counterexample must produce exit code 1 (a requirement failed)");
    }

    @Test
    @DisplayName("the C++ fixture's own CTest suite and its coverage are measured, not assumed")
    void measuresTheProjectsOwnEvidence(@TempDir Path output) throws IOException {
        assumeCmakeAvailable();
        Path fixture = requireFixture("cpp-withdrawal-ledger");

        Main.execute("verify", fixture.toString(),
                "--requirements", fixture.resolve("requirements.md").toString(),
                "--output", output.toString(),
                "--max-duration", "10m",
                "--no-mutation",
                "--quiet");

        JsonNode report = readReport(output);
        JsonNode tests = report.path("tests");
        assertTrue(tests.path("executed").asBoolean(), "the fixture registers a test with add_test: " + tests);
        assertEquals(1, tests.path("total").asInt(),
                "the fixture's one test binary is one CTest test: " + tests);
        assertEquals(1, tests.path("passed").asInt(), tests.toString());

        JsonNode coverage = report.path("coverage");
        assertTrue(coverage.path("measured").asBoolean(),
                "the build is instrumented with --coverage and read with gcov: " + coverage);
        assertEquals("gcov-coverage", coverage.path("tool").asText());
        assertTrue(coverage.path("lineTotal").asInt() > 0, coverage.toString());
        assertTrue(coverage.path("lineCoveragePercent").asDouble() > 50.0,
                "the fixture's own test exercises most of the ledger: " + coverage);
        for (var entry : coverage.path("perClassLineCoverage").properties()) {
            assertTrue(entry.getKey().startsWith("src/") || entry.getKey().startsWith("tests/"),
                    "only the project's own sources are measured, not the standard library: " + entry.getKey());
        }
    }

    @Test
    @DisplayName("a C++ project that does not compile is BLOCKED, never reported as incorrect code")
    void compileErrorIsBlockedNotFailed(@TempDir Path project, @TempDir Path output) throws IOException {
        assumeCmakeAvailable();
        Files.writeString(project.resolve("CMakeLists.txt"), """
                cmake_minimum_required(VERSION 3.20)
                project(broken LANGUAGES CXX)

                add_library(broken STATIC src/broken.cpp)
                """, StandardCharsets.UTF_8);
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/broken.cpp"), """
                long long value() {
                    return "not a number";
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
                "a compile error must be reported as BLOCKED, not as a code defect");
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
