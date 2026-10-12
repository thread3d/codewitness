package dev.codewitness.vhdl.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.vhdl.VhdlTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The VHDL rule table. */
class VhdlHeuristicAnalyzerTest {

    private static final String RISKY = """
            library ieee;
            use ieee.std_logic_1164.all;
            use ieee.numeric_std.all;
            use std.textio.all;
            use std.env.all;

            entity risky is
              port (
                clk : in std_logic;
                a   : in unsigned(7 downto 0);
                b   : in unsigned(7 downto 0);
                y   : out unsigned(7 downto 0)
              );
            end entity risky;

            architecture rtl of risky is
              attribute foreign : string;
            begin

              clocked : process(clk)
              begin
                if rising_edge(clk) then
                  case a is
                    when "00000000" => y <= a;
                  end case;
                end if;
              end process clocked;

              bad_wait : process(clk)
              begin
                wait for 1 ns;
              end process bad_wait;

              runaway : process
              begin
                while true loop
                  y <= a / b;
                end loop;
              end process runaway;

              stopper : process
              begin
                std.env.finish;
              end process stopper;

            end architecture rtl;
            """;

    private static List<String> ruleIds(List<Finding> findings) {
        return findings.stream().map(Finding::ruleId).toList();
    }

    @Test
    @DisplayName("the code rules are reported by analyze and the security rules by analyzeSecurity")
    void rulesAreSplitBetweenPhases(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        VhdlTestContexts.write(root, "rtl/risky.vhd", RISKY);
        VerificationContext context = VhdlTestContexts.context(root, runDirectory);

        VhdlHeuristicAnalyzer analyzer = new VhdlHeuristicAnalyzer();
        List<Finding> code = analyzer.analyze(context);
        List<String> codeIds = ruleIds(code);

        assertTrue(codeIds.contains("CW-VHDL-BUG-001"), "a case with no when others: " + codeIds);
        assertTrue(codeIds.contains("CW-VHDL-BUG-002"), "a clocked if with no else: " + codeIds);
        assertTrue(codeIds.contains("CW-VHDL-BUG-003"), "a process with a sensitivity list and a wait: " + codeIds);
        assertTrue(codeIds.contains("CW-VHDL-BUG-004"), "an unbounded loop that never advances time: " + codeIds);
        assertTrue(codeIds.contains("CW-VHDL-CORRECT-001"), "std.env.finish in a design: " + codeIds);
        assertTrue(codeIds.contains("CW-VHDL-STYLE-001"), "a division by a signal: " + codeIds);
        assertTrue(code.stream().noneMatch(finding -> finding.category() == FindingCategory.SECURITY),
                "the static phase must not carry security findings: " + codeIds);

        List<Finding> security = analyzer.analyzeSecurity(context);
        List<String> securityIds = ruleIds(security);
        assertTrue(securityIds.contains("CW-VHDL-SEC-001"), "a foreign subprogram binding: " + securityIds);
        assertTrue(securityIds.contains("CW-VHDL-SEC-002"), "file I/O through textio: " + securityIds);
        assertTrue(security.stream().allMatch(finding -> finding.category() == FindingCategory.SECURITY),
                "the security phase must carry only security findings: " + securityIds);
    }

    @Test
    @DisplayName("a clean well formed design produces no code findings")
    void cleanDesignIsQuiet(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        VhdlTestContexts.write(root, "rtl/clean.vhd", """
                library ieee;
                use ieee.std_logic_1164.all;

                entity clean is
                  port (a : in std_logic; b : in std_logic; y : out std_logic);
                end entity clean;

                architecture rtl of clean is
                begin
                  process(all)
                  begin
                    if a = '1' then
                      y <= b;
                    else
                      y <= '0';
                    end if;
                  end process;
                end architecture rtl;
                """);
        VerificationContext context = VhdlTestContexts.context(root, runDirectory);
        List<Finding> findings = new VhdlHeuristicAnalyzer().analyze(context);
        assertTrue(findings.isEmpty(), "nothing here is a rule violation: " + ruleIds(findings));
    }

    @Test
    @DisplayName("a testbench file is not analysed as design logic")
    void testbenchIsNotAnalysed(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        VhdlTestContexts.write(root, "tb/risky_tb.vhd", RISKY);
        VerificationContext context = VhdlTestContexts.context(root, runDirectory);
        assertTrue(new VhdlHeuristicAnalyzer().analyze(context).isEmpty());
        assertTrue(new VhdlHeuristicAnalyzer().analyzeSecurity(context).isEmpty());
    }
}
