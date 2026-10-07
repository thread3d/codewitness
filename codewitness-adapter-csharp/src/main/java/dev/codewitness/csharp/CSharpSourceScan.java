package dev.codewitness.csharp;

/**
 * Scans C# source once and derives the three views the rule table reads it through.
 *
 * <p>Every rule needs to see code and nothing else, but "nothing else" means different things to
 * different rules. A comment that describes a hazard is not the hazard, so comments have to go. A
 * credential rule, on the other hand, is <em>about</em> a literal, so it needs the literal's contents
 * while every other rule must not match text that merely sits inside a string. And a rule that reports
 * a suppression directive is about a comment, so it needs the one thing the other two views throw away.
 * Hence three views, produced in a single pass:
 *
 * <ul>
 *   <li>{@code code} - comments removed, literal contents preserved;</li>
 *   <li>{@code masked} - comments removed, literal contents blanked but the delimiters kept, so
 *       {@code password = "}" still reads as an assignment of a string literal;</li>
 *   <li>{@code directives} - comments kept, literal contents blanked, so a URL such as
 *       {@code "http://pragma"} cannot be mistaken for a directive.</li>
 * </ul>
 *
 * <p>The pass runs over the whole file rather than line by line because a C# literal may span lines: a
 * verbatim string ({@code @"..."}) ends at the next undoubled quote, and a raw string
 * ({@code """..."""}) ends at the next run of at least as many quotes as opened it. A line-at-a-time
 * scanner would lose that state and start treating a continuation line as code.
 *
 * <p>All three views have exactly the same length and the same line structure as the input, which is
 * what lets a match in any of them be reported with a real line number and quoted from the original.
 */
public final class CSharpSourceScan {

    private CSharpSourceScan() {
    }

    /** The three views of one source file, all of them offset-aligned with the input. */
    public record Views(String code, String masked, String directives) {
    }

    /** @return the three views of a C# source file. */
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
                // The directive view keeps the comment, because reporting a suppression is its purpose.
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
     * @param source the C# source, with line endings already normalised to {@code \n}
     * @param start  the offset of a candidate literal opener
     * @return the offset just past the literal, or {@code start} when no literal opens here
     */
    public static int literalEnd(String source, int start) {
        char c = source.charAt(start);
        if (c == '\'') {
            return endOfCharacterLiteral(source, start);
        }
        int i = start;
        boolean interpolated = false;
        boolean verbatim = false;
        while (i < source.length() && (source.charAt(i) == '$' || source.charAt(i) == '@')) {
            if (source.charAt(i) == '$') {
                if (interpolated) {
                    return start;
                }
                interpolated = true;
            } else {
                if (verbatim) {
                    return start;
                }
                verbatim = true;
            }
            i++;
        }
        // An '@' that is not followed by a quote starts a verbatim identifier such as @class, not a
        // literal, and a '$' that is not followed by a quote is just a character.
        if (i >= source.length() || source.charAt(i) != '"') {
            return start;
        }
        return verbatim ? endOfVerbatimString(source, i) : endOfStringOrRawString(source, i);
    }

    /**
     * @return the offset just past a normal ({@code "..."}) or raw ({@code """..."""}) string.
     *
     * <p>An interpolated string is consumed whole, holes included. That is conservative on purpose: the
     * expressions inside holes are code, but the scanner has no brace-aware way to see them, and missing
     * a possible mutation site is better than reporting a site inside what looks like string data.
     */
    private static int endOfStringOrRawString (String source, int quoteAt) {
        int quotes = 0;
        while (quoteAt + quotes < source.length() && source.charAt(quoteAt + quotes) == '"') {
            quotes++;
        }
        if (quotes >= 3) {
            int i = quoteAt + quotes;
            while (i < source.length()) {
                if (source.charAt(i) == '"') {
                    int run = 0;
                    while (i + run < source.length() && source.charAt(i + run) == '"') {
                        run++;
                    }
                    if (run >= quotes) {
                        return i + run;
                    }
                    i += run;
                    continue;
                }
                i++;
            }
            return source.length();
        }
        int i = quoteAt + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '"') {
                return i + 1;
            }
            if (c == '\n') {
                // An unterminated string on one line: stop before the newline rather than swallowing the
                // rest of the file, which would hide every later rule.
                return i;
            }
            i++;
        }
        return source.length();
    }

    /** @return the offset just past a verbatim string, in which {@code ""} is an escaped quote. */
    private static int endOfVerbatimString(String source, int quoteAt) {
        int i = quoteAt + 1;
        while (i < source.length()) {
            if (source.charAt(i) == '"') {
                if (i + 1 < source.length() && source.charAt(i + 1) == '"') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return source.length();
    }

    /** @return the offset just past a character literal, or before the newline when it is unterminated. */
    private static int endOfCharacterLiteral(String source, int quoteAt) {
        int i = quoteAt + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '\'') {
                return i + 1;
            }
            if (c == '\n') {
                return i;
            }
            i++;
        }
        return source.length();
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
            } else if (c == '"' || c == '\'' || c == '@' || c == '$') {
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
