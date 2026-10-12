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

/** Lexical reading of VHDL entities, ports and comments. */
class VhdlSourceParserTest {

    @Test
    @DisplayName("comments and strings are blanked but offsets and line numbers are preserved")
    void masksCommentsAndStrings() {
        String source = """
                entity e is -- entity in a comment
                  port (a : in std_logic); /* entity block comment */
                end entity e;
                """;
        String masked = VhdlSourceParser.maskCommentsAndStrings(source);
        assertEquals(source.length(), masked.length());
        assertEquals(source.lines().count(), masked.lines().count());
        assertFalse(masked.contains("entity in a comment"));
        assertFalse(masked.contains("entity block comment"));
        assertTrue(masked.contains("entity e is"));
    }

    @Test
    @DisplayName("a doubled quote inside a VHDL string does not end the literal early")
    void handlesDoubledQuoteInString() {
        String source = "report \"say \"\"hi\"\"\"; entity e is end entity;";
        String masked = VhdlSourceParser.maskCommentsAndStrings(source);
        assertFalse(masked.contains("hi"));
        assertTrue(masked.contains("entity e is"));
    }

    @Test
    @DisplayName("an entity with a generic clause and a port clause parses both")
    void parsesGenericAndPort(@TempDir Path root) throws IOException {
        Path file = VhdlTestContexts.write(root, "e.vhd", """
                entity counter is
                  generic (width : positive := 8);
                  port (
                    clk : in std_logic;
                    q   : out std_logic_vector(width - 1 downto 0)
                  );
                end entity counter;
                """);
        List<VhdlDesignUnit> units = VhdlSourceParser.parse(file);
        assertEquals(1, units.size());
        VhdlDesignUnit unit = units.getFirst();
        assertEquals("counter", unit.name());
        assertEquals(2, unit.ports().size());
        assertEquals("std_logic", unit.ports().get(0).subtype());
        assertEquals("std_logic_vector(width - 1 downto 0)", unit.ports().get(1).subtype());
        // A bound written as an expression cannot be evaluated here, so the width falls back to 64.
        assertEquals(64, unit.ports().get(1).width());
    }

    @Test
    @DisplayName("a literal vector bound gives an exact width and kind")
    void readsVectorWidth() {
        VhdlDesignUnit.Port port =
                new VhdlDesignUnit.Port("q", "out", "unsigned(31 downto 0)");
        assertEquals(32, port.width());
        assertEquals("unsigned", port.vectorKind());
        assertEquals("to_unsigned(cw_q, 32)", port.driveExpression("cw_q"));

        VhdlDesignUnit.Port ascending =
                new VhdlDesignUnit.Port("q", "out", "std_logic_vector(0 to 7)");
        assertEquals(8, ascending.width());
        assertEquals("std_logic_vector", ascending.vectorKind());

        VhdlDesignUnit.Port single = new VhdlDesignUnit.Port("a", "in", "std_logic");
        assertEquals(1, single.width());
        assertEquals("'1' when cw_a /= 0 else '0'", single.driveExpression("cw_a"));

        VhdlDesignUnit.Port integer = new VhdlDesignUnit.Port("n", "in", "integer");
        assertEquals("cw_n", integer.driveExpression("cw_n"));
        assertEquals(32, integer.width());
    }

    @Test
    @DisplayName("`end entity name;` does not declare a second entity")
    void doesNotDoubleCountEndClause(@TempDir Path root) throws IOException {
        Path file = VhdlTestContexts.write(root, "one.vhd", """
                entity one is
                  port (a : in std_logic);
                end entity one;
                """);
        assertEquals(1, VhdlSourceParser.parse(file).size());
    }

    @Test
    @DisplayName("an entity with no port clause has no ports and is a testbench")
    void entityWithoutPorts(@TempDir Path root) throws IOException {
        Path file = VhdlTestContexts.write(root, "tb.vhd", """
                entity bench_tb is
                end entity bench_tb;
                """);
        VhdlDesignUnit unit = VhdlSourceParser.parse(file).getFirst();
        assertTrue(unit.ports().isEmpty());
        assertTrue(unit.testbench());
    }
}
