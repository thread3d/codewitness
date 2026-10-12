package dev.codewitness.pascal.analysis;

import dev.codewitness.pascal.PascalSourceParser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A literal-aware view of one Pascal source file.
 *
 * <p>Analysis has to ignore what the compiler ignores: a rule that fires on a {@code goto} inside a
 * comment is noise, and noise is how a ruleset stops being read. The masked text blanks every comment
 * and string literal while preserving newlines, so a rule can match code and still report the line the
 * match was on. Rules that are <em>about</em> a literal - a hard-coded credential, say - read the raw
 * text through {@link #findRaw(Pattern)}, because that is the one place the literal is the evidence.
 */
final class PascalSourceScan {

    private final String raw;
    private final String masked;

    private PascalSourceScan(String raw, String masked) {
        this.raw = raw;
        this.masked = masked;
    }

    static PascalSourceScan of(String source) {
        String text = source == null ? "" : source;
        return new PascalSourceScan(text, PascalSourceParser.stripCommentsAndStrings(text));
    }

    String raw() {
        return raw;
    }

    String masked() {
        return masked;
    }

    /** @return matches of {@code pattern} in the code, with comments and literals blanked. */
    List<Match> find(Pattern pattern) {
        return matches(pattern, masked, false);
    }

    /** @return matches of {@code pattern} in the raw text, for rules about literals. */
    List<Match> findRaw(Pattern pattern) {
        return matches(pattern, raw, false);
    }

    /** @return matches of {@code pattern} whose first capture group is the interesting text. */
    List<Match> findWith(Pattern pattern) {
        return matches(pattern, masked, true);
    }

    /** @return the 1-based line number the offset falls on. */
    int lineAt(int offset) {
        int line = 1;
        int limit = Math.min(offset, masked.length());
        for (int i = 0; i < limit; i++) {
            if (masked.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static List<Match> matches(Pattern pattern, String text, boolean captured) {
        List<Match> results = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String group = captured && matcher.groupCount() >= 1 && matcher.group(1) != null
                    ? matcher.group(1)
                    : matcher.group();
            results.add(new Match(matcher.start(), lineOf(text, matcher.start()), matcher.group(), group));
        }
        return results;
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        int limit = Math.min(offset, text.length());
        for (int i = 0; i < limit; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * One match.
     *
     * @param start     offset in the scanned text
     * @param line      1-based line number
     * @param text      the whole matched text
     * @param captured  the first capture group, or the whole match when there is none
     */
    record Match(int start, int line, String text, String captured) {

        Match(int start, int line, String text) {
            this(start, line, text, text);
        }
    }
}
