package dev.codewitness.php.analysis;

/**
 * Reads PHP source the way the language defines it, so that no rule and no mutant is decided by text that
 * only looks like code.
 *
 * <p>PHP's literals are not the C family's, and a scanner that assumes they are gets the interesting
 * cases wrong in both directions:
 *
 * <ul>
 *   <li>a single-quoted string takes only {@code \'} and {@code \\} as escapes, so a backslash before any
 *       other character is literal text;</li>
 *   <li>a double-quoted string and a backtick shell string interpolate {@code $name} and {@code {$...}},
 *       and the brace form contains real code - including nested strings and braces - so the hole is
 *       consumed as part of the literal rather than left to be scanned as PHP. That is the conservative
 *       direction: missing a site costs one mutant, while mutating inside a hole would change the
 *       string;</li>
 *   <li>a heredoc or nowdoc {@code <<<ID ... ID;} spans any number of lines and, since PHP 7.3, its
 *       closing marker may be indented, so a line-at-a-time scanner would treat the body as code;</li>
 *   <li>{@code #} starts a comment, but {@code #[} opens an attribute, which is code; and</li>
 *   <li>backticks are a shell command, not a quote.</li>
 * </ul>
 *
 * <p>Every projection this class produces has exactly the same length and the same newlines as the input,
 * so a match found in one of them maps back to the same line in the original file. Two views are
 * produced in a single pass, because the rule table needs both and two passes could disagree:
 *
 * <ul>
 *   <li>{@link Views#code} - comments blanked, literal <em>text kept</em>. A rule that is <em>about</em> a
 *       literal (a hardcoded credential, an interpolated SQL statement) reads this view, and a
 *       commented-out line is invisible to it;</li>
 *   <li>{@link Views#masked} - comments blanked, and the <em>contents</em> of every literal blanked while
 *       its delimiters stay, so {@code $password = "..."} still reads as an assignment of a string
 *       literal but no rule can match text that merely sits inside one.</li>
 * </ul>
 *
 * <p>Known limit, stated rather than hidden: a PHP file may close its {@code ?>} tag and continue as
 * HTML. Inline markup is scanned as if it were PHP code, because the build and test phases read such a
 * file as a template and no rule in this adapter is written against HTML.
 */
public final class PhpSourceScan {

    private PhpSourceScan() {
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
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                i = blankBoth(code, masked, source, i, lineEnd(source, i));
                continue;
            }
            if (c == '#' && !(i + 1 < source.length() && source.charAt(i + 1) == '[')) {
                // "#[" opens an attribute, which is code: treating it as a comment would hide a test
                // method's own #[Test] marker from the rule that looks for it.
                i = blankBoth(code, masked, source, i, lineEnd(source, i));
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                i = blankBoth(code, masked, source, i, blockCommentEnd(source, i));
                continue;
            }
            int end = literalEnd(source, i);
            if (end > i) {
                appendLiteral(source, i, end, code, masked);
                i = end;
                continue;
            }
            code.append(c);
            masked.append(c);
            i++;
        }
        return new Views(code.toString(), masked.toString());
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
     *     newlines kept - the view the specification calls the mask, and the one a rule reads when it is
     *     looking for a statement shape
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
        if (source == null || start < 0 || start >= source.length()) {
            return -1;
        }
        char c = source.charAt(start);
        if (c == '\'' || c == '"' || c == '`') {
            return quotedEnd(source, start, c);
        }
        if (c == '<') {
            int end = heredocEnd(source, start);
            return end > start ? end : -1;
        }
        return -1;
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
     * @return the offset just past the quoted literal that opens at {@code quoteAt}
     *
     * <p>An unterminated literal stops at the end of its line rather than swallowing the rest of the
     * file: a scan that hid every later rule would be worse than one that reports the literal as ending
     * early. A heredoc is the exception, because it legitimately spans lines and ends only at its own
     * marker.
     */
    private static int quotedEnd(String source, int quoteAt, char quote) {
        int i = quoteAt + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                // A backslash escapes the next character, including the closing quote; a backslash before
                // a newline continues the literal on the next line.
                i += 2;
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            if (c == '\n') {
                return i;
            }
            // Only a double-quoted or backtick literal interpolates: a single-quoted one is literal text,
            // and reading a hole into it would consume the string's own closing quote as code.
            if (quote != '\'' && c == '$' && i + 1 < source.length() && source.charAt(i + 1) == '{') {
                i = interpolationHoleEnd(source, i + 2);
                continue;
            }
            if (quote != '\'' && c == '{' && i + 1 < source.length() && source.charAt(i + 1) == '$') {
                i = interpolationHoleEnd(source, i + 2);
                continue;
            }
            i++;
        }
        return source.length();
    }

    /**
     * @return the offset just past the {@code }} that closes a {@code {$...}} or {@code ${...}} hole
     *     opened before it
     *
     * <p>The hole contains PHP, so its braces nest and it may hold further literals; those are consumed
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
            int end = literalEnd(source, i);
            if (end > i) {
                i = end;
                continue;
            }
            i++;
        }
        return source.length();
    }

    /**
     * @return the offset just past the closing marker of the heredoc whose {@code <<<} begins at
     *     {@code start}, or {@code start} when the text there is not a heredoc opener
     *
     * <p>The opener is validated before the body is consumed. {@code <<<} is also how a shift is written in
     * a language that only looks similar, and a scanner that accepted a shift as a heredoc would read the
     * rest of the file as one string.
     */
    private static int heredocEnd(String source, int start) {
        if (!source.startsWith("<<<", start)) {
            return start;
        }
        int i = start + 3;
        while (i < source.length() && (source.charAt(i) == ' ' || source.charAt(i) == '\t')) {
            i++;
        }
        char quote = 0;
        if (i < source.length() && (source.charAt(i) == '\'' || source.charAt(i) == '"')) {
            quote = source.charAt(i);
            i++;
        }
        int identifierStart = i;
        while (i < source.length() && isIdentifierChar(source.charAt(i))) {
            i++;
        }
        if (i == identifierStart || !isIdentifierStart(source.charAt(identifierStart))) {
            // A label may not begin with a digit, so "<<" followed by a number is a shift.
            return start;
        }
        String identifier = source.substring(identifierStart, i);
        if (quote != 0) {
            if (i >= source.length() || source.charAt(i) != quote) {
                return start;
            }
            i++;
        }
        int openingLineEnd = lineEnd(source, i);
        if (!source.substring(i, openingLineEnd).isBlank()) {
            // A heredoc's opening line ends after its identifier; anything else is not an opener.
            return start;
        }
        int lineStart = openingLineEnd < source.length() ? openingLineEnd + 1 : source.length();
        while (lineStart <= source.length()) {
            int end = lineEnd(source, lineStart);
            int first = lineStart;
            while (first < end && (source.charAt(first) == ' ' || source.charAt(first) == '\t')) {
                first++;
            }
            if (first + identifier.length() <= end && source.startsWith(identifier, first)
                    && (first + identifier.length() == end
                    || !isIdentifierChar(source.charAt(first + identifier.length())))) {
                return first + identifier.length();
            }
            if (end >= source.length()) {
                break;
            }
            lineStart = end + 1;
        }
        // No marker: the rest of the file is the body, which is what the interpreter reports too.
        return source.length();
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c >= 0x80;
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_' || c >= 0x80;
    }

    /**
     * Copies a literal into both views: the code view keeps its text verbatim, and the masked view keeps
     * its newlines and its delimiters - so that an assignment of a literal still reads as one - and blanks
     * everything else, including a heredoc's opener and body, which carry no shape a code rule reads.
     */
    private static void appendLiteral(
            String source, int start, int end, StringBuilder code, StringBuilder masked) {
        char opener = source.charAt(start);
        boolean quoted = opener == '\'' || opener == '"' || opener == '`';
        for (int i = start; i < end; i++) {
            char c = source.charAt(i);
            code.append(c);
            if (c == '\n') {
                masked.append('\n');
            } else if (quoted && (i == start || (i == end - 1 && c == opener))) {
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

    /** @return the offset just past a {@code /* *}{@code /} comment that opens at {@code start}. */
    private static int blockCommentEnd(String source, int start) {
        // PHP block comments do not nest, so the first "*/" ends the comment.
        int close = source.indexOf("*/", start + 2);
        return close < 0 ? source.length() : close + 2;
    }

    /** @return the offset of the newline that ends the line holding {@code start}, or the end of the file. */
    private static int lineEnd(String source, int start) {
        int i = start;
        while (i < source.length() && source.charAt(i) != '\n') {
            i++;
        }
        return i;
    }
}
