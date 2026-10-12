package dev.codewitness.sql.analysis;

/**
 * A literal-aware view of one SQL file.
 *
 * <p>A rule table that reads raw SQL text finds keywords inside comments and string literals, which is
 * how a rule reports a {@code DROP TABLE} that only appears in a comment explaining why there is none.
 * The scan keeps a copy with comments and quoted regions blanked out - the same length, so a match
 * position still maps to a line in the original - and every rule reads that copy. What the database
 * will actually execute is what the rules see.
 */
public record SqlSourceScan(String original, String masked) {

    /** @return the original text together with a masked copy of it. */
    public static SqlSourceScan of(String source) {
        String text = source == null ? "" : source;
        StringBuilder masked = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '-' && i + 1 < text.length() && text.charAt(i + 1) == '-') {
                i = blankTo(text, masked, i, '\n', false);
                continue;
            }
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                i = blankBlockComment(text, masked, i);
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                i = blankQuoted(text, masked, i, c);
                continue;
            }
            if (c == '[') {
                i = blankQuoted(text, masked, i, ']');
                continue;
            }
            masked.append(c);
            i++;
        }
        return new SqlSourceScan(text, masked.toString());
    }

    /** @return the 1-based line a character index falls on. */
    public int lineAt(int index) {
        int line = 1;
        int limit = Math.min(index, masked.length());
        for (int i = 0; i < limit; i++) {
            if (masked.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** @return the masked text from {@code from} to the end of that statement, for statement-scoped rules. */
    public String statementFrom(int from) {
        int end = masked.indexOf(';', from);
        return masked.substring(from, end < 0 ? masked.length() : end);
    }

    /** Blanks a line comment, keeping the newline so line numbers do not shift. */
    private static int blankTo(String text, StringBuilder masked, int start, char terminator, boolean include) {
        int i = start;
        while (i < text.length() && text.charAt(i) != terminator) {
            masked.append(' ');
            i++;
        }
        if (i < text.length() && include) {
            masked.append(' ');
            i++;
        }
        return i;
    }

    /** Blanks a block comment; the delimiters become spaces too. */
    private static int blankBlockComment(String text, StringBuilder masked, int start) {
        int i = start;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '*' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                masked.append("  ");
                return i + 2;
            }
            masked.append(c == '\n' ? '\n' : ' ');
            i++;
        }
        return i;
    }

    /** Blanks a quoted region, keeping newlines so line numbers do not shift. */
    private static int blankQuoted(String text, StringBuilder masked, int start, char closing) {
        masked.append(' ');
        int i = start + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == closing) {
                if (i + 1 < text.length() && text.charAt(i + 1) == closing) {
                    masked.append("  ");
                    i += 2;
                    continue;
                }
                masked.append(' ');
                return i + 1;
            }
            masked.append(c == '\n' ? '\n' : ' ');
            i++;
        }
        return i;
    }
}
