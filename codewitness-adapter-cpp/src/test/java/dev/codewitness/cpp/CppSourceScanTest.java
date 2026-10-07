package dev.codewitness.cpp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the C and C++ scanner.
 *
 * <p>The scanner is what every rule and the mutator read the source through, so the shapes that make C and
 * C++ different from the languages with a simpler literal grammar are asserted directly: a raw string that
 * ends only at its own delimiter, a character literal that opens with the same character as a Rust lifetime,
 * a backslash continuation, and a comment that must survive in exactly one of the three views.
 */
class CppSourceScanTest {

    @Test
    @DisplayName("the code view drops comments and keeps what a literal says")
    void codeViewDropsComments() {
        String source = """
                int total = 0; // the running total
                /* a block
                   comment */
                const char* message = "// not a comment";
                """;

        CppSourceScan.Views views = CppSourceScan.scan(source);

        assertTrue(views.code().contains("int total = 0;"), views.code());
        assertTrue(views.code().contains("\"// not a comment\""), views.code());
        assertEquals(false, views.code().contains("running total"), views.code());
        assertEquals(false, views.code().contains("a block"), views.code());
        assertEquals(source.length(), views.code().length(), "the views stay offset-aligned with the input");
    }

    @Test
    @DisplayName("the masked view blanks a literal's contents but keeps its delimiters")
    void maskedViewBlanksLiterals() {
        String source = "const char* secret = \"hunter2\";\n";

        CppSourceScan.Views views = CppSourceScan.scan(source);

        assertTrue(views.masked().contains("\"       \""), views.masked());
        assertEquals(false, views.masked().contains("hunter2"), views.masked());
        assertTrue(views.code().contains("hunter2"), "the code view is where the literal's text is readable");
        assertEquals(views.code().length(), views.masked().length());
    }

    @Test
    @DisplayName("the directive view keeps a comment, because reporting a suppression is its purpose")
    void directiveViewKeepsComments() {
        String source = "int total = risky(); // NOLINT\nconst char* url = \"http://pragma\";\n";

        CppSourceScan.Views views = CppSourceScan.scan(source);

        assertTrue(views.directives().contains("NOLINT"), views.directives());
        assertEquals(false, views.directives().contains("http://pragma"),
                "a URL inside a literal must not read as a directive");
        assertEquals(source.length(), views.directives().length(),
                "the directive view stays offset-aligned with the input");
    }

    @Test
    @DisplayName("a raw string runs to its own delimiter, newlines included")
    void rawStringsAreConsumedWhole() {
        String source = """
                const char* sql = R"sql(
                select * from ledger
                )sql";
                int after = 1;
                """;

        CppSourceScan.Views views = CppSourceScan.scan(source);

        assertEquals(false, views.masked().contains("select"), views.masked());
        assertTrue(views.code().contains("select * from ledger"), views.code());
        assertTrue(views.code().contains("int after = 1;"),
                "the scan must resume after the raw string: " + views.code());
        assertEquals(source.length(), views.code().length());
    }

    @Test
    @DisplayName("an unterminated literal stops at its line rather than swallowing the file")
    void unterminatedLiteralStopsAtTheLine() {
        String source = "const char* broken = \"no end\nint after = 1;\n";

        CppSourceScan.Views views = CppSourceScan.scan(source);

        assertTrue(views.code().contains("int after = 1;"), views.code());
    }

    @Test
    @DisplayName("a character literal is a literal, including an escaped quote inside it")
    void characterLiterals() {
        String source = "char quote = '\\'';\nchar newline = '\\n';\nint after = 1;\n";

        CppSourceScan.Views views = CppSourceScan.scan(source);

        assertTrue(views.code().contains("int after = 1;"), views.code());
        assertEquals(false, views.masked().contains("'\\n'"), views.masked());
    }

    @Test
    @DisplayName("a backslash continues a quoted literal across a line break")
    void lineContinuationIsOneLiteral() {
        String source = "const char* message = \"first \\\nsecond\";\nint after = 1;\n";

        CppSourceScan.Views views = CppSourceScan.scan(source);

        assertTrue(views.code().contains("second"), views.code());
        assertTrue(views.code().contains("int after = 1;"), views.code());
    }

    @Test
    @DisplayName("a prefix before a raw string does not stop it being one")
    void prefixedRawStrings() {
        for (String prefix : new String[] {"u8", "u", "U", "L"}) {
            String source = "const char* text = " + prefix + "R\"(a )b)\";\nint after = 1;\n";
            CppSourceScan.Views views = CppSourceScan.scan(source);
            assertTrue(views.code().contains("int after = 1;"), prefix + ": " + views.code());
        }
    }

    @Test
    @DisplayName("an offset can be reported as the line a reader will look at")
    void reportsLineNumbers() {
        String source = "one\ntwo\nthree\n";

        assertEquals(1, CppSourceScan.lineOf(source, 0));
        assertEquals(2, CppSourceScan.lineOf(source, 4));
        assertEquals(3, CppSourceScan.lineOf(source, 8));
        assertEquals(4, CppSourceScan.lineOf(source, source.length()),
                "the position just past a trailing newline is on the empty line after it");
    }

    @Test
    @DisplayName("a literal ends where the language says it does")
    void literalEndsWhereItShould() {
        String source = "\"a\\\"b\" rest";
        assertEquals(6, CppSourceScan.literalEnd(source, 0));

        String raw = "R\"(x)y)\" and more";
        assertEquals(8, CppSourceScan.literalEnd(raw, 0));

        // Not a literal at all: the offset is returned unchanged, which is how the mutator knows to read
        // the character as ordinary code.
        assertEquals(0, CppSourceScan.literalEnd("int x = 1;", 0));
    }
}
