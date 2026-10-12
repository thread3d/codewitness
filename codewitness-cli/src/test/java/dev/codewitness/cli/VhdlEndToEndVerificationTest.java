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
 * End-to-end verification of a deliberately defective VHDL design.
 *
 * <p>This is the VHDL counterpart of {@link VerilogEndToEndVerificationTest}: it proves that the same
 * evidence chain works for the other great hardware description language, whose tools are an analyser,
 * an elaborator and a simulator in one executable. A defect behind a requirement is found by an
 * executed generated testbench, the counterexample carries the exact inputs, a requirement with no
 * executable verification stays {@code UNVERIFIED}, and a requirement that is genuinely discharged is
 * reported as {@code VERIFIED} - the one status that requires execution.
 *
 * <p>The fixture needs no network: GHDL is the only tool, and a tree with no dependencies needs nothing
 * else. The fixture's {@code codewitness.yaml} therefore forbids the network, and the CI job only has
 * to install {@code ghdl}.
 */
@Tag("e2e")
class VhdlEndToEndVerificationTest extends EndToEndSupport {

    @Test
    @DisplayName("a VHDL defect behind a requirement is found and attributed, and an executable requirement passes")
    void verifiesVhdlDesign(@TempDir Path output) throws IOException {
        assumeVhdlAvailable();
        Path fixture = requireFixture("vhdl-withdrawal-ledger");

        int exitCode = Main.execute("verify", fixture.toString(),
                "--requirements", fixture.resolve("requirements.md").toString(),
                "--output", output.toString(),
                "--max-duration", "10m",
                "--quiet");

        JsonNode report = readReport(output);
        assertEquals("FAILED", report.path("status").asText(),
                "the fixture contains deliberately introduced defects, so the run must not pass");

        JsonNode debit = requirement(report, "R-001");
        assertEquals("FAILED", debit.path("status").asText(),
                "R-001 requires an accepted withdrawal to reduce the balance exactly; the fixture adds a cent");
        assertTrue(debit.path("rationale").asText().contains("counterexample"),
                "the verdict must say why it failed: " + debit.path("rationale").asText());

        JsonNode counterexample = counterexampleFor(report, "R-001");
        assertNotNull(counterexample, "a counterexample must be recorded for R-001");
        JsonNode inputs = counterexample.path("inputs");
        assertTrue(inputs.has("balance") && inputs.has("amount"),
                "the counterexample must carry the exact signals that reproduce the violation: " + inputs);
        assertEquals("100000", counterexample.path("actual").asText(),
                "the observed balance is the defect: the design returned the balance unchanged");
        assertFalse(counterexample.path("reproduction").path("command").isEmpty(),
                "a reproducible counterexample must carry the command that replays it");
        String replay = counterexample.path("reproduction").path("command").toString();
        assertTrue(replay.contains("ghdl"),
                "the replay command must be a real GHDL analyse-and-simulate invocation: " + replay);

        JsonNode exact = requirement(report, "R-003");
        assertEquals("FAILED", exact.path("status").asText(),
                "R-003 requires a withdrawal of exactly the balance to be accepted; the fixture uses <");

        JsonNode unverifiable = requirement(report, "R-004");
        assertEquals("UNVERIFIED", unverifiable.path("status").asText(),
                "R-004 declares no scenario, so CodeWitness has no way to execute it and must say so");

        assertEquals("VERIFIED", requirement(report, "R-002").path("status").asText(),
                "R-002 is discharged by an executed generated test, which is the only path to VERIFIED");

        JsonNode mutation = report.path("mutation");
        assertTrue(mutation.path("measured").asBoolean(),
                "the existing testbench is weak enough to mutate against: " + mutation);
        assertTrue(mutation.path("generated").asInt() >= 1,
                "at least one mutant must have been generated: " + mutation);
        assertTrue(mutation.path("killed").asInt() >= 1,
                "the fixture's own testbench does check the rejected branch, so the mutant that swaps the "
                        + "comparison is detected by it: " + mutation);
        assertTrue(mutation.path("survived").asInt() >= 1,
                "the fixture's own testbench checks only the rejected branch, so mutants survive: " + mutation);

        assertEquals(1, exitCode, "a found counterexample must produce exit code 1 (a requirement failed)");
    }

    @Test
    @DisplayName("a design that does not analyse is BLOCKED, never reported as incorrect code")
    void analysisErrorIsBlockedNotFailed(@TempDir Path project, @TempDir Path output) throws IOException {
        assumeVhdlAvailable();
        Files.createDirectories(project.resolve("rtl"));
        Files.createDirectories(project.resolve("tb"));
        Files.writeString(project.resolve("rtl/broken.vhd"), """
                library ieee;
                use ieee.std_logic_1164.all;

                entity broken is
                  port (a : in std_logic; y : out std_logic);
                end entity broken;

                architecture rtl of broken is
                begin
                  -- a syntax error: the right operand of `and` is missing
                  y <= a and ;
                end architecture rtl;
                """, StandardCharsets.UTF_8);
        Files.writeString(project.resolve("tb/broken_tb.vhd"), """
                library ieee;
                use ieee.std_logic_1164.all;
                use std.env.all;

                entity broken_tb is
                end entity broken_tb;

                architecture sim of broken_tb is
                  signal a : std_logic := '0';
                  signal y : std_logic;
                begin
                  dut : entity work.broken port map (a => a, y => y);
                  process
                  begin
                    wait for 1 ns;
                    std.env.finish;
                  end process;
                end architecture sim;
                """, StandardCharsets.UTF_8);
        Files.writeString(project.resolve("requirements.md"), """
                # R-001 The value must be driven

                The design must drive the output from the input.
                """, StandardCharsets.UTF_8);

        int exitCode = Main.execute("verify", project.toString(),
                "--requirements", project.resolve("requirements.md").toString(),
                "--output", output.toString(),
                "--max-duration", "5m", "--no-mutation", "--quiet");

        JsonNode report = readReport(output);
        assertEquals("BLOCKED", requirement(report, "R-001").path("status").asText(),
                "an analysis error must be reported as BLOCKED, not as a code defect");
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
