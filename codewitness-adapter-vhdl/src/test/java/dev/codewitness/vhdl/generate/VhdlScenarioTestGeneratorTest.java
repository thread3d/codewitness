package dev.codewitness.vhdl.generate;

import dev.codewitness.core.config.Scenario;
import dev.codewitness.core.requirements.ObligationKind;
import dev.codewitness.core.requirements.ObligationOrigin;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.vhdl.VhdlTestContexts;

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

/** Rendering of declared scenarios as executable VHDL testbenches. */
class VhdlScenarioTestGeneratorTest {

    private static final String DESIGN = """
            library ieee;
            use ieee.std_logic_1164.all;
            use ieee.numeric_std.all;

            entity ledger is
              port (
                balance     : in  unsigned(31 downto 0);
                amount      : in  unsigned(31 downto 0);
                accepted    : out std_logic;
                new_balance : out unsigned(31 downto 0)
              );
            end entity ledger;

            architecture rtl of ledger is
            begin
              process(all)
              begin
                if amount <= balance then
                  accepted    <= '1';
                  new_balance <= balance - amount;
                else
                  accepted    <= '0';
                  new_balance <= balance;
                end if;
              end process;
            end architecture rtl;
            """;

    private static Scenario scenario() {
        return new Scenario(
                "SC-DEBIT", "R-001", "an accepted withdrawal reduces the balance exactly", Scenario.Mode.SINGLE,
                "",
                List.of(new Scenario.Step("null;", List.of(
                        new Scenario.Input("balance", "integer", Scenario.Domain.INTEGER, List.of("100000"),
                                "100000", "100000", 0),
                        new Scenario.Input("amount", "integer", Scenario.Domain.INTEGER, List.of("1", "99999"),
                                "1", "99999", 0)), "the inputs are the whole invocation")),
                "to_integer(new_balance)",
                "accepted = '1' and observation = to_integer(balance) - to_integer(amount)",
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
        VhdlTestContexts.write(root, "rtl/ledger.vhd", DESIGN);
        Scenario scenario = scenario();
        Requirement requirement = Requirement.builder("R-001", "exact debit").title("Exact debit").build();
        VerificationContext context = VhdlTestContexts.context(root, runDirectory, List.of(scenario));

        List<GeneratedTest> generated =
                new VhdlScenarioTestGenerator().generate(context, requirement, List.of(obligation()));

        assertEquals(1, generated.size());
        GeneratedTest test = generated.getFirst();
        assertEquals("cw_sc_sc_debit_ob_001", test.className());
        assertEquals("cw_sc_sc_debit_ob_001", test.testMethod());
        assertTrue(test.checksInvariant(), "the scenario declares an observation and an invariant");

        String source = test.sourceCode();
        assertTrue(source.contains("entity cw_sc_sc_debit_ob_001 is"), source);
        assertTrue(source.contains("entity work.ledger port map (balance => balance"), source);
        assertTrue(source.contains("signal balance : unsigned(31 downto 0);"), source);
        assertTrue(source.contains("signal accepted : std_logic;"), source);
        assertTrue(source.contains("begin\n\n  dut : entity work.ledger"),
                "the instantiation must be a concurrent statement after `begin`: " + source);
        assertTrue(source.contains("cw_balance := 100000;"), source);
        assertTrue(source.contains("balance <= to_unsigned(cw_balance, 32);"), source);
        assertTrue(source.contains("cw_amount := 1;"), source);
        assertTrue(source.contains("wait for 1 ns;"), "the observation must wait for settling: " + source);
        assertTrue(source.contains("observation := to_integer(new_balance);"), source);
        assertTrue(source.contains(
                "if not (accepted = '1' and observation = to_integer(balance) - to_integer(amount)) then"), source);
        assertTrue(source.contains("CODEWITNESS_COUNTEREXAMPLE"), source);
        assertTrue(source.contains("CODEWITNESS_GENERATED_TEST_FAILED|requirement=R-001|obligation=OB-001"), source);
        assertTrue(source.contains("severity failure;"), source);
        assertTrue(source.contains("std.env.finish;"), source);
        assertFalse(source.contains("${"), "no placeholder may survive into generated code:\n" + source);
    }

    @Test
    @DisplayName("a clock input the scenario never mentions is driven by a generated clock process")
    void injectsClock(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        VhdlTestContexts.write(root, "rtl/counter.vhd", """
                library ieee;
                use ieee.std_logic_1164.all;

                entity counter is
                  port (clk : in std_logic; d : in std_logic; q : out std_logic);
                end entity counter;

                architecture rtl of counter is
                begin
                  q <= d;
                end architecture rtl;
                """);
        Scenario scenario = new Scenario(
                "SC-CLK", "R-001", "clocked", Scenario.Mode.SINGLE, "",
                List.of(new Scenario.Step("null;", List.of(
                        new Scenario.Input("d", "integer", Scenario.Domain.INTEGER, List.of("1"),
                                "1", "1", 0)), "step")),
                "to_integer(unsigned'('0' & q))",
                "observation = 1", 1, 1L, 60);
        VerificationContext context = VhdlTestContexts.context(root, runDirectory, List.of(scenario));

        GeneratedTest test = new VhdlScenarioTestGenerator()
                .generate(context, Requirement.builder("R-001", "x").build(),
                        List.of(new VerificationObligation("OB-001", "R-001", "x",
                                ObligationKind.BOUNDARY_VALUE, ObligationOrigin.USER_DECLARED, "SC-CLK",
                                Map.of("scenario", "SC-CLK"), false)))
                .getFirst();
        assertTrue(test.sourceCode().contains("clock : process"), test.sourceCode());
        assertTrue(test.sourceCode().contains("clk <= not clk;"), test.sourceCode());
        assertTrue(test.sourceCode().contains("d <= '1' when cw_d /= 0 else '0';"), test.sourceCode());
    }

    @Test
    @DisplayName("a design with no ports yields no generated test rather than a broken one")
    void noDesignYieldsNothing(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        VhdlTestContexts.write(root, "tb/only_tb.vhd", """
                entity only_tb is
                end entity only_tb;
                """);
        VerificationContext context = VhdlTestContexts.context(root, runDirectory, List.of(scenario()));

        List<GeneratedTest> generated = new VhdlScenarioTestGenerator()
                .generate(context, Requirement.builder("R-001", "x").build(), List.of(obligation()));
        assertTrue(generated.isEmpty(), "without a design under test there is nothing to instantiate");
    }

    @Test
    @DisplayName("boundary values are the cartesian product of the declared candidates, capped")
    void boundaryCases() {
        List<Scenario.Input> inputs = List.of(
                new Scenario.Input("a", "integer", Scenario.Domain.INTEGER, List.of("1", "2"), null, null, 0),
                new Scenario.Input("b", "integer", Scenario.Domain.INTEGER, List.of("3", "4"), null, null, 0));
        List<Map<String, String>> cases = VhdlScenarioTestGenerator.boundaryCases(inputs);
        assertTrue(cases.size() <= 12, "the cap must hold: " + cases.size());
        assertTrue(cases.size() >= 4, "both inputs must contribute: " + cases.size());
    }

    @Test
    @DisplayName("a random search needs a bounded, non-negative range on every input")
    void canSearch() {
        assertTrue(VhdlScenarioTestGenerator.canSearch(List.of(
                new Scenario.Input("a", "integer", Scenario.Domain.INTEGER, List.of(), "1", "10", 0))));
        assertFalse(VhdlScenarioTestGenerator.canSearch(List.of(
                new Scenario.Input("a", "integer", Scenario.Domain.INTEGER, List.of(), "1", null, 0))));
        assertFalse(VhdlScenarioTestGenerator.canSearch(List.of(
                new Scenario.Input("a", "integer", Scenario.Domain.INTEGER, List.of(), "-5", "5", 0))));
    }

    @Test
    @DisplayName("the class name is a valid VHDL identifier for any scenario and obligation")
    void classNames() {
        assertEquals("cw_sc_sc_debit_ob_001", VhdlScenarioTestGenerator.classNameFor("SC-DEBIT", "OB-001"));
        assertEquals("case", VhdlScenarioTestGenerator.sanitize("---"));
        assertEquals("a_b", VhdlScenarioTestGenerator.sanitize("a.b"));
    }
}
