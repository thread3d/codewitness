package dev.codewitness.vhdl.analysis;

import dev.codewitness.vhdl.VhdlSourceParser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A source file prepared for pattern matching: a view of the code with comments and string literals
 * blanked out, so a rule sees the language and not prose that happens to look like it.
 *
 * <p>VHDL has a line comment ({@code --}), a block comment ({@code /* *}{@code /}, VHDL-2008) and one
 * string form ({@code "..."}, in which a doubled quote is an escaped quote). The masking itself lives
 * with the entity parser so that both read the same view of a file. The masked view replaces every
 * comment and string character with a space, which keeps byte offsets and line numbers identical to
 * the original and lets a caller report a finding at the offset it matched.
 */
final class VhdlSourceScan {

    private final String raw;
    private final String masked;

    private VhdlSourceScan(String raw, String masked) {
        this.raw = raw;
        this.masked = masked;
    }

    static VhdlSourceScan of(String source) {
        String text = source == null ? "" : source;
        return new VhdlSourceScan(text, VhdlSourceParser.maskCommentsAndStrings(text));
    }

    String raw() {
        return raw;
    }

    /** @return the source with every comment and string literal replaced by spaces. */
    String masked() {
        return masked;
    }

    /** @return every match of {@code pattern} in the masked view, in source order. */
    List<Match> find(Pattern pattern) {
        List<Match> matches = new ArrayList<>();
        Matcher matcher = pattern.matcher(masked);
        while (matcher.find()) {
            matches.add(new Match(matcher.start(), lineAt(matcher.start()), matcher.group()));
        }
        return matches;
    }

    /** @return every match of {@code pattern} over the masked view, with the first capture group kept. */
    List<Match> findWith(Pattern pattern) {
        List<Match> matches = new ArrayList<>();
        Matcher matcher = pattern.matcher(masked);
        while (matcher.find()) {
            matches.add(new Match(matcher.start(), lineAt(matcher.start()), matcher.group(),
                    matcher.groupCount() >= 1 ? matcher.group(1) : null));
        }
        return matches;
    }

    /** @return the 1-based line number of a byte offset. */
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

    /**
     * A match in the masked view.
     *
     * @param start    the byte offset of the match
     * @param line     the 1-based line the match starts on
     * @param text     the matched text, from the masked view
     * @param captured the first capture group, or {@code null} when the pattern has none. The group is
     *     captured at match time rather than read from a live matcher later, because a matcher that has
     *     finished iterating no longer holds a match to read a group from.
     */
    record Match(int start, int line, String text, String captured) {

        Match(int start, int line, String text) {
            this(start, line, text, null);
        }
    }
}
