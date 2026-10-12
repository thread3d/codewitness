package dev.codewitness.vhdl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Detection of VHDL entities and testbenches. */
class VhdlProjectDetectorTest {

    private static final String LEDGER = """
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

    private static final String TESTBENCH = """
            library ieee;
            use ieee.std_logic_1164.all;
            use std.env.all;

            entity ledger_tb is
            end entity ledger_tb;

            architecture sim of ledger_tb is
              signal a : std_logic := '0';
            begin
              process
              begin
                wait for 1 ns;
                std.env.finish;
              end process;
            end architecture sim;
            """;

    @Test
    @DisplayName("an entity with ports is the design and an entity without ports is a testbench")
    void detectsDesignAndTestbench(@TempDir Path root) throws IOException {
        VhdlTestContexts.write(root, "rtl/ledger.vhd", LEDGER);
        VhdlTestContexts.write(root, "tb/ledger_tb.vhd", TESTBENCH);

        VhdlDesignUnit top = VhdlProjectDetector.topEntity(root);
        assertEquals("ledger", top.name());
        assertFalse(top.testbench());
        assertEquals(4, top.ports().size());
        assertEquals(2, top.inputs().size());
        assertEquals(2, top.outputs().size());

        List<VhdlDesignUnit> testbenches = VhdlProjectDetector.testbenches(root);
        assertEquals(1, testbenches.size());
        assertEquals("ledger_tb", testbenches.getFirst().name());
    }

    @Test
    @DisplayName("a non-ANSI style entity still parses its ports, with each name sharing one declaration")
    void parsesSharedPortNames(@TempDir Path root) throws IOException {
        VhdlTestContexts.write(root, "rtl/pair.vhd", """
                entity pair is
                  port (a, b : in std_logic; y : out std_logic);
                end entity pair;
                """);
        VhdlDesignUnit top = VhdlProjectDetector.topEntity(root);
        assertEquals(3, top.ports().size());
        assertEquals("a", top.ports().get(0).name());
        assertEquals("b", top.ports().get(1).name());
        assertTrue(top.ports().get(0).isInput());
        assertTrue(top.ports().get(2).isOutput());
    }

    @Test
    @DisplayName("a port default value is not part of the subtype")
    void stripsPortDefault(@TempDir Path root) throws IOException {
        VhdlTestContexts.write(root, "rtl/f.vhd", """
                entity f is
                  port (a : in std_logic := '0'; y : out std_logic);
                end entity f;
                """);
        VhdlDesignUnit.Port port = VhdlProjectDetector.topEntity(root).ports().getFirst();
        assertEquals("std_logic", port.subtype());
    }

    @Test
    @DisplayName("only .vhd and .vhdl files are VHDL sources")
    void sourceExtensions(@TempDir Path root) throws IOException {
        VhdlTestContexts.write(root, "rtl/ledger.vhd", LEDGER);
        VhdlTestContexts.write(root, "rtl/notes.txt", "not a source");
        VhdlTestContexts.write(root, "rtl/ledger.vhdx", "not a source either");
        List<Path> sources = VhdlProjectDetector.sourceFiles(root);
        assertEquals(1, sources.size());
        assertTrue(VhdlProjectDetector.isSourceFile(Path.of("a/b/c.vhdl")));
        assertFalse(VhdlProjectDetector.isSourceFile(Path.of("a/b/c.txt")));
    }

    @Test
    @DisplayName("build output and VCS metadata are skipped")
    void skipsBuildOutput(@TempDir Path root) throws IOException {
        VhdlTestContexts.write(root, "rtl/ledger.vhd", LEDGER);
        VhdlTestContexts.write(root, "target/ledger.vhd", LEDGER);
        VhdlTestContexts.write(root, ".git/ledger.vhd", LEDGER);
        VhdlTestContexts.write(root, "work/ledger.vhd", LEDGER);
        List<Path> sources = VhdlProjectDetector.sourceFiles(root);
        assertEquals(1, sources.size(), "only the rtl copy is a source: " + sources);
    }

    @Test
    @DisplayName("a testbench is named by convention as well as by having no ports")
    void testbenchNaming() {
        assertTrue(VhdlSourceParser.isTestbenchName("ledger_tb"));
        assertTrue(VhdlSourceParser.isTestbenchName("tb_ledger"));
        assertTrue(VhdlSourceParser.isTestbenchName("ledger_testbench"));
        assertFalse(VhdlSourceParser.isTestbenchName("ledger"));
    }

    @Test
    @DisplayName("a tree with no entity is still claimed, and says why nothing can run")
    void treeWithNoEntity(@TempDir Path root) throws IOException {
        VhdlTestContexts.write(root, "rtl/notes.vhd", "-- just a comment\n");
        assertTrue(VhdlProjectDetector.looksLikeVhdlProject(root));
        assertTrue(VhdlProjectDetector.entities(root).isEmpty());
    }
}
