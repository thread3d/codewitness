package dev.codewitness.php.analysis;

import dev.codewitness.php.PhpTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scanner every PHP rule and every PHP mutant is decided by.
 *
 * <p>These cases are the ones a naive "a string ends at the next quote" scanner gets wrong, and they are
 * why the rule table and the text mutator share one implementation rather than each carrying its own
 * idea of where a literal ends.
 */
class PhpSourceScanTest {

    @Test
    @DisplayName("a single-quoted string ends at its closing quote, not at an escaped one")
    void singleQuotedStringsRespectEscapes() {
        String source = "$a = 'it\\'s a test'; $b = 1;\n";
        int start = source.indexOf('\'');

        int end = PhpSourceScan.literalEnd(source, start);

        assertEquals("'it\\'s a test'", source.substring(start, end),
                "the escaped quote belongs to the literal: a scanner that stopped there would read the rest "
                        + "of the file as code");
    }

    @Test
    @DisplayName("a double-quoted string ends at its closing quote, not at an escaped one")
    void doubleQuotedStringsRespectEscapes() {
        String source = "$a = \"a \\\" b\"; $b = 2;\n";
        int start = source.indexOf('"');

        int end = PhpSourceScan.literalEnd(source, start);

        assertEquals("\"a \\\" b\"", source.substring(start, end), source.substring(start, end));
    }

    @Test
    @DisplayName("an interpolation hole is consumed with the nested strings and braces inside it")
    void interpolationHolesAreConsumedWhole() {
        String source = """
                $a = "total {$row["k"]} end"; $b = 2;
                """;
        int start = source.indexOf('"');

        int end = PhpSourceScan.literalEnd(source, start);

        assertEquals("\"total {$row[\"k\"]} end\"", source.substring(start, end),
                "the hole contains code, and a scanner that ended the literal at the quote inside it would "
                        + "read the rest of the file wrong");
    }

    @Test
    @DisplayName("a single-quoted string does not interpolate, so a brace in it is text")
    void singleQuotedStringsDoNotInterpolate() {
        String source = "$a = 'x{$y'; $b = 1;\n";
        int start = source.indexOf('\'');

        int end = PhpSourceScan.literalEnd(source, start);

        assertEquals("'x{$y'", source.substring(start, end),
                "a single-quoted literal has no holes, so the scan must end at its own quote rather than "
                        + "hunting for a closing brace in the code that follows");
    }

    @Test
    @DisplayName("the legacy ${name} hole is consumed too, and an unterminated one stops at the end")
    void legacyDollarBraceHoles() {
        String source = "$a = \"${first}${second}\";\n";
        int end = PhpSourceScan.literalEnd(source, source.indexOf('"'));

        assertEquals("\"${first}${second}\"", source.substring(source.indexOf('"'), end));

        String unterminated = "$a = \"oops";
        int stop = PhpSourceScan.literalEnd(unterminated, unterminated.indexOf('"'));
        assertEquals(unterminated.length(), stop, "an unterminated literal runs to the end of the file");
    }

    @Test
    @DisplayName("an unterminated single-line literal does not swallow the rest of the file")
    void unterminatedQuotedStringStopsAtItsLine() {
        String source = "$a = \"oops\n$b = 1;\n";
        int end = PhpSourceScan.literalEnd(source, source.indexOf('"'));

        assertTrue(end <= source.indexOf('\n'),
                "a quoted literal cannot span a line, so the scan stops there instead of hiding every later "
                        + "rule: " + end);
    }

    @Test
    @DisplayName("a heredoc spans lines and ends at its own marker")
    void heredocsSpanLines() {
        String source = """
                $sql = <<<SQL
                SELECT * FROM accounts WHERE id = $id
                SQL;
                $x = 1;
                """;
        int start = source.indexOf("<<<");

        int end = PhpSourceScan.literalEnd(source, start);

        String literal = source.substring(start, end);
        assertTrue(literal.contains("SELECT *"), "the whole body is part of the literal: " + literal);
        assertTrue(source.substring(end).startsWith(";"),
                "the literal ends at the marker, so the statement's own semicolon stays code: "
                        + source.substring(end));
        assertFalse(source.substring(end).contains("$id"), source.substring(end));
    }

    @Test
    @DisplayName("a nowdoc and an indented closing marker (PHP 7.3+) both end at their marker")
    void nowdocsAndIndentedMarkers() {
        String source = """
                $a = <<<'EOT'
                no $interpolation here
                    EOT;
                $b = 2;
                """;
        int start = source.indexOf("<<<");

        int end = PhpSourceScan.literalEnd(source, start);

        assertTrue(source.substring(start, end).contains("no $interpolation"),
                "a nowdoc's body is text, not code: " + source.substring(start, end));
        assertTrue(source.substring(end).startsWith(";"), source.substring(end));
    }

    @Test
    @DisplayName("a shift operator is not a heredoc opener")
    void shiftIsNotAHeredoc() {
        assertEquals(-1, PhpSourceScan.literalEnd("$x = $a <<< 2;\n", 8),
                "the opening line must end after the identifier, so a shift is left as code");
        assertEquals(-1, PhpSourceScan.literalEnd("$x = $a <<< $b;\n", 8),
                "a variable cannot be a heredoc label");
    }

    @Test
    @DisplayName("a backtick string is a literal, and the code after it is still code")
    void backtickStringsAreLiterals() {
        String source = "$out = `ls -l $dir`; $b = 2;\n";
        int start = source.indexOf('`');

        int end = PhpSourceScan.literalEnd(source, start);

        assertEquals("`ls -l $dir`", source.substring(start, end));
        assertTrue(source.substring(end).contains("$b = 2"), source.substring(end));
    }

    @Test
    @DisplayName("'#' comments are blanked, but '#[' attributes are code")
    void hashCommentsVersusAttributes() {
        String source = "# eval($input)\n#[Test]\npublic function itWorks(): void\n";

        String code = PhpSourceScan.withoutComments(source);

        assertFalse(code.contains("eval"), "# is a comment to the end of the line: " + code);
        assertTrue(code.contains("#[Test]"),
                "#[...] is an attribute, which is code: treating it as a comment would hide a test's own "
                        + "marker from the rule that looks for it: " + code);
        assertEquals(source.length(), code.length(), "the projection must keep the offset alignment");
    }

    @Test
    @DisplayName("block comments are blanked to the first */ and do not nest")
    void blockCommentsEndAtTheFirstCloser() {
        String source = "<?php\n/* a \"quoted\" comment\n   with */ eval($x);\n";

        String code = PhpSourceScan.withoutComments(source);

        assertFalse(code.contains("quoted"), code);
        assertTrue(code.contains("eval($x)"), code);
        assertTrue(PhpSourceScan.stripLiterals("<?php /* never closed").contains("<?php "),
                "an unterminated block comment blanks the rest of the file rather than leaking code");
    }

    @Test
    @DisplayName("the masked view blanks literal contents but keeps delimiters and every line")
    void maskedViewPreservesPositions() {
        String source = """
                // $password = "commented out"
                $password = "hunter2";
                $url = "https://example.test/a"; // a URL is not a comment start
                $path = '/tmp/ledger.json';
                """;

        String masked = PhpSourceScan.stripLiterals(source);

        assertEquals(source.length(), masked.length(), "the projection must have the same length");
        assertEquals(newlines(source), newlines(masked), "and the same number of lines");
        assertTrue(masked.contains("$password = \""), "the delimiters survive: " + masked);
        assertFalse(masked.contains("hunter2"), "literal contents do not: " + masked);
        assertFalse(masked.contains("commented out"), "and neither does a comment: " + masked);
        assertFalse(masked.contains("example.test"), masked);
        assertFalse(masked.contains("/tmp/"), masked);
        assertEquals(3, PhpSourceScan.lineOf(source, source.indexOf("$url")),
                "a location found in a projection must be the location in the original file");
    }

    @Test
    @DisplayName("the code view keeps literal text so a rule about a literal can read it")
    void codeViewKeepsLiteralText() {
        String source = """
                // $apiKey = "commented out"
                $url = "https://example.test/a";
                """;

        String code = PhpSourceScan.withoutComments(source);

        assertEquals(source.length(), code.length());
        assertTrue(code.contains("https://example.test/a"), code);
        assertFalse(code.contains("commented out"),
                "a commented-out assignment is not code even for a rule that reads literals: " + code);
        assertEquals(2, PhpSourceScan.lineOf(source, source.indexOf("$url")));
    }

    @Test
    @DisplayName("nothing that opens no literal yields an end")
    void nonOpenersYieldMinusOne() {
        assertEquals(-1, PhpSourceScan.literalEnd("$a = 1 + 2;", 5));
        assertEquals(-1, PhpSourceScan.literalEnd("", 0));
        assertEquals(-1, PhpSourceScan.literalEnd(null, 0));
        assertEquals(-1, PhpSourceScan.literalEnd("x", 9));
        assertEquals(-1, PhpSourceScan.literalEnd("$a < $b", 3));
    }

    @Test
    @DisplayName("the scanner reads a real file from disk, which is how every rule reaches it")
    void scansAFile(@TempDir Path root) throws IOException {
        String snippet = """
                <?php
                $doc = <<<'TXT'
                {$notCode}
                TXT;
                $name = 'x';
                """;
        Path file = PhpTestContexts.write(root, "src/Legacy.php", snippet);

        PhpSourceScan.Views views = PhpSourceScan.scan(java.nio.file.Files.readString(file));

        assertEquals(snippet.length(), views.code().length());
        assertEquals(snippet.length(), views.masked().length());
        assertTrue(views.masked().contains("$name = '"),
                "the assignment survives the projection: " + views.masked());
        assertFalse(views.masked().contains("notCode"), views.masked());
    }

    private static int newlines(String text) {
        int count = 0;
        for (char c : text.toCharArray()) {
            if (c == '\n') {
                count++;
            }
        }
        return count;
    }
}
