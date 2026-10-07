package dev.codewitness.kotlin.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scanner that every rule and every mutant site is decided by.
 *
 * <p>These tests are the reason the analyzer and the mutator share one implementation: the cases below are
 * exactly the ones a second copy of "where does this literal end" got wrong.
 */
class KotlinSourceScanTest {

    @Test
    @DisplayName("a raw string runs to the next three quotes, across lines")
    void rawStringsSpanLines() {
        String source = "val a = \"\"\"a \" b\nc \"\"\" + x\n";
        int start = source.indexOf("\"\"\"");
        int end = KotlinSourceScan.literalEnd(source, start);

        assertEquals(source.indexOf(" + x"), end,
                "the raw string ends at its closing triple quote, not at the single quote inside it");
        assertTrue(source.substring(start, end).contains("c \"\"\""), source.substring(start, end));
    }

    @Test
    @DisplayName("a string ends at the closing quote, not at an escaped one")
    void escapedQuotesDoNotEndAString() {
        String source = "val s = \"a \\\" b\" + 1\n";
        int start = source.indexOf('"');
        int end = KotlinSourceScan.literalEnd(source, start);

        assertEquals(source.indexOf("\" + 1") + 1, end,
                "the escaped quote is part of the literal: " + source.substring(start, end));
    }

    @Test
    @DisplayName("a template hole is consumed with its nested strings and braces")
    void templateHolesAreConsumedWhole() {
        String source = "val s = \"total ${ledger.total(\"x\") + 1}\" + 2\n";
        int start = source.indexOf('"');
        int end = KotlinSourceScan.literalEnd(source, start);

        assertEquals(source.indexOf("\" + 2") + 1, end,
                "the hole and the string inside it belong to the literal: " + source.substring(0, end));
    }

    @Test
    @DisplayName("a character literal and a backtick identifier are literals too")
    void charLiteralsAndBackticksAreLiterals() {
        String charSource = "val c = 'a' + b\n";
        assertEquals(charSource.indexOf("' + b") + 1,
                KotlinSourceScan.literalEnd(charSource, charSource.indexOf('\'')));

        String backtick = "val `my name` = a + b\n";
        assertEquals(backtick.indexOf("` = a") + 1,
                KotlinSourceScan.literalEnd(backtick, backtick.indexOf('`')));
    }

    @Test
    @DisplayName("an unterminated single-quoted string stops at the end of its line")
    void anUnterminatedStringDoesNotSwallowTheFile() {
        String source = "val a = \"oops\nval b = 1\n";
        int end = KotlinSourceScan.literalEnd(source, source.indexOf('"'));

        assertTrue(end <= source.indexOf('\n') + 1,
                "a single-quoted string cannot span a line, so the scanner stops there: " + end);
    }

    @Test
    @DisplayName("anything that opens no literal yields no end")
    void nonOpenersYieldMinusOne() {
        assertEquals(-1, KotlinSourceScan.literalEnd("val a = 1 + 2", 4));
        assertEquals(-1, KotlinSourceScan.literalEnd("", 0));
        assertEquals(-1, KotlinSourceScan.literalEnd(null, 0));
        assertEquals(-1, KotlinSourceScan.literalEnd("x", 9));
    }

    @Test
    @DisplayName("codeOnly blanks literals and comments but keeps every line, so a location stays valid")
    void codeOnlyPreservesPositions() {
        String source = """
                // a comment with a "quote
                val a = "a string with a // comment inside"
                /* a block
                   comment */
                val b = 1 + 2 // trailing
                """;

        String code = KotlinSourceScan.codeOnly(source);

        assertEquals(source.length(), code.length(), "the projection must have the same length");
        assertEquals(countNewlines(source), countNewlines(code), "and the same number of lines");
        assertTrue(code.contains("val a ="), code);
        assertTrue(code.contains("val b = 1 + 2"), code);
        assertFalse(code.contains("a string with"), "literal contents are blanked: " + code);
        assertFalse(code.contains("a comment with"), "comment contents are blanked: " + code);
        assertFalse(code.contains("trailing"), code);
        assertEquals(5, lineOf(source, "val b"), "line numbers survive the projection");
        assertEquals(5, lineOf(code, "val b"));
    }

    @Test
    @DisplayName("withoutComments blanks comments but keeps literal text for a rule that reads it")
    void withoutCommentsKeepsLiterals() {
        String source = """
                // val password = "commented-out"
                val password = "hunter2"
                val url = "https://example.test/a" // not a comment start
                """;

        String kept = KotlinSourceScan.withoutComments(source);

        assertEquals(source.length(), kept.length());
        assertTrue(kept.contains("\"hunter2\""), kept);
        assertTrue(kept.contains("https://example.test/a"), "a URL inside a string is not a comment");
        assertFalse(kept.contains("commented-out"), "a commented-out assignment is not code: " + kept);
    }

    @Test
    @DisplayName("nested block comments are consumed whole")
    void blockCommentsNest() {
        String source = "val a = 1 /* outer /* inner */ still comment */ + 2\n";
        String code = KotlinSourceScan.codeOnly(source);

        assertFalse(code.contains("still comment"), code);
        assertTrue(code.contains("val a = 1"), code);
        assertTrue(code.contains("+ 2"), code);
    }

    private static int countNewlines(String text) {
        int count = 0;
        for (char c : text.toCharArray()) {
            if (c == '\n') {
                count++;
            }
        }
        return count;
    }

    private static int lineOf(String content, String needle) {
        int line = 1;
        for (int i = 0; i < content.indexOf(needle); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
