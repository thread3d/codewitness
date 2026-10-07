package dev.codewitness.cpp;

/**
 * Scans C or C++ source once and derives the three views the rule table reads it through.
 *
 * <p>Every rule needs to see code and nothing else, but "nothing else" means different things to
 * different rules. A comment that describes a hazard is not the hazard, so comments have to go. A
 * credential rule, on the other hand, is <em>about</em> a literal, so it needs the literal's contents while
 * every other rule must not match text that merely sits inside a string. And a rule that reports a
 * suppression directive is about a comment, so it needs the one thing the other two views throw away. Hence
 * three views, produced in a single pass:
 *
 * <ul>
 *   <li>{@code code} - comments removed, literal contents preserved;</li>
 *   <li>{@code masked} - comments removed, literal contents blanked but the delimiters kept, so
 *       {@code password = "}" still reads as an assignment of a string literal;</li>
 *   <li>{@code directives} - comments kept, literal contents blanked, so a URL such as
 *       {@code "http://pragma"} cannot be mistaken for a directive.</li>
 * </ul>
 *
 * <p>The pass runs over the whole file rather than line by line because a C++ literal may span lines: a
 * raw string {@code R"delim(...)delim"} ends only at a closing parenthesis followed by the same delimiter,
 * which may be an arbitrary distance away, and a backslash-newline continues a literal on the next line. A
 * line-at-a-time scanner would lose that state and start treating a continuation line as code.
 *
 * <p>All three views have exactly the same length and the same line structure as the input, which is what
 * lets a match in any of them be reported with a real line number and quoted from the original.
 */
public final class CppSourceScan {

    private CppSourceScan() {
    }

    /** The three views of one source file, all of them offset-aligned with the input. */
    public record Views(String code, String masked, String directives) {
    }

    /** @return the three views of a C or C++ source file. */
    public static Views scan(String source) {
        StringBuilder code = new StringBuilder(source.length());
        StringBuilder masked = new StringBuilder(source.length());
        StringBuilder directives = new StringBuilder(source.length());
        int i = 0;
        boolean inBlockComment = false;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (inBlockComment) {
                if (c == '*' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                    blank(code, masked, directives, 2);
                    i += 2;
                    inBlockComment = false;
                } else {
                    blank(code, masked, directives, 1);
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                int end = source.indexOf('\n', i);
                if (end < 0) {
                    end = source.length();
                }
                // The directive view keeps the comment, because reporting a suppression - a NOLINT, a
                // cppcheck-suppress, a pragma in a trailing comment - is its purpose.
                directives.append(source, i, end);
                blank(code, masked, null, end - i);
                i = end;
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                inBlockComment = true;
                blank(code, masked, directives, 2);
                i += 2;
                continue;
            }
            int literalEnd = literalEnd(source, i);
            if (literalEnd > i) {
                appendLiteral(source, i, literalEnd, code, masked, directives);
                i = literalEnd;
                continue;
            }
            code.append(c);
            masked.append(c);
            directives.append(c);
            i++;
        }
        return new Views(code.toString(), masked.toString(), directives.toString());
    }

    /**
     * @param source the C or C++ source, with line endings already normalised to {@code \n}
     * @param start  the offset of a candidate literal opener
     * @return the offset just past the literal, or {@code start} when no literal opens here
     */
    public static int literalEnd(String source, int start) {
        char c = source.charAt(start);
        if (c == '\'') {
            return endOfCharacterLiteral(source, start);
        }
        int rawAt = rawStringStart(source, start);
        if (rawAt > start) {
            return endOfRawString(source, rawAt);
        }
        if (c == '"') {
            return endOfQuoted(source, start, '"');
        }
        return start;
    }

    /**
     * @return the offset of the quote that opens a raw string beginning at {@code start}, or {@code start}
     *     when the text there is not one
     *
     * <p>A raw string is written {@code R"delim(...)delim"} and may carry an encoding prefix
     *     ({@code u8}, {@code u}, {@code U}, {@code L}), which is why the {@code R} is looked for after the
     *     optional prefix rather than at the start. The identifier {@code R} followed by a quote is a user
     *     defined literal operator only in a context this scanner cannot see, and reading it as a raw
     *     string is the conservative direction: it stops the scan rather than letting the rest of the file
     *     be read as code.
     */
    static int rawStringStart(String source, int start) {
        int i = start;
        if (i < source.length() && (source.charAt(i) == 'u' || source.charAt(i) == 'U' || source.charAt(i) == 'L')) {
            i++;
            if (source.charAt(i - 1) == 'u' && i < source.length() && source.charAt(i) == '8') {
                i++;
            }
        }
        if (i >= source.length() || source.charAt(i) != 'R') {
            return start;
        }
        return i + 1 < source.length() && source.charAt(i + 1) == '"' ? i + 1 : start;
    }

    /** @return the offset just past a raw string whose opening quote is at {@code quoteAt}. */
    private static int endOfRawString(String source, int quoteAt) {
        int open = source.indexOf('(', quoteAt + 1);
        if (open < 0) {
            // A raw string's delimiter may hold at most sixteen characters; a longer run means this is not
            // one, and the scan resumes rather than swallowing the file.
            return quoteAt;
        }
        String delimiter = source.substring(quoteAt + 1, open);
        if (delimiter.length() > 16 || delimiter.indexOf(' ') >= 0 || delimiter.indexOf(')') >= 0
                || delimiter.indexOf('\\') >= 0) {
            return quoteAt;
        }
        String terminator = ")" + delimiter + "\"";
        int close = source.indexOf(terminator, open + 1);
        return close < 0 ? source.length() : close + terminator.length();
    }

    /**
     * @return the offset just past a quoted literal, or before the newline when it is unterminated
     *
     * <p>A backslash escapes the next character, including a newline, which is what makes a multi-line
     *     string literal one literal rather than an error and a stray line of code.
     */
    private static int endOfQuoted(String source, int quoteAt, char quote) {
        int i = quoteAt + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            if (c == '\n') {
                // An unterminated literal on one line: stop before the newline rather than swallowing the
                // rest of the file, which would hide every later rule.
                return i;
            }
            i++;
        }
        return source.length();
    }

    /** @return the offset just past a character literal, or before the newline when it is unterminated. */
    private static int endOfCharacterLiteral(String source, int quoteAt) {
        return endOfQuoted(source, quoteAt, '\'');
    }

    /**
     * Copies a literal into the three views: the code view keeps its text, the other two keep only its
     * delimiters and blank its contents, and all three keep its newlines so offsets stay aligned.
     */
    private static void appendLiteral(
            String source, int start, int end, StringBuilder code, StringBuilder masked, StringBuilder directives) {
        for (int i = start; i < end; i++) {
            char c = source.charAt(i);
            code.append(c);
            if (c == '\n') {
                masked.append('\n');
                directives.append('\n');
            } else if (c == '"' || c == '\'' || c == 'R' || c == 'L' || c == 'U') {
                masked.append(c);
                directives.append(c);
            } else {
                masked.append(' ');
                directives.append(' ');
            }
        }
    }

    private static void blank(StringBuilder code, StringBuilder masked, StringBuilder directives, int count) {
        for (int i = 0; i < count; i++) {
            code.append(' ');
            masked.append(' ');
            if (directives != null) {
                directives.append(' ');
            }
        }
    }

    /** @return the 1-based line number of an offset. */
    public static int lineOf(String source, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
