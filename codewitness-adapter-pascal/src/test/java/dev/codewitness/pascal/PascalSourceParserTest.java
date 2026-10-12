package dev.codewitness.pascal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The lexical scanner preserves offsets and blanks every hiding place a keyword can sit in. */
class PascalSourceParserTest {

    @Test
    @DisplayName("every comment form and a string literal are blanked, with newlines preserved")
    void masksCommentsAndStrings() {
        String source = "unit a;\n{ uses Ghost; }\n(* uses Other *)\n// uses Third\n"
                + "const S = 'it''s uses Fourth';\nend.\n";

        String masked = PascalSourceParser.stripCommentsAndStrings(source);

        assertFalse(masked.toLowerCase().contains("ghost"));
        assertFalse(masked.toLowerCase().contains("other"));
        assertFalse(masked.toLowerCase().contains("third"));
        assertFalse(masked.toLowerCase().contains("fourth"));
        assertEquals(source.chars().filter(c -> c == '\n').count(),
                masked.chars().filter(c -> c == '\n').count());
    }

    @Test
    @DisplayName("a uses entry that names a file contributes only its identifier")
    void readsFileQualifiedUses() {
        List<String> dependencies = PascalSourceParser.dependencies("uses ledgererrors in 'x.pas', Math;");

        assertEquals(List.of("ledgererrors", "Math"), dependencies);
    }

    @Test
    @DisplayName("test names are recognised in the shapes projects use")
    void recognisesTestNames() {
        assertTrue(PascalSourceParser.isTestName("ledger_tests"));
        assertTrue(PascalSourceParser.isTestName("ledger_test"));
        assertTrue(PascalSourceParser.isTestName("tests"));
        assertTrue(PascalSourceParser.isTestName("ledgerspec"));
        assertFalse(PascalSourceParser.isTestName("ledger"));
        assertTrue(PascalSourceParser.isTestDirectory("tests"));
        assertFalse(PascalSourceParser.isTestDirectory("src"));
    }

    @Test
    @DisplayName("a source with no declaration falls back to its file stem")
    void fallsBackToTheStem() {
        PascalModule module = PascalSourceParser.parse(Path.of("anonymous.pas"));

        assertEquals("anonymous", module.name());
        assertFalse(module.program());
    }
}
