package dev.codewitness.swift.analysis;

import dev.codewitness.swift.SwiftProjectDetector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads Swift source the way the language defines it, so that no rule and no mutant is decided by text
 * that only looks like code.
 *
 * <p>Swift's lexical rules are close enough to the C family's that a scanner written for another
 * language compiles against it and still gets the interesting cases wrong in both directions:
 *
 * <ul>
 *   <li>{@code //} runs to the end of the line, and {@code /* ... *}{@code /} is a block comment that
 *       <em>nests</em>: the first {@code *}{@code /} does not necessarily end one, so a depth counter is
 *       required or the tail of a nested comment is read as code;</li>
 *   <li>an ordinary string ({@code "..."}) takes backslash escapes and may contain a
 *       {@code \(...)} interpolation hole. The hole holds real Swift, including nested strings,
 *       comments and parentheses, so it is consumed as part of the literal rather than left to be
 *       scanned as code - the conservative direction: missing a site inside a hole costs one finding,
 *       while reading the inside of a string as code invents one;</li>
 *   <li>a multi-line string ({@code """..."""}) spans any number of lines, so a line-at-a-time scanner
 *       would read its body as code;</li>
 *   <li>a raw string adds hashes to both delimiters - {@code #"..."#}, {@code ##"..."##},
 *       {@code ##"a"#b"##} - so a {@code "} is content until it is followed by the same number of
 *       {@code #} signs, and an escape must be followed by the same number of hashes too;</li>
 *   <li>an extended delimiter combines both: {@code #"""..."""#} is a multi-line raw string, and its
 *       closing {@code """} is content unless it is followed by the {@code #}; and</li>
 *   <li>a backtick identifier ({@code `default`}) is a name, so an operator-looking character inside it
 *       is not an operator.</li>
 * </ul>
 *
 * <p>Swift has <em>no</em> character literal: {@code '} is not a string delimiter, and a scanner that
 * treats it as one swallows the rest of a line at the first apostrophe. It is deliberately not a literal
 * here.
 *
 * <p>Every projection this class produces has exactly the same length and the same newlines as the input,
 * so a match found in one of them maps back to the same line and column in the original file, and
 * {@link #lineOf(String, int)} answers for any of them. Three views are produced in a single pass, because
 * the rule table needs all of them and separate passes could disagree:
 *
 * <ul>
 *   <li>{@link Views#code()} - comments and every string literal replaced by spaces. A rule that looks for
 *       a statement shape reads this view, so a commented-out line or a text that merely sits inside a
 *       string is invisible to it;</li>
 *   <li>{@link Views#literals()} - comments blanked and literal text kept. A rule that is <em>about</em> a
 *       literal (a hard-coded credential, a secret-looking {@code UserDefaults} key) reads this view, and a
 *       commented-out assignment is still invisible to it; and</li>
 *   <li>{@link Views#comments()} - only comment text, with code and literals blanked. A rule that is about
 *       what a developer wrote <em>in a comment</em> - an unfinished-work marker - reads this view, so a
 *       {@code TODO} inside a string is not reported as one.</li>
 * </ul>
 *
 * <p>Known limits, stated rather than hidden. Swift 5.7 introduced a bare {@code /.../} regular-expression
 * literal whose opening {@code /} is indistinguishable from division without a parser, so this scanner does
 * not model it: a {@code //} inside such a literal is read as a line comment, which can hide a finding but
 * cannot invent one. A {@code """} opening a multi-line string is taken as a multi-line string even where
 * the content does not begin on the next line, which is exactly the choice Swift's own lexer makes and
 * reports as an error. For a raw string, {@code #"""} is read as a multi-line delimiter only when the
 * content begins on the next line; otherwise it is read the way Swift reads it - a single-line raw string
 * opened by {@code #"} whose content happens to start with two quotes. An unterminated single-line string
 * stops at the end of its own line rather than swallowing the rest of the file, so a misread opener hides
 * one line rather than the whole file.
 */
public final class SwiftSourceScan {

    private SwiftSourceScan() {
    }

    /**
     * Offset-aligned readings of one source file.
     *
     * @param code     comments and string literals replaced by spaces of the same length
     * @param literals comments blanked, literal text kept
     * @param comments only comment text, everything else blanked
     */
    public record Views(String code, String literals, String comments) {
    }

    /** A line of a projection together with its 1-based number. */
    public record Line(int number, String text) {
    }

    /** A literal the scanner recognised: where it opens and where it ends, exclusive. */
    private record Literal(int start, int end) {
    }

    /**
     * @param source the source text
     * @return all three views of the file, produced in one pass so that they cannot disagree; a
     *     {@code null} or empty input yields three empty views rather than {@code null}
     */
    public static Views scan(String source) {
        if (source == null || source.isEmpty()) {
            return new Views("", "", "");
        }
        int length = source.length();
        char[] code = source.toCharArray();
        char[] literals = source.toCharArray();
        char[] comments = blanked(source);
        int i = 0;
        while (i < length) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < length && source.charAt(i + 1) == '/') {
                int end = lineEnd(source, i);
                blank(code, source, i, end);
                blank(literals, source, i, end);
                copy(comments, source, i, end);
                i = end;
                continue;
            }
            if (c == '/' && i + 1 < length && source.charAt(i + 1) == '*') {
                int end = blockCommentEnd(source, i);
                blank(code, source, i, end);
                blank(literals, source, i, end);
                copy(comments, source, i, end);
                i = end;
                continue;
            }
            Literal literal = literal(source, i);
            if (literal != null) {
                // The literal text is exactly what the literals view already holds; only the code and
                // comment views have to forget it.
                blank(code, source, literal.start(), literal.end());
                blank(comments, source, literal.start(), literal.end());
                i = literal.end();
                continue;
            }
            i++;
        }
        return new Views(new String(code), new String(literals), new String(comments));
    }

    /**
     * @param file a Swift source file
     * @return all three views of a file, or three empty views when the file does not exist or cannot be
     *     read, so that an unreadable file produces no findings rather than an exception
     */
    public static Views scan(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return new Views("", "", "");
        }
        try {
            return scan(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException _) {
            return new Views("", "", "");
        }
    }

    /**
     * @param source the source text
     * @return the source with comments and every string literal replaced by spaces - the view a rule reads
     *     when it is looking for the shape of a statement
     */
    public static String codeOnly(String source) {
        return scan(source).code();
    }

    /**
     * @param source the source text
     * @return the source with comments blanked and literal text kept - the view a rule reads when its
     *     pattern is about the contents of a literal
     */
    public static String withoutComments(String source) {
        return scan(source).literals();
    }

    /**
     * @param source the source text
     * @return the source with only comment text kept and everything else blanked - the view a rule reads
     *     when it is about what was written in a comment
     */
    public static String commentText(String source) {
        return scan(source).comments();
    }

    /**
     * @param source the source text
     * @return the lines of the code-only view, split on any line break; line {@code i} of the result is
     *     1-based line {@code i + 1} of the source
     */
    public static List<String> codeLines(String source) {
        return List.of(codeOnly(source).split("\\R", -1));
    }

    /**
     * @param source the source text
     * @return the lines of the code-only view, each paired with its 1-based source line number
     */
    public static List<Line> numberedCodeLines(String source) {
        String[] lines = codeOnly(source).split("\\R", -1);
        List<Line> numbered = new ArrayList<>(lines.length);
        for (int i = 0; i < lines.length; i++) {
            numbered.add(new Line(i + 1, lines[i]));
        }
        return List.copyOf(numbered);
    }

    /**
     * @param source the source text
     * @param start  the offset of the candidate opener
     * @return the offset just past the literal that opens there, or {@code -1} when the text there is code
     */
    public static int literalEnd(String source, int start) {
        Literal literal = literal(source, start);
        return literal == null ? -1 : literal.end();
    }

    /**
     * @param source the source text
     * @param offset an offset into it
     * @return the 1-based line number of that offset, valid for the source or any of its three views
     *     because all of them keep every newline at the same offset
     */
    public static int lineOf(String source, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * @param root a directory to read a project's sources from
     * @return the Swift files below the root that a rule must read, in path order
     *
     * <p>{@link SwiftProjectDetector#swiftFiles(Path)} already applies the same exclusions; the check is
     * repeated here because this is the method the analyzers trust, and a filter that lives only inside
     * another class's walk is one refactor away from being lost.
     */
    public static List<Path> sourceFiles(Path root) {
        List<Path> files = new ArrayList<>();
        for (Path file : SwiftProjectDetector.swiftFiles(root)) {
            if (!SwiftProjectDetector.isExcludedSource(root, file)) {
                files.add(file);
            }
        }
        return List.copyOf(files);
    }

    /**
     * @return the literal that opens at {@code start}, or {@code null} when the text there is code
     */
    private static Literal literal(String source, int start) {
        if (source == null || start < 0 || start >= source.length()) {
            return null;
        }
        char c = source.charAt(start);
        if (c == '"') {
            return new Literal(start, quotedEnd(source, start, 0, startsMultiline(source, start, 0)));
        }
        if (c == '`') {
            return new Literal(start, backtickEnd(source, start));
        }
        if (c == '#') {
            int hashes = 0;
            int i = start;
            while (i < source.length() && source.charAt(i) == '#') {
                hashes++;
                i++;
            }
            if (i >= source.length() || source.charAt(i) != '"') {
                // #if, #available, #selector, #file and friends are code, not a raw string.
                return null;
            }
            return new Literal(start, quotedEnd(source, i, hashes, startsMultiline(source, i, hashes)));
        }
        return null;
    }

    /**
     * @return {@code true} when the quote run at {@code quote} opens a multi-line string
     *
     * <p>A plain {@code """} is a multi-line string to Swift even when the content does not begin on the
     * next line - the lexer commits and then reports the malformed content as an error - so it is one
     * here too. A raw {@code #"""} is different: Swift only reads it as multi-line when the content
     * begins on the next line, and otherwise reads a single-line raw string opened by {@code #"} whose
     * content starts with two quotes. That distinction matters, because treating
     * {@code #"""x"#} as an unterminated multi-line string would hide the rest of the file.
     */
    private static boolean startsMultiline(String source, int quote, int hashes) {
        if (quote + 2 >= source.length()
                || source.charAt(quote + 1) != '"' || source.charAt(quote + 2) != '"') {
            return false;
        }
        if (hashes == 0) {
            return true;
        }
        int after = quote + 3;
        return after < source.length()
                && (source.charAt(after) == '\n' || source.charAt(after) == '\r');
    }

    /**
     * @param source    the source text
     * @param open      the offset of the opening quote (the first of three for a multi-line string)
     * @param hashes    how many {@code #} signs the raw delimiter carries, zero for an ordinary string
     * @param multiline whether this is a multi-line string
     * @return the offset just past the literal
     *
     * <p>An unterminated single-line string stops at the end of its own line rather than swallowing the
     * rest of the file; a multi-line string that never closes runs to the end of the file, which is what
     * the compiler reports too.
     */
    private static int quotedEnd(String source, int open, int hashes, boolean multiline) {
        int delimiter = multiline ? 3 : 1;
        int length = source.length();
        int i = open + delimiter;
        while (i < length) {
            char c = source.charAt(i);
            if (c == '\\') {
                int j = i + 1;
                int seen = 0;
                while (seen < hashes && j < length && source.charAt(j) == '#') {
                    j++;
                    seen++;
                }
                if (seen == hashes && j < length) {
                    if (source.charAt(j) == '(') {
                        i = interpolationEnd(source, j + 1);
                        continue;
                    }
                    if (!multiline && (source.charAt(j) == '\n' || source.charAt(j) == '\r')) {
                        // A backslash-newline continues a multi-line string; in a single-line string it
                        // is invalid, and the literal is unterminated at the line end.
                        return i;
                    }
                    i = j + 1;
                    continue;
                }
                // Not an escape at this delimiter width: in a raw string the backslash is content.
                i++;
                continue;
            }
            if (c == '"' && closesHere(source, i, delimiter, hashes)) {
                return i + delimiter + hashes;
            }
            if (!multiline && (c == '\n' || c == '\r')) {
                return i;
            }
            i++;
        }
        return length;
    }

    /**
     * @return {@code true} when the delimiter that closes a literal begins at {@code at}
     *
     * <p>The closing delimiter is the <em>first</em> run of the required quotes followed by the required
     * hashes, not the last of a longer run: Swift closes at the first three quotes and reads any fourth as
     * the start of the next token, which is why {@code """"} at the end of a multi-line string is an
     * unterminated string rather than a quote inside one.
     */
    private static boolean closesHere(String source, int at, int delimiter, int hashes) {
        if (at + delimiter + hashes > source.length()) {
            return false;
        }
        for (int k = 0; k < delimiter; k++) {
            if (source.charAt(at + k) != '"') {
                return false;
            }
        }
        for (int k = 0; k < hashes; k++) {
            if (source.charAt(at + delimiter + k) != '#') {
                return false;
            }
        }
        return true;
    }

    /**
     * @return the offset just past the {@code )} that closes an interpolation hole opened before it
     *
     * <p>The hole contains Swift, so its parentheses nest and it may hold further strings and comments;
     * those are consumed with the offsets this class would give them, which keeps the recursion in step
     * with the text. A hole that never closes runs to the end of the file.
     */
    private static int interpolationEnd(String source, int start) {
        int length = source.length();
        int depth = 1;
        int i = start;
        while (i < length) {
            char c = source.charAt(i);
            if (c == '(') {
                depth++;
                i++;
                continue;
            }
            if (c == ')') {
                depth--;
                i++;
                if (depth == 0) {
                    return i;
                }
                continue;
            }
            if (c == '/' && i + 1 < length && source.charAt(i + 1) == '/') {
                i = lineEnd(source, i);
                continue;
            }
            if (c == '/' && i + 1 < length && source.charAt(i + 1) == '*') {
                i = blockCommentEnd(source, i);
                continue;
            }
            Literal nested = literal(source, i);
            if (nested != null) {
                i = nested.end();
                continue;
            }
            i++;
        }
        return length;
    }

    /** @return the offset just past a backtick identifier, or the end of the line when it never closes. */
    private static int backtickEnd(String source, int start) {
        int i = start + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '`') {
                return i + 1;
            }
            if (c == '\n') {
                return i;
            }
            i++;
        }
        return source.length();
    }

    /** @return the offset just past a block comment that opens at {@code start}, honouring nesting. */
    private static int blockCommentEnd(String source, int start) {
        int length = source.length();
        int depth = 1;
        int i = start + 2;
        while (i < length) {
            if (source.charAt(i) == '/' && i + 1 < length && source.charAt(i + 1) == '*') {
                depth++;
                i += 2;
                continue;
            }
            if (source.charAt(i) == '*' && i + 1 < length && source.charAt(i + 1) == '/') {
                depth--;
                i += 2;
                if (depth == 0) {
                    return i;
                }
                continue;
            }
            i++;
        }
        return length;
    }

    /** @return a copy of the source with every non-newline character replaced by a space. */
    private static char[] blanked(String source) {
        char[] out = source.toCharArray();
        for (int i = 0; i < out.length; i++) {
            if (out[i] != '\n' && out[i] != '\r') {
                out[i] = ' ';
            }
        }
        return out;
    }

    /** Blanks {@code [start, end)} in a view, keeping every newline so line numbers survive. */
    private static void blank(char[] out, String source, int start, int end) {
        for (int i = start; i < end && i < source.length(); i++) {
            char c = source.charAt(i);
            if (c != '\n' && c != '\r') {
                out[i] = ' ';
            }
        }
    }

    /** Copies {@code [start, end)} of the source into a view, which is how a comment is kept. */
    private static void copy(char[] out, String source, int start, int end) {
        for (int i = start; i < end && i < source.length(); i++) {
            out[i] = source.charAt(i);
        }
    }

    /** @return the offset of the newline that ends the line holding {@code start}, or the end of the file. */
    private static int lineEnd(String source, int start) {
        int i = start;
        while (i < source.length() && source.charAt(i) != '\n' && source.charAt(i) != '\r') {
            i++;
        }
        return i;
    }
}
