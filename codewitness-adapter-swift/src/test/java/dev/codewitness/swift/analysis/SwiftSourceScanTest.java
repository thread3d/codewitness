package dev.codewitness.swift.analysis;

import dev.codewitness.swift.SwiftProjectDetector;
import dev.codewitness.swift.SwiftTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scanner every Swift rule and every Swift mutant is decided by.
 *
 * <p>These are the cases a scanner written for the C family gets wrong in both directions: a block
 * comment that nests, a string that interpolates real code, a multi-line string, a raw string whose
 * closing quote only counts with the same number of hashes, and an apostrophe that opens nothing at all
 * because Swift has no character literal. Every projection has to keep the same length and the same
 * newlines as the input, or a finding's line number is a lie.
 */
class SwiftSourceScanTest {

    @Test
    @DisplayName("a // comment is blanked in every coding view, and an apostrophe inside it opens nothing")
    void lineCommentsAreBlankedAndApostrophesAreNotDelimiters() {
        String source = """
                // don't evaluate eval(input)
                let x = 1
                let y = 2
                """;

        SwiftSourceScan.Views views = SwiftSourceScan.scan(source);

        assertFalse(views.code().contains("eval"), "a commented-out call is not code: " + views.code());
        assertFalse(views.literals().contains("eval"), "and it is not literal text either: " + views.literals());
        assertTrue(views.comments().contains("don't"), "the comment view keeps what a developer wrote: "
                + views.comments());
        assertTrue(views.code().contains("let x = 1"), views.code());
        assertEquals(source.length(), views.code().length(), "the projection must keep offset alignment");
        assertEquals(newlines(source), newlines(views.code()),
                "a location found in a projection must be the location in the original file");
        assertEquals(-1, SwiftSourceScan.literalEnd(source, source.indexOf('\'')),
                "Swift has no character literal, so an apostrophe must not swallow the rest of the line");
        assertEquals(3, SwiftSourceScan.lineOf(source, source.indexOf("let y = 2")));
    }

    @Test
    @DisplayName("a /* ... */ comment nests: the first */ does not end it")
    void blockCommentsNest() {
        String source = """
                let a = 1 /* outer /* inner */ still hidden */
                let b = 2
                """;

        SwiftSourceScan.Views views = SwiftSourceScan.scan(source);

        assertFalse(views.code().contains("still hidden"), "the tail of a nested comment is not code: "
                + views.code());
        assertFalse(views.code().contains("inner"), views.code());
        assertTrue(views.comments().contains("still hidden"), views.comments());
        assertTrue(views.code().contains("let a = 1"), views.code());
        assertTrue(views.code().contains("let b = 2"), "the code after the comment resumes: " + views.code());
        assertEquals(source.length(), views.code().length());
        assertEquals(newlines(source), newlines(views.code()));
    }

    @Test
    @DisplayName("an ordinary string takes escapes and an interpolation hole holds real Swift")
    void ordinaryStringsTakeEscapesAndHoles() {
        String source = "let msg = \"he said \\\"hi\\\" \\(name) and \\(row[\"k\"]) end\"\n"
                + "let after = 1\n";
        int start = source.indexOf('"');
        int end = SwiftSourceScan.literalEnd(source, start);
        String literal = source.substring(start, end);

        assertEquals("\"he said \\\"hi\\\" \\(name) and \\(row[\"k\"]) end\"", literal,
                "the escaped quote belongs to the literal, and the hole is consumed whole even though it "
                        + "contains a nested string");
        assertTrue(source.substring(end).startsWith("\nlet after = 1"), source.substring(end));

        SwiftSourceScan.Views views = SwiftSourceScan.scan(source);

        assertFalse(views.code().contains("he said"), "the literal is not code: " + views.code());
        assertTrue(views.code().contains("let after = 1"), views.code());
        assertTrue(views.literals().contains("he said"), "the literal view keeps it for the literal rules: "
                + views.literals());
    }

    @Test
    @DisplayName("a \"\"\" multi-line string spans lines, and the code after it is still code")
    void multilineStringsSpanLines() {
        String source = """
                let text = \"\"\"
                line one "quoted"
                line two
                \"\"\"
                let after = 2
                """;
        int start = source.indexOf('"');
        int end = SwiftSourceScan.literalEnd(source, start);

        assertTrue(source.substring(start, end).contains("line two"),
                "the whole body is one literal: " + source.substring(start, end));
        assertTrue(source.substring(end).contains("let after = 2"), source.substring(end));

        SwiftSourceScan.Views views = SwiftSourceScan.scan(source);

        assertFalse(views.code().contains("line one"), "a line-at-a-time scanner would read the body as code: "
                + views.code());
        assertTrue(views.code().contains("let text = "), views.code());
        assertTrue(views.code().contains("let after = 2"), views.code());
        assertEquals(source.length(), views.code().length());
        assertEquals(newlines(source), newlines(views.code()));
        assertEquals(5, SwiftSourceScan.lineOf(source, source.indexOf("let after")));
    }

    @Test
    @DisplayName("a raw string closes only at a quote followed by the same number of hashes")
    void rawStringsCloseAtTheirDelimiterWidth() {
        String source = "let a = #\"raw \\(not a hole) \"#\n"
                + "let b = ##\"has \" quote\"##\n"
                + "let after = 3\n";

        int first = source.indexOf('#');
        assertEquals("#\"raw \\(not a hole) \"#", source.substring(first,
                        SwiftSourceScan.literalEnd(source, first)),
                "an escape needs the matching number of hashes, so \\( here is content and the literal "
                        + "closes at \"#");
        int second = source.indexOf("##\"");
        assertEquals("##\"has \" quote\"##", source.substring(second,
                        SwiftSourceScan.literalEnd(source, second)),
                "a quote inside a ## string is content until it is followed by two hashes");

        SwiftSourceScan.Views views = SwiftSourceScan.scan(source);

        assertFalse(views.code().contains("raw"), "the raw string is a literal, not code: " + views.code());
        assertFalse(views.code().contains("quote"), views.code());
        assertTrue(views.code().contains("let after = 3"), views.code());

        assertEquals(-1, SwiftSourceScan.literalEnd("#if DEBUG\n", 0),
                "#if is a directive, not a raw string opener");
        assertEquals(-1, SwiftSourceScan.literalEnd("value # 1\n", 6),
                "a hash followed by a space opens nothing");
    }

    @Test
    @DisplayName("a backtick identifier is a name, so it is blanked with the literals rather than read as code")
    void backtickIdentifiersAreNames() {
        String source = "let `default` = 1\nlet after = 2\n";
        int at = source.indexOf('`');

        assertEquals("`default`", source.substring(at, SwiftSourceScan.literalEnd(source, at)));
        assertFalse(SwiftSourceScan.codeOnly(source).contains("default"),
                "the name is not code a rule reads: " + SwiftSourceScan.codeOnly(source));
        assertTrue(SwiftSourceScan.codeOnly(source).contains("let after = 2"), SwiftSourceScan.codeOnly(source));
    }

    @Test
    @DisplayName("the three views keep what each is for: code shape, literal text and comment text")
    void theThreeViewsSeparateCodeLiteralsAndComments() {
        String source = """
                let url = "https://example.com/a"
                // TODO: "not a string"
                let x = 1
                """;

        assertFalse(SwiftSourceScan.codeOnly(source).contains("https"), SwiftSourceScan.codeOnly(source));
        assertTrue(SwiftSourceScan.withoutComments(source).contains("https://example.com/a"),
                "the literal view keeps the text, for a rule that is about a literal: "
                        + SwiftSourceScan.withoutComments(source));
        assertFalse(SwiftSourceScan.withoutComments(source).contains("TODO"),
                "but a commented-out line is invisible to it: " + SwiftSourceScan.withoutComments(source));
        assertTrue(SwiftSourceScan.commentText(source).contains("TODO"),
                SwiftSourceScan.commentText(source));
        assertFalse(SwiftSourceScan.commentText(source).contains("https"),
                "the comment view is only comment text: " + SwiftSourceScan.commentText(source));
    }

    @Test
    @DisplayName("line helpers preserve the 1-based source line, whatever the literal spans")
    void lineHelpersPreserveSourceLines() {
        String source = "let a = \"\"\"\nbody\n\"\"\"\nlet b = 2\nlet c = 3\n";

        List<String> lines = SwiftSourceScan.codeLines(source);

        assertEquals(6, lines.size(), lines.toString());
        assertFalse(lines.get(1).contains("body"), lines.toString());
        assertTrue(lines.get(3).contains("let b = 2"), lines.toString());

        List<SwiftSourceScan.Line> numbered = SwiftSourceScan.numberedCodeLines(source);

        assertEquals(4, numbered.get(3).number());
        assertTrue(numbered.get(4).text().contains("let c = 3"), numbered.toString());
        assertEquals(2, SwiftSourceScan.lineOf(source, source.indexOf("body")));
    }

    @Test
    @DisplayName("nothing that opens no literal yields an end, and an unreadable file yields empty views")
    void nonOpenersAndMissingFiles(@TempDir Path root) throws IOException {
        assertEquals(-1, SwiftSourceScan.literalEnd(null, 0));
        assertEquals(-1, SwiftSourceScan.literalEnd("", 0));
        assertEquals(-1, SwiftSourceScan.literalEnd("x", 9));
        assertEquals(-1, SwiftSourceScan.literalEnd("a + b", 1));

        assertTrue(SwiftSourceScan.scan((String) null).code().isEmpty());
        assertTrue(SwiftSourceScan.scan("").literals().isEmpty());
        assertTrue(SwiftSourceScan.scan(root.resolve("missing.swift")).code().isEmpty());

        Path file = SwiftTestContexts.write(root, "Sources/Ledger/Ledger.swift",
                "let value = \"a + b\"\n// hidden + here\n");
        SwiftSourceScan.Views views = SwiftSourceScan.scan(file);

        assertTrue(views.code().contains("let value = "), views.code());
        assertFalse(views.code().contains("a + b"), views.code());
        assertFalse(views.code().contains("hidden"), views.code());
    }

    @Test
    @DisplayName("source roots are walked, but build output and dependency checkouts are not project source")
    void sourceFilesSkipBuildOutput(@TempDir Path root) throws IOException {
        SwiftTestContexts.write(root, "Sources/Ledger/Ledger.swift", "public struct Ledger {}\n");
        SwiftTestContexts.write(root, "Package.swift", "// swift-tools-version:5.9\n");
        SwiftTestContexts.write(root, ".build/checkouts/dep/Sources/Dep/Dep.swift", "public struct Dep {}\n");

        List<Path> files = SwiftSourceScan.sourceFiles(root);

        assertEquals(1, files.size(), files.toString());
        assertTrue(files.getFirst().toString().endsWith("Ledger.swift"), files.toString());
        assertTrue(SwiftProjectDetector.isSwiftSourceName(files.getFirst()));
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
