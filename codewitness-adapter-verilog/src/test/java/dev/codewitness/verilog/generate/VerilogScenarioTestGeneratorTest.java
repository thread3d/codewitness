package dev.codewitness.verilog.generate;

import dev.codewitness.core.config.Scenario;
import dev.codewitness.core.requirements.ObligationKind;
import dev.codewitness.core.requirements.ObligationOrigin;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.verilog.VerilogTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rendering of declared scenarios as executable Verilog testbenches. */
class VerilogScenarioTestGeneratorTest {

    private static final String DESIGN = """
            module ledger (
                input  wire [31:0] balance,
                input  wire [31:0] amount,
                output reg         accepted,
                output reg  [31:0] new_balance
            );
              always @(*) begin
                if (amount <= balance) begin
                  accepted = 1'b1;
                  new_balance = balance - amount;
                end else begin
                  accepted = 1'b0;
                  new_balance = balance;
                end
              end
            endmodule
            """;

    private static Scenario scenario() {
        return new Scenario(
                "SC-DEBIT", "R-001", "an accepted withdrawal reduces the balance exactly", Scenario.Mode.SINGLE,
                "",
                List.of(new Scenario.Step(";", List.of(
                        new Scenario.Input("balance", "integer", Scenario.Domain.INTEGER, List.of("100000"),
                                "100000", "100000", 0),
                        new Scenario.Input("amount", "integer", Scenario.Domain.INTEGER, List.of("1", "99999"),
                                "1", "99999", 0)), "the inputs are the whole invocation")),
                "dut.new_balance",
                "dut.accepted == 1 && observation == balance - amount",
                1, 1L, 60);
    }

    private static VerificationObligation obligation() {
        return new VerificationObligation("OB-001", "R-001", "execute scenario",
                ObligationKind.BOUNDARY_VALUE, ObligationOrigin.USER_DECLARED, "SC-DEBIT",
                Map.of("scenario", "SC-DEBIT"), false);
    }

    @Test
    @DisplayName("a scenario becomes a testbench that instantiates the design and reports the protocol")
    void rendersScenario(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        VerilogTestContexts.write(root, "rtl/ledger.v", DESIGN);
        Scenario scenario = scenario();
        Requirement requirement = Requirement.builder("R-001", "exact debit").title("Exact debit").build();
        VerificationContext context = VerilogTestContexts.context(root, runDirectory, List.of(scenario));

        List<GeneratedTest> generated =
                new VerilogScenarioTestGenerator().generate(context, requirement, List.of(obligation()));

        assertEquals(1, generated.size());
        GeneratedTest test = generated.getFirst();
        assertEquals("cw_sc_sc_debit_ob_001", test.className());
        assertEquals("cw_sc_sc_debit_ob_001", test.testMethod());
        assertTrue(test.checksInvariant(), "the scenario declares an observation and an invariant");

        String source = test.sourceCode();
        assertTrue(source.contains("module cw_sc_sc_debit_ob_001;"), source);
        assertTrue(source.contains("ledger dut ("), "the design must be instantiated: " + source);
        assertTrue(source.contains(".balance(balance)"), source);
        assertTrue(source.contains("reg [31:0] balance;"), source);
        assertTrue(source.contains("wire accepted;"), source);
        assertTrue(source.contains("balance = 100000;"), source);
        assertTrue(source.contains("amount = 1;"), source);
        assertTrue(source.contains("#1;"), "the observation must wait for combinational settling: " + source);
        assertTrue(source.contains("observation = dut.new_balance;"), source);
        assertTrue(source.contains("if (!(dut.accepted == 1 && observation == balance - amount))"), source);
        assertTrue(source.contains("CODEWITNESS_COUNTEREXAMPLE"), source);
        assertTrue(source.contains("$fatal;"), source);
        assertFalse(source.contains("${"), "no placeholder may survive into generated code:\n" + source);
    }

    @Test
    @DisplayName("a percent in the invariant is doubled so $display does not read it as a conversion")
    void escapesPercentInFormat(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        VerilogTestContexts.write(root, "rtl/ledger.v", DESIGN);
        Scenario scenario = new Scenario(
                "SC-MOD", "R-001", "modulo", Scenario.Mode.SINGLE, "",
                List.of(new Scenario.Step(";", List.of(
                        new Scenario.Input("balance", "integer", Scenario.Domain.INTEGER, List.of("10"),
                                "10", "10", 0),
                        new Scenario.Input("amount", "integer", Scenario.Domain.INTEGER, List.of("3"),
                                "3", "3", 0)), "step")),
                "dut.new_balance", "observation % 2 == 0", 1, 1L, 60);
        VerificationContext context = VerilogTestContexts.context(root, runDirectory, List.of(scenario));

        GeneratedTest test = new VerilogScenarioTestGenerator()
                .generate(context, Requirement.builder("R-001", "modulo").build(),
                        List.of(new VerificationObligation("OB-001", "R-001", "modulo",
                                ObligationKind.BOUNDARY_VALUE, ObligationOrigin.USER_DECLARED, "SC-MOD",
                                Map.of("scenario", "SC-MOD"), false)))
                .getFirst();

        assertTrue(test.sourceCode().contains("invariant=observation %% 2 == 0"),
                "the format string must escape the modulo operator: " + test.sourceCode());
    }

    @Test
    @DisplayName("a design with no ports yields no generated test rather than a broken one")
    void noDesignYieldsNothing(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        VerilogTestContexts.write(root, "tb/only_tb.v", """
                module only_tb;
                  initial $finish;
                endmodule
                """);
        VerificationContext context = VerilogTestContexts.context(root, runDirectory, List.of(scenario()));

        List<GeneratedTest> generated = new VerilogScenarioTestGenerator()
                .generate(context, Requirement.builder("R-001", "x").build(), List.of(obligation()));
        assertTrue(generated.isEmpty(), "without a design under test there is nothing to instantiate");
    }

    @Test
    @DisplayName("boundary values are the cartesian product of the declared candidates, capped")
    void boundaryCases() {
        List<Scenario.Input> inputs = List.of(
                new Scenario.Input("a", "integer", Scenario.Domain.INTEGER, List.of("1", "2"), null, null, 0),
                new Scenario.Input("b", "integer", Scenario.Domain.INTEGER, List.of("3", "4"), null, null, 0));
        List<Map<String, String>> cases = VerilogScenarioTestGenerator.boundaryCases(inputs);
        assertTrue(cases.size() <= 12, "the cap must hold: " + cases.size());
        assertTrue(cases.size() >= 4, "both inputs must contribute: " + cases.size());
    }

    @Test
    @DisplayName("a declared input type is used, and only integral types are produced")
    void declarationFromType() {
        assertEquals("integer", VerilogScenarioTestGenerator.declarationFromType("integer"));
        assertEquals("time", VerilogScenarioTestGenerator.declarationFromType("time"));
        assertEquals("reg [7:0]", VerilogScenarioTestGenerator.declarationFromType("reg [7:0]"));
        assertEquals("logic signed [3:0]", VerilogScenarioTestGenerator.declarationFromType("logic signed [3:0]"));
        assertEquals("reg [31:0]", VerilogScenarioTestGenerator.declarationFromType("[31:0]"));
        // real has no %0d conversion, so it falls back to an integral register rather than breaking $display.
        assertEquals("reg [63:0]", VerilogScenarioTestGenerator.declarationFromType("real"));
        assertEquals("reg [63:0]", VerilogScenarioTestGenerator.declarationFromType(null));
    }
}
