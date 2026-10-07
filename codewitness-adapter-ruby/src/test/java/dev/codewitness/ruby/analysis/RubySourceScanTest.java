package dev.codewitness.ruby.analysis;

import dev.codewitness.ruby.RubyTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scanner every Ruby rule and every Ruby mutant is decided by.
 *
 * <p>These are the cases a scanner written for the C family gets wrong in both directions, and they are why
 * the rule table and the text mutator share one implementation rather than each carrying its own idea of
 * where a literal ends. Two Ruby-only shapes run through the whole file: {@code =begin}/{@code =end}, which
 * opens only in the first column, and {@code __END__}, which ends the program even though nothing about the
 * text changes.
 *
 * <p>The scanner deliberately does not model everything Ruby does. Where a decision belongs to a parser -
 * whether {@code /} divides or opens a regular expression, whether {@code <<} shifts or opens a heredoc -
 * the tests pin the cheapest rule the class documents, so that a later change to it is a visible change to
 * a test rather than a silent one.
 */
class RubySourceScanTest {

    @Test
    @DisplayName("# comments are blanked in both views, and every line and offset survives the projection")
    void hashCommentsAreBlankedInBothViews() {
        String source = "x = 1 # eval(input)\ny = 2\n";

        RubySourceScan.Views views = RubySourceScan.scan(source);

        assertFalse(views.code().contains("eval"), "a commented-out call is not code: " + views.code());
        assertFalse(views.masked().contains("eval"), views.masked());
        assertEquals(source.length(), views.code().length(), "the projection must keep the offset alignment");
        assertEquals(source.length(), views.masked().length());
        assertEquals(newlines(source), newlines(views.code()));
        assertEquals(newlines(source), newlines(views.masked()),
                "a location found in a projection must be the location in the original file");
        assertEquals(2, RubySourceScan.lineOf(source, source.indexOf("y = 2")));
    }

    @Test
    @DisplayName("=begin/=end is a comment that opens only in the first column")
    void embeddedDocumentationOnlyOpensInTheFirstColumn() {
        String source = """
                =begin
                eval(input)
                =end
                x = 1
                """;

        String code = RubySourceScan.withoutComments(source);

        assertFalse(code.contains("eval"), "embedded documentation is comment text: " + code);
        assertTrue(code.contains("x = 1"), "and the program resumes after =end: " + code);
        assertEquals(source.length(), code.length());

        assertTrue(RubySourceScan.withoutComments("x = 1\n  =begin\neval(input)\n").contains("eval"),
                "an indented =begin is not the opener: treating it as one would blank whatever follows");
    }

    @Test
    @DisplayName("__END__ ends the program, and everything after it is data the interpreter never parses")
    void theEndMarkerStopsTheProgram() {
        String source = "x = 1\n__END__\neval(input)\n";

        RubySourceScan.Views views = RubySourceScan.scan(source);

        assertTrue(views.code().contains("x = 1"), views.code());
        assertFalse(views.code().contains("eval"), "a fixture or a block of YAML after __END__ is not code: "
                + views.code());
        assertFalse(views.masked().contains("eval"), views.masked());
        assertEquals(source.length(), views.code().length());
        assertEquals(newlines(source), newlines(views.code()),
                "the lines after __END__ still exist, so a later offset still maps to the same line");

        assertTrue(RubySourceScan.withoutComments("a = 1\n__END__x\n").contains("__END__x"),
                "the marker is a line of its own: __END__x is an ordinary identifier");
    }

    @Test
    @DisplayName("a single-quoted string ends at its own quote, respecting escapes and taking no hole")
    void singleQuotedStringsRespectEscapes() {
        String escaped = "a = 'it\\'s a test'; b = 1;\n";
        int start = escaped.indexOf('\'');

        assertEquals("'it\\'s a test'", escaped.substring(start, RubySourceScan.literalEnd(escaped, start)),
                "the escaped quote belongs to the literal: a scanner that stopped there would read the rest "
                        + "of the file as code");

        String braces = "a = 'x#{y'; b = 1\n";
        int at = braces.indexOf('\'');

        assertEquals("'x#{y'", braces.substring(at, RubySourceScan.literalEnd(braces, at)),
                "a single-quoted literal has no holes, so the scan must end at its own quote rather than "
                        + "hunting for a closing brace in the code that follows");
    }

    @Test
    @DisplayName("a double-quoted hole is consumed whole, with the nested strings and braces inside it")
    void doubleQuotedStringsConsumeTheirInterpolationHoles() {
        String source = """
                a = "total #{row["k"]} end"; b = 2;
                """;
        int start = source.indexOf('"');
        int end = RubySourceScan.literalEnd(source, start);

        assertEquals("\"total #{row[\"k\"]} end\"", source.substring(start, end),
                "the hole contains Ruby, and a scanner that ended the literal at the quote inside it would "
                        + "read the rest of the file wrong");
        assertTrue(source.substring(end).contains("b = 2"), source.substring(end));

        String globals = "x = \"a #$count b\"; y = 1\n";
        int at = globals.indexOf('"');

        assertEquals("\"a #$count b\"", globals.substring(at, RubySourceScan.literalEnd(globals, at)),
                "#$global and #@ivar interpolate a bare name, which is part of the literal");
    }

    @Test
    @DisplayName("%q %Q %w %i %x %r and a typed-less % are literals, and a bare % is the modulo operator")
    void percentLiteralsAreConsumedWhole() {
        assertEquals("%q(text # here)",
                literalAt("a = %q(text # here)\nb = 1\n", "%q"),
                "the # of a %q literal is text, not a comment");
        assertEquals("%Q(total #{row} end)",
                literalAt("a = %Q(total #{row} end)\nb = 1\n", "%Q"),
                "a %Q literal does interpolate");
        assertEquals("%w[one two]", literalAt("a = %w[one two]\nb = 1\n", "%w"));
        assertEquals("%i[a b]", literalAt("a = %i[a b]\nb = 1\n", "%i"));
        assertEquals("%x{ls -l}", literalAt("a = %x{ls -l}\nb = 1\n", "%x"),
                "a %x literal runs a command, but it is still a literal to this scanner");
        assertEquals("%r{a/b}", literalAt("a = %r{a/b}\nb = 1\n", "%r"));
        assertEquals("%(bare)", literalAt("a = %(bare)\nb = 1\n", "%("),
                "the type letter is optional, so a bare % before a delimiter opens a string");
        assertEquals("%q(nested (parens) here)",
                literalAt("a = %q(nested (parens) here)\nb = 1\n", "%q"),
                "a paired delimiter nests, so the literal ends at the last matching one");

        assertEquals(-1, RubySourceScan.literalEnd("amount % count\n", 7),
                "a % followed by a space is the modulo operator");
        assertEquals(-1, RubySourceScan.literalEnd("x%5\n", 1),
                "and so is one followed by a digit");
    }

    @Test
    @DisplayName("a heredoc spans lines and ends at its own marker, in its bare, - and ~ forms")
    void heredocsSpanLinesAndEndAtTheirMarker() {
        String squiggly = """
                sql = <<~SQL
                  SELECT #{id}
                SQL
                x = 1
                """;
        int at = squiggly.indexOf("<<~");
        String body = literalAt(squiggly, at);

        assertTrue(body.contains("SELECT #{id}"), "the whole body is part of the literal: " + body);
        assertTrue(squiggly.substring(at + body.length()).startsWith("\n"),
                "the literal ends at the marker, so the newline after it and the rest of the file stay code: "
                        + squiggly.substring(at + body.length()));
        assertFalse(body.contains("x = 1"), body);

        assertEquals("<<-TXT\n  body\n  TXT",
                literalAt("doc = <<-TXT\n  body\n  TXT\ny = 2\n", "<<-"),
                "<<- allows the closing marker to be indented");
        assertEquals("<<EOT\nbody\nEOT",
                literalAt("a = <<EOT\nbody\nEOT\nb = 2\n", "<<"),
                "a bare heredoc needs its marker in the first column");

        assertEquals(-1, RubySourceScan.literalEnd("list<<item\n", 4),
                "an append with no space before it is not a heredoc opener");
        assertTrue(RubySourceScan.withoutComments("list<<item\n").contains("item"), "and stays code");
        assertEquals(-1, RubySourceScan.literalEnd("x = <<~NOPE\nbody\n", 4),
                "an opener whose marker never appears is treated as a shift, because consuming the rest of "
                        + "the file would hide every later rule");
    }

    @Test
    @DisplayName("the masked view blanks a heredoc's whole body, because it carries no shape a rule reads")
    void heredocsAreBlankedInTheMaskedView() {
        String source = """
                sql = <<~SQL
                  SELECT #{id}
                SQL
                x = 1
                """;

        RubySourceScan.Views views = RubySourceScan.scan(source);

        assertTrue(views.code().contains("SELECT"), "the code view keeps it for a rule that is about SQL: "
                + views.code());
        assertFalse(views.masked().contains("SELECT"), "the masked view does not: " + views.masked());
        assertEquals(source.length(), views.masked().length());
        assertEquals(newlines(source), newlines(views.masked()));
        assertTrue(views.masked().contains("x = 1"), "and the code after the marker survives: "
                + views.masked());
    }

    @Test
    @DisplayName("?c character literals and bare symbols are ordinary code, not literal openers")
    void questionMarkLiteralsAndSymbolsAreCode() {
        String source = "flag = ?a # eval(input)\nname = :ledger\n";

        assertEquals(-1, RubySourceScan.literalEnd(source, source.indexOf('?')),
                "?a opens no quoted literal; it is read as ordinary code");
        assertEquals(-1, RubySourceScan.literalEnd(source, source.indexOf(':')),
                "a bare symbol is code too");

        RubySourceScan.Views views = RubySourceScan.scan(source);

        assertTrue(views.code().contains("?a"), views.code());
        assertTrue(views.code().contains(":ledger"), views.code());
        assertFalse(views.code().contains("eval"),
                "the scanner is still in step after the character literal, so the # after it is a comment: "
                        + views.code());
    }

    @Test
    @DisplayName("a slash opens a regular expression only where a value can begin, and divides otherwise")
    void aSlashIsARegexOnlyWhereAValueCanBegin() {
        String regex = "raise 'bad' if value =~ /a#b/\n";
        int at = regex.indexOf('/');

        assertEquals("/a#b/", regex.substring(at, RubySourceScan.literalEnd(regex, at)),
                "the # inside a regular expression is text, not a comment");
        assertEquals(regex, RubySourceScan.withoutComments(regex),
                "nothing on that line is a comment, so the code view is the line itself");

        String division = "total = amount / count # half\n";
        int slash = division.indexOf('/');

        assertEquals(-1, RubySourceScan.literalEnd(division, slash),
                "an operator preceded by a value is division: reading it as a regex would hide the rest of "
                        + "the line");
        assertTrue(RubySourceScan.withoutComments(division).contains("total = amount / count"),
                "the division stays code");
        assertFalse(RubySourceScan.withoutComments(division).contains("half"),
                "and the comment after it is still a comment");

        assertEquals("/x/", literalAt("return /x/\n", "return ".length()),
                "a keyword that expects an expression is where a value can begin");
        assertEquals("/ab/", literalAt("x =~ /ab/i && y\n", "x =~ ".length()),
                "the flags after a regex are code, so a rule about what follows still sees them");
        assertEquals("x =~ /abc".length(),
                RubySourceScan.literalEnd("x =~ /abc\n y = 1\n", "x =~ ".length()),
                "a regex that never closes on its line hides that line and no more");
    }

    @Test
    @DisplayName("a literal's delimiters survive the masked view even though its contents do not")
    void theMaskedViewKeepsTheShapeOfAnAssignment() {
        String masked = RubySourceScan.stripLiterals("a = %q(secret)\nb = \"hunter2\"\n");

        assertTrue(masked.contains("a = %q("), "an assignment of a percent literal still reads as one: "
                + masked);
        assertFalse(masked.contains("secret"), masked);
        assertFalse(masked.contains("hunter2"), masked);
        assertTrue(masked.contains("b = \""), masked);
    }

    @Test
    @DisplayName("a rule regex cannot match text that only appears inside a string or a comment")
    void rulesCannotMatchWhatIsNotCode() {
        String source = """
                # eval(input)
                note = "eval(input) and system(command)"
                y = 1
                """;

        RubySourceScan.Views views = RubySourceScan.scan(source);
        Pattern eval = Pattern.compile("eval\\(");

        assertEquals(source.length(), views.code().length());
        assertEquals(source.length(), views.masked().length());
        assertEquals(newlines(source), newlines(views.code()));
        assertEquals(newlines(source), newlines(views.masked()));
        assertEquals(2, RubySourceScan.lineOf(source, source.indexOf("note")));
        assertFalse(eval.matcher(views.masked()).find(),
                "the masked view is what a statement-shaped rule reads, so it cannot see either: "
                        + views.masked());
        assertFalse(views.masked().contains("system"), views.masked());
        assertTrue(views.code().contains("eval(input)"),
                "the code view keeps literal text, for the rules that are about a literal: " + views.code());
        assertFalse(views.code().contains("# eval"),
                "a commented-out call is invisible to every projection: " + views.code());
    }

    @Test
    @DisplayName("nothing that opens no literal yields an end")
    void nonOpenersYieldMinusOne() {
        assertEquals(-1, RubySourceScan.literalEnd("a = 1 + 2;", 5));
        assertEquals(-1, RubySourceScan.literalEnd("", 0));
        assertEquals(-1, RubySourceScan.literalEnd(null, 0));
        assertEquals(-1, RubySourceScan.literalEnd("x", 9));
        assertEquals(-1, RubySourceScan.literalEnd("a < b", 2));
    }

    @Test
    @DisplayName("the scanner reads a real file from disk, which is how every rule reaches it")
    void scansAFile(@TempDir Path root) throws IOException {
        String snippet = """
                doc = <<~'TXT'
                  {$notCode}
                  TXT
                name = 'x'
                """;
        Path file = RubyTestContexts.write(root, "lib/legacy.rb", snippet);

        RubySourceScan.Views views = RubySourceScan.scan(file);

        assertEquals(snippet.length(), views.code().length());
        assertEquals(snippet.length(), views.masked().length());
        assertTrue(views.masked().contains("name = '"),
                "the assignment survives the projection: " + views.masked());
        assertFalse(views.masked().contains("notCode"), views.masked());
    }

    /** @return the literal that opens at the first occurrence of {@code opener}. */
    private static String literalAt(String source, String opener) {
        return literalAt(source, source.indexOf(opener));
    }

    /** @return the literal that opens at {@code start}, or {@code <none>} when nothing opens there. */
    private static String literalAt(String source, int start) {
        int end = RubySourceScan.literalEnd(source, start);
        return end < 0 ? "<none>" : source.substring(start, end);
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
