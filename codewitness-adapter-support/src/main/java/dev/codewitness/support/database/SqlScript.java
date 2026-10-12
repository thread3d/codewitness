package dev.codewitness.support.database;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a {@code .sql} script into the statements to execute one at a time.
 *
 * <p>JDBC gives no way to run a script: the SQLite driver executes only the first statement of a
 * multi-statement string and reports success for the whole thing, so a schema loaded in one call would
 * silently create its first table and nothing else. Every statement therefore has to be separated here.
 *
 * <p>Separating on {@code ;} alone is not enough, which is what most of this class is about:
 *
 * <ul>
 *   <li>a semicolon inside a string literal ({@code 'a;b'}) or a quoted identifier is not a separator;</li>
 *   <li>a semicolon inside a comment is not a separator, and the comment itself must not become part of
 *       the statement;</li>
 *   <li>a trigger body is one statement that contains semicolons - {@code CREATE TRIGGER ... BEGIN ...
 *       END;} - and splitting it would produce fragments the database rejects. A {@code CASE} expression
 *       inside that body has its own {@code END}, so the nesting is counted rather than assumed to be one
 *       level.</li>
 * </ul>
 *
 * <p>The one shape it deliberately does not attempt is a procedural body written for a database that has
 * stored procedures; SQLite has none, and a fixture that guessed would be guessing about the wrong
 * dialect. It is a documented limit rather than a silent misread.
 */
public final class SqlScript {

    private SqlScript() {
    }

    /**
     * @param script the text of a {@code .sql} file
     * @return the statements to execute, in order, with comments removed and empty statements dropped
     */
    public static List<String> statements(String script) {
        List<String> statements = new ArrayList<>();
        if (script == null || script.isBlank()) {
            return statements;
        }
        StringBuilder current = new StringBuilder();
        int blockDepth = 0;
        int i = 0;
        while (i < script.length()) {
            char c = script.charAt(i);

            if (c == '-' && i + 1 < script.length() && script.charAt(i + 1) == '-') {
                i = skipLineComment(script, i);
                current.append(' ');
                continue;
            }
            if (c == '/' && i + 1 < script.length() && script.charAt(i + 1) == '*') {
                i = skipBlockComment(script, i);
                current.append(' ');
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                i = copyQuoted(script, i, c, current);
                continue;
            }
            if (c == '[') {
                i = copyBracketed(script, i, current);
                continue;
            }
            if (Character.isLetter(c)) {
                int end = i;
                while (end < script.length() && (Character.isLetterOrDigit(script.charAt(end)) || script.charAt(end) == '_')) {
                    end++;
                }
                String word = script.substring(i, end);
                if (word.equalsIgnoreCase("BEGIN") && current.toString().trim().length() > 0) {
                    // A trigger body: the statement already has text, so this BEGIN opens a block. A bare
                    // BEGIN; (a transaction) has no preceding text and is left to terminate at its semicolon.
                    blockDepth++;
                } else if (word.equalsIgnoreCase("CASE")) {
                    blockDepth++;
                } else if (word.equalsIgnoreCase("END") && blockDepth > 0) {
                    blockDepth--;
                }
                current.append(word);
                i = end;
                continue;
            }
            if (c == ';' && blockDepth == 0) {
                String statement = current.toString().trim();
                if (!statement.isEmpty()) {
                    statements.add(statement);
                }
                current.setLength(0);
                i++;
                continue;
            }
            current.append(c);
            i++;
        }
        String trailing = current.toString().trim();
        if (!trailing.isEmpty()) {
            statements.add(trailing);
        }
        return statements;
    }

    /** @return the index just past the newline that ends the {@code --} comment starting at {@code start}. */
    private static int skipLineComment(String script, int start) {
        int i = start + 2;
        while (i < script.length() && script.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    /** @return the index just past the closing delimiter of the block comment starting at {@code start}. */
    private static int skipBlockComment(String script, int start) {
        int i = start + 2;
        while (i + 1 < script.length()) {
            if (script.charAt(i) == '*' && script.charAt(i + 1) == '/') {
                return i + 2;
            }
            i++;
        }
        return script.length();
    }

    /**
     * Copies a quoted region verbatim, including its delimiters, so a separator inside it stays inside it.
     * A doubled delimiter ({@code ''} or {@code ""}) is an escape and does not close the region.
     */
    private static int copyQuoted(String script, int start, char quote, StringBuilder out) {
        out.append(quote);
        int i = start + 1;
        while (i < script.length()) {
            char c = script.charAt(i);
            out.append(c);
            if (c == quote) {
                if (i + 1 < script.length() && script.charAt(i + 1) == quote) {
                    out.append(quote);
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return script.length();
    }

    /** Copies a {@code [...]} identifier verbatim; brackets do not nest in SQLite. */
    private static int copyBracketed(String script, int start, StringBuilder out) {
        out.append('[');
        int i = start + 1;
        while (i < script.length()) {
            char c = script.charAt(i);
            out.append(c);
            if (c == ']') {
                return i + 1;
            }
            i++;
        }
        return script.length();
    }
}
