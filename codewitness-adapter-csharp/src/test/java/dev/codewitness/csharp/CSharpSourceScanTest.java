package dev.codewitness.csharp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the C# scanner that feeds both the heuristic analyzer and the mutation tester. */
class CSharpSourceScanTest {

    @Test
    @DisplayName("a comment that describes a hazard is not the hazard")
    void stripsComments() {
        String source = """
                var total = 1 + 2; // secret = "abc"
                /* password = "def" */
                var other = total;
                """;
        CSharpSourceScan.Views views = CSharpSourceScan.scan(source);

        assertEquals(source.length(), views.code().length(), "the views are offset-aligned with the input");
        assertTrue(views.code().contains("1 + 2"));
        assertTrue(views.code().contains("var other"));
        assertEquals(-1, views.masked().indexOf("secret"));
        assertEquals(-1, views.masked().indexOf("password"));
        assertTrue(views.directives().contains("secret = \"abc\""),
                "the directive view keeps line comments, which is what the suppression rule reads");
    }

    @Test
    @DisplayName("string contents are blanked for ordinary rules but kept for the rules that need them")
    void blanksLiteralContents() {
        String source = "var sql = \"SELECT * FROM t\" + name;\n";
        CSharpSourceScan.Views views = CSharpSourceScan.scan(source);

        assertEquals(source.length(), views.code().length());
        assertTrue(views.code().contains("SELECT * FROM t"), "the code view keeps literal contents");
        assertTrue(views.masked().contains("var sql = \""), "the masked view keeps the delimiters");
        assertEquals(-1, views.masked().indexOf("SELECT"));
        assertTrue(views.directives().indexOf("SELECT") < 0);
    }

    @Test
    @DisplayName("a verbatim string ends at the next undoubled quote and may span lines")
    void skipsVerbatimStrings() {
        String source = """
                var text = @"a ""quoted"" value
                still inside";
                var after = 1;
                """;
        CSharpSourceScan.Views views = CSharpSourceScan.scan(source);

        assertEquals(source.length(), views.code().length());
        assertEquals(-1, views.masked().indexOf("still inside"),
                "the continuation line is still inside the literal");
        assertTrue(views.masked().contains("var after = 1;"),
                "scanning resumes in code after the literal closes");
    }

    @Test
    @DisplayName("a raw string ends at a run of as many quotes as opened it, across lines")
    void skipsRawStrings() {
        String source = """
                var text = \"""
                a "quoted" line
                \""";
                var after = 2;
                """;
        CSharpSourceScan.Views views = CSharpSourceScan.scan(source);

        assertEquals(source.length(), views.code().length());
        assertEquals(-1, views.masked().indexOf("quoted"));
        assertTrue(views.masked().contains("var after = 2;"));
    }

    @Test
    @DisplayName("an interpolated string is consumed whole, so a hole is not read as code")
    void skipsInterpolatedStrings() {
        String source = "var text = $\"value {1 + 2}\";\n";
        CSharpSourceScan.Views views = CSharpSourceScan.scan(source);

        assertEquals(source.length(), views.code().length());
        assertEquals(-1, views.masked().indexOf("1 + 2"),
                "conservative on purpose: a missed site costs a mutant, a misread one changes data");
    }

    @Test
    @DisplayName("a verbatim identifier is not a literal, and an unterminated literal does not swallow the file")
    void handlesEdgeCases() {
        assertEquals(8, CSharpSourceScan.literalEnd("var x = @class;", 8),
                "an @ that is not followed by a quote opens nothing, so the scanner reports no literal");

        CSharpSourceScan.Views views = CSharpSourceScan.scan("var wrong = \"unterminated\nvar after = 1;\n");
        assertTrue(views.masked().contains("var after = 1;"),
                "an unterminated string ends at the newline rather than hiding every later rule");
        assertTrue(views.directives().contains("var after = 1;"));
    }

    @Test
    @DisplayName("line numbers are 1-based offsets into the scanned text")
    void countsLines() {
        String source = "one\ntwo\nthree\n";
        assertEquals(1, CSharpSourceScan.lineOf(source, 0));
        assertEquals(2, CSharpSourceScan.lineOf(source, 4));
        assertEquals(3, CSharpSourceScan.lineOf(source, 8));
    }
}
