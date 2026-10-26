package dev.codewitness.report;

/**
 * Makes rendered report text encodable as UTF-8.
 *
 * <p>Report content comes from the project under verification, and a project can put an unpaired surrogate into
 * it: a JSON test report carrying {@code "\ud800"} decodes to a lone surrogate in a string, travels through the
 * adapters untouched, and reaches the writer. {@code Files.writeString} then throws
 * {@code UnmappableCharacterException} before anything is written, so the run loses both report files - and,
 * because the caller writes the files and the SQLite record in one block, the durable record too. Everything the
 * run established becomes unavailable because one string was not encodable.
 *
 * <p>The replacement is U+FFFD, the character Unicode defines for exactly this: "there was data here that could
 * not be represented". It is visible in the report, which is the honest outcome, and it keeps the rest of the
 * document intact. A paired surrogate - an ordinary character outside the basic plane, such as an emoji in a
 * test name - is left alone.
 *
 * <p>Control characters are deliberately <em>not</em> stripped here. They are not an encoding problem, they are a
 * rendering one, and a report that silently rewrites a byte the tool actually observed is a worse failure than a
 * table cell with an odd character in it.
 */
final class ReportText {

    private static final char REPLACEMENT = '\uFFFD';

    private ReportText() {
    }

    /**
     * @param text rendered report text, may be {@code null}
     * @return the same text with any unpaired surrogate replaced by U+FFFD
     */
    static String encodable(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                boolean paired = i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1));
                if (!paired) {
                    if (out == null) {
                        out = new StringBuilder(text.length());
                        out.append(text, 0, i);
                    }
                    out.append(REPLACEMENT);
                    continue;
                }
            } else if (Character.isLowSurrogate(c)) {
                // Reaching a low surrogate here means its high half was missing or already consumed as unpaired.
                boolean precededByHigh = i > 0 && Character.isHighSurrogate(text.charAt(i - 1));
                if (!precededByHigh) {
                    if (out == null) {
                        out = new StringBuilder(text.length());
                        out.append(text, 0, i);
                    }
                    out.append(REPLACEMENT);
                    continue;
                }
            }
            if (out != null) {
                out.append(c);
            }
        }
        return out == null ? text : out.toString();
    }
}
