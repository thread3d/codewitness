package dev.codewitness.ruby.analysis;

import dev.codewitness.ruby.RubyProjectDetector;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reads Ruby source the way the language defines it, so that no rule and no mutant is decided by text that
 * only looks like code.
 *
 * <p>Ruby's literals are close enough to the C family's that a scanner written for another language
 * compiles against it and still gets the interesting cases wrong in both directions:
 *
 * <ul>
 *   <li>{@code #} runs to the end of the line, but only in code: {@code "#{x}"}, {@code %q(#)}, a heredoc
 *       body and a {@code /regex/} all carry a {@code #} that is text;</li>
 *   <li>{@code =begin} ... {@code =end} is a comment that spans whole lines and opens only in the first
 *       column, so a line-at-a-time scanner would read embedded documentation as code;</li>
 *   <li>a single-quoted string takes only {@code \\} and {@code \'} as escapes, while a double-quoted one
 *       interpolates {@code #{...}}, {@code #$global} and {@code #@ivar} - and the brace form contains
 *       real code, including nested strings and braces, so the hole is consumed as part of the literal
 *       rather than left to be scanned as Ruby. That is the conservative direction: missing a site inside
 *       a hole costs one finding, while reading prose as code invents one;</li>
 *   <li>{@code %q()}, {@code %Q()}, {@code %w[]}, {@code %i[]}, {@code %x{}} and {@code %r{}} are literals
 *       whose delimiter may be any punctuation character and whose paired delimiters nest;</li>
 *   <li>a heredoc ({@code <<~ID}, {@code <<-ID}, {@code <<ID}, {@code <<'ID'}, {@code <<"ID"}) spans any
 *       number of lines and ends only at its own marker, so a line-at-a-time scanner would treat the body
 *       as code; and</li>
 *   <li>{@code __END__} in the first column ends the program: what follows is data the interpreter never
 *       parses.</li>
 * </ul>
 *
 * <p>Every projection this class produces has exactly the same length and the same newlines as the input,
 * so a match found in one of them maps back to the same line in the original file. Two views are produced
 * in a single pass, because the rule table needs both and two passes could disagree:
 *
 * <ul>
 *   <li>{@link Views#code} - comments blanked, literal <em>text kept</em>. A rule that is <em>about</em> a
 *       literal (a hardcoded credential, an interpolated SQL statement) reads this view, and a
 *       commented-out line is invisible to it;</li>
 *   <li>{@link Views#masked} - comments blanked, and the <em>contents</em> of every literal blanked while
 *       its delimiters stay, so {@code password = "..."} still reads as an assignment of a string
 *       literal but no rule can match text that merely sits inside one.</li>
 * </ul>
 *
 * <p>Known limits, stated rather than hidden. Ruby decides whether {@code /}, {@code <<} and {@code %}
 * open a literal or an operator from the state of the expression before them, and that decision belongs
 * to a parser rather than to this scanner, so each is resolved by the cheapest test that agrees with Ruby
 * in ordinary code. A {@code /} is read as a regular expression only where a value can begin - after a
 * bracket, an operator, the start of a line or a keyword such as {@code when} - which is right for
 * {@code gsub(/x/)}, {@code x =~ /x/} and {@code return /x/} and wrong for a regular expression written as
 * a bare argument to a word operator ({@code split /x/}). A heredoc opener is read only when it is not
 * glued to the value before it, which is what separates {@code puts &lt;&lt;~MSG} from
 * {@code list&lt;&lt;item}; a bare {@code %} is read as a literal only when its delimiter follows it with
 * no space between them, which is what separates {@code %(text)} from {@code a % (b)}. A quoted string or
 * percent literal that opens and never closes is not consumed past the end of its own line, so a misread
 * operator hides one line rather than the rest of the file; the exception is a heredoc whose marker never
 * appears, which is treated as a shift rather than as an unterminated literal. {@code ?x} character
 * literals and the {@code __FILE__} family are read as ordinary code. None of these produce a finding by
 * themselves; they can only hide one.
 */
public final class RubySourceScan {

    /**
     * Words after which a {@code /} opens a regular expression rather than dividing.
     *
     * <p>{@code total / count} is the common case and the preceding word is a value, so the default is
     * division. These keywords expect an expression instead, which is what makes {@code return /x/} and
     * {@code when /x/} read as the literals they are.
     */
    private static final Set<String> EXPRESSION_KEYWORDS = Set.of(
            "if", "unless", "while", "until", "when", "return", "then", "else", "elsif", "case", "and",
            "or", "not", "do", "begin", "yield", "in", "rescue", "break", "next");

    /** The characters after which a {@code /} opens a literal: an operator, a bracket or a separator. */
    private static final String EXPRESSION_END = "([{,;=!&|?:+-*%^~<>";

    private RubySourceScan() {
    }

    /**
     * Two offset-aligned readings of one source file.
     *
     * @param code   comments blanked, literal text kept
     * @param masked comments blanked, literal contents blanked, delimiters and newlines kept
     */
    public record Views(String code, String masked) {
    }

    /**
     * A literal the scanner recognised.
     *
     * @param start        the offset of the opener's first character
     * @param end          the offset just past the literal
     * @param contentStart the offset of the first character of the literal's contents
     * @param contentEnd   the offset of the closing delimiter, or of the end of the literal when there is
     *                     none
     * @param delimiters   whether the masked view keeps the opener and the closer, which is what lets an
     *                     assignment of a literal still read as one
     */
    private record Literal(int start, int end, int contentStart, int contentEnd, boolean delimiters) {
    }

    /**
     * A heredoc opener, parsed but not yet closed.
     *
     * @param identifier the marker that ends the body
     * @param after      the offset just past the opener, from which the next opener on the same line is
     *                   searched for
     * @param indented   whether the marker may be indented ({@code <<~} or {@code <<-})
     */
    private record Opener(String identifier, int after, boolean indented) {
    }

    /**
     * @param source the source text
     * @return both views of the file, produced in one pass so that they cannot disagree; an empty input
     *     yields two empty views rather than {@code null}
     */
    public static Views scan(String source) {
        if (source == null || source.isEmpty()) {
            return new Views("", "");
        }
        StringBuilder code = new StringBuilder(source.length());
        StringBuilder masked = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (atLineStart(source, i) && endsProgram(source, i)) {
                // __END__ is not a comment, but everything after it is data the interpreter never parses.
                // Blanking it here is what keeps a fixture or a block of YAML at the end of a file from
                // being read as one long method body by a rule.
                i = blankBoth(code, masked, source, i, source.length());
                continue;
            }
            if (atLineStart(source, i) && openerAt(source, i, "=begin")) {
                i = blankBoth(code, masked, source, i, embeddedDocEnd(source, i));
                continue;
            }
            if (c == '#') {
                i = blankBoth(code, masked, source, i, lineEnd(source, i));
                continue;
            }
            Literal literal = literal(source, i);
            if (literal != null) {
                appendLiteral(source, literal, code, masked);
                i = literal.end();
                continue;
            }
            code.append(c);
            masked.append(c);
            i++;
        }
        return new Views(code.toString(), masked.toString());
    }

    /**
     * @param file a Ruby source file
     * @return both views of a file read through {@link RubyProjectDetector#readString(Path)} - which
     *     answers the empty string for a file that does not exist or cannot be read - so that an
     *     unreadable file yields two empty views rather than an exception
     */
    public static Views scan(Path file) {
        return scan(RubyProjectDetector.readString(file));
    }

    /**
     * @param root a directory to read a project's sources from
     * @return the Ruby files below the root that a rule must read, in path order
     *
     * <p>{@link RubyProjectDetector#rubyFiles(Path)} already applies the same exclusions; the check is
     * repeated here because this is the method the analyzers trust, and a filter that lives only inside
     * another class's walk is one refactor away from being lost.
     */
    public static List<Path> sourceFiles(Path root) {
        List<Path> files = new ArrayList<>();
        for (Path file : RubyProjectDetector.rubyFiles(root)) {
            if (!RubyProjectDetector.isExcludedSource(root, file)) {
                files.add(file);
            }
        }
        return List.copyOf(files);
    }

    /**
     * @param source the source text
     * @return the source with comments blanked and literal text kept - the view a rule reads when its
     *     pattern is about the contents of a literal
     */
    public static String withoutComments(String source) {
        return scan(source).code();
    }

    /**
     * @param source the source text
     * @return the source with comments blanked and the contents of every literal blanked, delimiters and
     *     newlines kept - the view a rule reads when it is looking for a statement shape
     */
    public static String stripLiterals(String source) {
        return scan(source).masked();
    }

    /**
     * Skips the literal that opens at {@code start}.
     *
     * @param source the source text
     * @param start  the offset of the candidate opener
     * @return the offset just past the literal, or {@code -1} when no literal opens there
     */
    public static int literalEnd(String source, int start) {
        Literal literal = literal(source, start);
        return literal == null ? -1 : literal.end();
    }

    /**
     * @param source the source text
     * @param offset an offset into it
     * @return the 1-based line number of that offset
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
     * @return the literal that opens at {@code start}, or {@code null} when the text there is code
     */
    private static Literal literal(String source, int start) {
        if (source == null || start < 0 || start >= source.length()) {
            return null;
        }
        char c = source.charAt(start);
        if (c == '\'' || c == '"') {
            return quoted(source, start, c);
        }
        if (c == '<') {
            return heredoc(source, start);
        }
        if (c == '%') {
            return percentLiteral(source, start);
        }
        if (c == '/' && expressionStart(source, start)) {
            return regex(source, start);
        }
        return null;
    }

    /**
     * @return the offset just past the quoted literal that opens at {@code start}
     *
     * <p>An unterminated literal stops at the end of its line rather than swallowing the rest of the file:
     * a scan that hid every later rule would be worse than one that reports the literal as ending early.
     */
    private static Literal quoted(String source, int start, char quote) {
        boolean interpolates = quote != '\'';
        int i = start + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                // A backslash escapes the next character in a single- and in a double-quoted string alike
                // - \' in the first, anything at all in the second - so the next character can never end
                // the literal.
                i += 2;
                continue;
            }
            if (c == quote) {
                return new Literal(start, i + 1, start + 1, i, true);
            }
            if (c == '\n') {
                return new Literal(start, i, start + 1, i, true);
            }
            if (interpolates && c == '#' && i + 1 < source.length()) {
                char next = source.charAt(i + 1);
                if (next == '{') {
                    i = interpolationHoleEnd(source, i + 2);
                    continue;
                }
                if (next == '$' || next == '@') {
                    // #$global and #@ivar interpolate a bare name; the name ends at the first character
                    // that cannot be part of one.
                    i += 2;
                    while (i < source.length() && (isIdentifierChar(source.charAt(i)) || source.charAt(i) == '@')) {
                        i++;
                    }
                    continue;
                }
            }
            i++;
        }
        return new Literal(start, source.length(), start + 1, source.length(), true);
    }

    /**
     * @return the offset just past the {@code }} that closes a {@code #{...}} hole opened before it
     *
     * <p>The hole contains Ruby, so its braces nest and it may hold further literals; those are consumed
     * with the offsets this class would give them, which keeps the recursion in step with the text.
     */
    private static int interpolationHoleEnd(String source, int start) {
        int i = start;
        int depth = 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
                i++;
                continue;
            }
            if (c == '}') {
                depth--;
                i++;
                if (depth == 0) {
                    return i;
                }
                continue;
            }
            Literal nested = literal(source, i);
            if (nested != null) {
                i = nested.end();
                continue;
            }
            i++;
        }
        return source.length();
    }

    /**
     * @return the offset just past the {@code %}-literal that opens at {@code start}, or {@code null}
     *     when the {@code %} there is the modulo operator
     */
    private static Literal percentLiteral(String source, int start) {
        int length = source.length();
        int i = start + 1;
        char type = 0;
        if (i < length && isPercentType(source.charAt(i))) {
            type = source.charAt(i);
            i++;
        }
        if (i >= length) {
            return null;
        }
        char open = source.charAt(i);
        if (Character.isLetterOrDigit(open) || open == '_' || Character.isWhitespace(open)) {
            // "%" followed by a name or a space is the modulo operator: x % 5, a % b.
            return null;
        }
        char close = matchingDelimiter(open);
        boolean nests = close != open;
        boolean interpolates = type == 0 || type == 'Q' || type == 'W' || type == 'I' || type == 'x'
                || type == 'r';
        int contentStart = i + 1;
        int closeAt = percentClose(source, contentStart, open, close, nests, interpolates);
        if (closeAt < 0) {
            // Nothing closes it: end at the end of the opening line so that a misread modulo hides one
            // line rather than the rest of the file.
            int eol = lineEnd(source, start);
            return new Literal(start, eol, contentStart, eol, true);
        }
        return new Literal(start, closeAt + 1, contentStart, closeAt, true);
    }

    /**
     * @return the offset of the delimiter that closes a {@code %}-literal, or {@code -1} when the text
     *     runs out first
     */
    private static int percentClose(
            String source, int from, char open, char close, boolean nests, boolean interpolates) {
        int depth = 1;
        int i = from;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (interpolates && c == '#' && i + 1 < source.length() && source.charAt(i + 1) == '{') {
                i = interpolationHoleEnd(source, i + 2);
                continue;
            }
            if (nests && c == open) {
                depth++;
                i++;
                continue;
            }
            if (c == close) {
                depth--;
                i++;
                if (depth == 0) {
                    return i - 1;
                }
                continue;
            }
            i++;
        }
        return -1;
    }

    /**
     * @return the heredoc that opens at {@code start}, or {@code null} when the {@code <<} there is a
     *     shift or an append
     *
     * <p>Ruby allows more than one heredoc on a line ({@code call(&lt;&lt;~A, &lt;&lt;~B)}), whose bodies
     * follow one another after the opening line; they are collected first and then consumed in order, so
     * the second body is not scanned as code.
     *
     * <p>A heredoc whose marker never appears is not treated as a literal at all. Either it is a shift
     * that merely looks like an opener ({@code list&lt;&lt;item}), or the file does not parse; in both
     * cases consuming the rest of the file would hide every later rule.
     */
    private static Literal heredoc(String source, int start) {
        Opener first = heredocOpener(source, start);
        if (first == null) {
            return null;
        }
        int openingLineEnd = lineEnd(source, start);
        List<Opener> openers = new ArrayList<>();
        openers.add(first);
        int cursor = first.after();
        while (cursor < openingLineEnd) {
            int at = source.indexOf("<<", cursor);
            if (at < 0 || at >= openingLineEnd) {
                break;
            }
            Opener next = heredocOpener(source, at);
            if (next == null) {
                cursor = at + 2;
                continue;
            }
            openers.add(next);
            cursor = next.after();
        }
        int lineStart = openingLineEnd < source.length() ? openingLineEnd + 1 : source.length();
        int end = -1;
        for (int index = 0; index < openers.size(); index++) {
            int marker = heredocMarkerEnd(source, lineStart, openers.get(index));
            if (marker < 0) {
                if (index == 0) {
                    // The first opener has no marker at all: this is a shift or a file that does not
                    // parse, not a heredoc.
                    return null;
                }
                // A later opener has none: keep the bodies that did close and let the rest be scanned as
                // code, which is the bounded failure rather than the whole-file one.
                break;
            }
            end = marker;
            int markerLineEnd = lineEnd(source, end);
            lineStart = markerLineEnd < source.length() ? markerLineEnd + 1 : source.length();
        }
        return new Literal(start, end, start, end, false);
    }

    /** @return the opener parsed at {@code at}, or {@code null} when the text there is not one. */
    private static Opener heredocOpener(String source, int at) {
        if (at + 1 >= source.length() || source.charAt(at) != '<' || source.charAt(at + 1) != '<') {
            return null;
        }
        if (!expressionStart(source, at) && (at == 0 || !isLineSpace(source.charAt(at - 1)))) {
            // "list<<item" appends without a space and is the shape a heredoc would have if Ruby decided
            // by punctuation alone; Ruby decides by the expression before it, and the space before the
            // opener is what tells the two apart in every shape this scanner can see.
            return null;
        }
        int i = at + 2;
        boolean indented = false;
        if (i < source.length() && (source.charAt(i) == '~' || source.charAt(i) == '-')) {
            indented = true;
            i++;
        }
        if (i >= source.length()) {
            return null;
        }
        char quote = 0;
        if (source.charAt(i) == '\'' || source.charAt(i) == '"' || source.charAt(i) == '`') {
            quote = source.charAt(i);
            i++;
        }
        int identifierStart = i;
        if (i >= source.length() || !isIdentifierStart(source.charAt(i))) {
            // "<< b" is a shift; a heredoc's marker follows its opener with nothing between them.
            return null;
        }
        while (i < source.length() && isIdentifierChar(source.charAt(i))) {
            i++;
        }
        String identifier = source.substring(identifierStart, i);
        if (quote != 0) {
            if (i >= source.length() || source.charAt(i) != quote) {
                return null;
            }
            i++;
        }
        return new Opener(identifier, i, indented);
    }

    /**
     * @return the offset just past the marker that closes the heredoc, or {@code -1} when the file ends
     *     first
     */
    private static int heredocMarkerEnd(String source, int from, Opener opener) {
        int lineStart = from;
        while (lineStart <= source.length()) {
            int end = lineEnd(source, lineStart);
            int first = lineStart;
            if (opener.indented()) {
                while (first < end && (source.charAt(first) == ' ' || source.charAt(first) == '\t')) {
                    first++;
                }
            } else {
                // A bare <<ID marker has to start in the first column; <<-ID and <<~ID may be indented.
                first = lineStart;
            }
            int after = first + opener.identifier().length();
            if (after <= end && source.startsWith(opener.identifier(), first)
                    && (after == end || !isIdentifierChar(source.charAt(after)))) {
                return after;
            }
            if (end >= source.length()) {
                break;
            }
            lineStart = end + 1;
        }
        return -1;
    }

    /**
     * @return the regular expression that opens at {@code start}, with any flags after its closing
     *     delimiter left as code
     *
     * <p>A regular expression is required to close on its own line. A {@code /} that divides and was
     * misread as an opener is the one false positive this class can produce, and stopping at the newline
     * bounds what it hides; a regular expression that legitimately spans lines is written
     * {@code %r{...}}, which is read as a percent literal.
     */
    private static Literal regex(String source, int start) {
        int i = start + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\n') {
                return new Literal(start, i, start + 1, i, true);
            }
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '/') {
                return new Literal(start, i + 1, start + 1, i, true);
            }
            if (c == '#' && i + 1 < source.length() && source.charAt(i + 1) == '{') {
                int holeEnd = interpolationHoleEnd(source, i + 2);
                int newline = source.indexOf('\n', i);
                if (newline >= 0 && newline < holeEnd) {
                    return new Literal(start, newline, start + 1, newline, true);
                }
                i = holeEnd;
                continue;
            }
            i++;
        }
        return new Literal(start, source.length(), start + 1, source.length(), true);
    }

    /**
     * @return {@code true} when a {@code /} at {@code at} opens a literal rather than dividing
     *
     * <p>{@code total / count} is the common case, so an operator preceded by a value is division. A
     * bracket, an operator character, the start of a line or a keyword that expects an expression all
     * mean a literal. The one shape this gets wrong in the direction of a false positive is
     * {@code x /y} written as division with the space on the wrong side; {@code x / y} is division here,
     * as it is to Ruby.
     */
    private static boolean expressionStart(String source, int at) {
        int i = at - 1;
        while (i >= 0 && (source.charAt(i) == ' ' || source.charAt(i) == '\t' || source.charAt(i) == '\r')) {
            i--;
        }
        if (i < 0 || source.charAt(i) == '\n') {
            return true;
        }
        if (EXPRESSION_END.indexOf(source.charAt(i)) >= 0) {
            return true;
        }
        int end = i + 1;
        while (i >= 0 && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '_')) {
            i--;
        }
        return EXPRESSION_KEYWORDS.contains(source.substring(i + 1, end));
    }

    /** @return the delimiter that closes one opened by {@code open}. */
    private static char matchingDelimiter(char open) {
        return switch (open) {
            case '(' -> ')';
            case '[' -> ']';
            case '{' -> '}';
            case '<' -> '>';
            default -> open;
        };
    }

    private static boolean isPercentType(char c) {
        return "qQwWiIxrs".indexOf(c) >= 0;
    }

    /** @return {@code true} when the token at {@code at} is {@code word} followed by whitespace or end. */
    private static boolean openerAt(String source, int at, String word) {
        if (!source.startsWith(word, at)) {
            return false;
        }
        int end = at + word.length();
        return end >= source.length() || isLineSpace(source.charAt(end));
    }

    /**
     * @return the offset of the newline that ends the {@code =end} line, or the end of the file when
     *     there is no {@code =end}
     *
     * <p>No {@code =end} is a syntax error, and the interpreter reports it as one; for this scanner the
     * rest of the file is comment text, which is the same conclusion the parser reaches about it.
     */
    private static int embeddedDocEnd(String source, int start) {
        int lineStart = lineEnd(source, start);
        lineStart = lineStart < source.length() ? lineStart + 1 : source.length();
        while (lineStart < source.length()) {
            if (openerAt(source, lineStart, "=end")) {
                return lineEnd(source, lineStart);
            }
            int end = lineEnd(source, lineStart);
            if (end >= source.length()) {
                break;
            }
            lineStart = end + 1;
        }
        return source.length();
    }

    /** @return {@code true} when the line at {@code at} is {@code __END__} and nothing else. */
    private static boolean endsProgram(String source, int at) {
        if (!source.startsWith("__END__", at)) {
            return false;
        }
        int end = lineEnd(source, at);
        for (int i = at + "__END__".length(); i < end; i++) {
            if (!isLineSpace(source.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Copies a literal into both views: the code view keeps its text verbatim, and the masked view keeps
     * its newlines and - where the literal has a shape a reader would recognise, such as quotes or a
     * percent literal's delimiters - its delimiters, and blanks everything else. A heredoc's opener and
     * body carry no shape a code rule reads, so both are blanked.
     */
    private static void appendLiteral(String source, Literal literal, StringBuilder code, StringBuilder masked) {
        for (int i = literal.start(); i < literal.end(); i++) {
            char c = source.charAt(i);
            code.append(c);
            if (c == '\n') {
                masked.append('\n');
            } else if (literal.delimiters()
                    && (i < literal.contentStart() || i >= literal.contentEnd())) {
                masked.append(c);
            } else {
                masked.append(' ');
            }
        }
    }

    /** Blanks {@code [start, end)} in both views, keeping every newline so line numbers survive. */
    private static int blankBoth(
            StringBuilder code, StringBuilder masked, String source, int start, int end) {
        for (int i = start; i < end && i < source.length(); i++) {
            char c = source.charAt(i);
            code.append(c == '\n' ? '\n' : ' ');
            masked.append(c == '\n' ? '\n' : ' ');
        }
        return end;
    }

    /** @return {@code true} when {@code at} is the first character of a line. */
    private static boolean atLineStart(String source, int at) {
        return at == 0 || source.charAt(at - 1) == '\n';
    }

    /** @return the offset of the newline that ends the line holding {@code start}, or the end of the file. */
    private static int lineEnd(String source, int start) {
        int i = start;
        while (i < source.length() && source.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    private static boolean isLineSpace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c >= 0x80;
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_' || c >= 0x80;
    }
}
