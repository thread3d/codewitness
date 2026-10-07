package dev.codewitness.kotlin.analysis;

/**
 * Reads Kotlin source the way the language defines it, so that no rule and no mutant is decided by text
 * that only looks like code.
 *
 * <p>Kotlin's literals are not the C family's, and a scanner that assumes they are gets the interesting
 * cases wrong in both directions:
 *
 * <ul>
 *   <li>a raw string ({@code """..."""}) spans any number of lines and takes no escapes, so an operator
 *       inside one is text, not a mutation site;</li>
 *   <li>a string template's {@code ${...}} hole contains real code, including nested strings and braces,
 *       so the hole is consumed as part of the literal rather than left to be scanned as Kotlin - the
 *       conservative direction: missing a site costs one mutant, while mutating inside a template would
 *       change the string;</li>
 *   <li>a backtick identifier ({@code `my name`}) is a name, so an operator-looking character inside it is
 *       not an operator; and</li>
 *   <li>Kotlin's block comments nest, so the first {@code *}{@code /} does not necessarily end one.</li>
 * </ul>
 *
 * <p>Every projection this class produces preserves the source's length and its newlines, so a match found
 * in one of them maps back to the same line and column in the original file. That is what lets a rule
 * report a location a reader can open.
 */
public final class KotlinSourceScan {

    private KotlinSourceScan() {
    }

    /** @return {@code true} when the character opens a Kotlin literal, template or backtick identifier. */
    public static boolean isLiteralOpener(char c) {
        return c == '"' || c == '\'' || c == '`';
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
        if (c == '"') {
            // A raw string is three quotes; anything else is an ordinary string with escapes and holes.
            if (start + 2 < source.length() && source.charAt(start + 1) == '"' && source.charAt(start + 2) == '"') {
                return rawStringEnd(source, start + 3);
            }
            return stringEnd(source, start + 1);
        }
        if (c == '\'') {
            return charEnd(source, start + 1);
        }
        if (c == '`') {
            return backtickEnd(source, start + 1);
        }
        return -1;
    }

    private static int rawStringEnd(String source, int start) {
        int i = start;
        while (i < source.length()) {
            if (source.charAt(i) == '"' && i + 2 < source.length()
                    && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                return i + 3;
            }
            i++;
        }
        // An unterminated raw string runs to the end of the file, which is what the compiler says too.
        return source.length();
    }

    private static int stringEnd(String source, int start) {
        int i = start;
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
                // A single-quoted string cannot span a line; the compiler stops it there, and so does this,
                // so that a file full of them cannot turn into one literal that swallows the rest.
                return i;
            }
            if (c == '$' && i + 1 < source.length() && source.charAt(i + 1) == '{') {
                i = templateHoleEnd(source, i + 2);
                continue;
            }
            i++;
        }
        return source.length();
    }

    /** @return the offset just past the {@code }} that closes a {@code ${...}} hole opened before it. */
    private static int templateHoleEnd(String source, int start) {
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
            if (c == '"' || c == '\'' || c == '`') {
                int end = literalEnd(source, i);
                if (end > i) {
                    i = end;
                    continue;
                }
            }
            i++;
        }
        return source.length();
    }

    private static int charEnd(String source, int start) {
        int i = start;
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

    private static int backtickEnd(String source, int start) {
        int i = start;
        while (i < source.length()) {
            if (source.charAt(i) == '`') {
                return i + 1;
            }
            if (source.charAt(i) == '\n') {
                // An unclosed backtick name is not valid Kotlin; stopping at the line end keeps the rest of
                // the file readable rather than swallowing it.
                return i;
            }
            i++;
        }
        return source.length();
    }

    /**
     * @param source the source text
     * @return the source with comments replaced by spaces and everything else - string contents included -
     *     left alone. Rules that read literal text use this projection, so that a {@code //} inside a URL is
     *     not mistaken for a comment and a commented-out assignment is not mistaken for code.
     */
    public static String withoutComments(String source) {
        return project(source, false);
    }

    /**
     * @param source the source text
     * @return the source with comments and literals replaced by spaces, so that only code remains in a
     *     string of the same length. Rules that look for statement shapes use this projection.
     */
    public static String codeOnly(String source) {
        return project(source, true);
    }

    private static String project(String source, boolean blankLiterals) {
        if (source == null || source.isEmpty()) {
            return source == null ? "" : source;
        }
        char[] out = source.toCharArray();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                i = blank(out, source, i, lineEnd(source, i));
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                i = blank(out, source, i, blockCommentEnd(source, i));
                continue;
            }
            if (isLiteralOpener(c)) {
                int end = literalEnd(source, i);
                if (end > i) {
                    i = blankLiterals ? blank(out, source, i, end) : end;
                    continue;
                }
            }
            i++;
        }
        return new String(out);
    }

    /** @return the offset just past a nested block comment that opens at {@code start}. */
    private static int blockCommentEnd(String source, int start) {
        int i = start + 2;
        int depth = 1;
        while (i < source.length()) {
            if (source.charAt(i) == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                depth++;
                i += 2;
                continue;
            }
            if (source.charAt(i) == '*' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                depth--;
                i += 2;
                if (depth == 0) {
                    return i;
                }
                continue;
            }
            i++;
        }
        return source.length();
    }

    private static int lineEnd(String source, int start) {
        int i = start;
        while (i < source.length() && source.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    /** Replaces {@code [start, end)} with spaces, keeping every newline so line numbers survive. */
    private static int blank(char[] out, String source, int start, int end) {
        for (int i = start; i < end && i < source.length(); i++) {
            if (source.charAt(i) != '\n') {
                out[i] = ' ';
            }
        }
        return end;
    }
}
