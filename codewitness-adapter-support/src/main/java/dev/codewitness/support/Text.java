package dev.codewitness.support;

/**
 * Text helpers shared by more than one adapter.
 *
 * <p>These are the small, purely mechanical things every process-based adapter needs when it has to
 * explain a failure: the tail of a tool's output, and nothing else. They lived as identical copies in
 * five adapters, which is the kind of duplication that drifts quietly - one copy gets a fix and the
 * others keep the old behaviour in the one place a human actually reads when a build fails.
 */
public final class Text {

    private Text() {
    }

    /**
     * @param text  tool output, possibly {@code null} or blank
     * @param count how many trailing lines to keep
     * @return the last {@code count} lines joined by {@code " | "}, or {@code "(no output)"} when there
     *     is nothing to show
     *
     * <p>The separator and the placeholder are deliberate: the result is embedded in a note that ends
     * up in a report and in an LLM prompt, where a run of newlines would be flattened anyway and an
     * empty string would read as though the tool said nothing rather than that it said nothing useful.
     */
    public static String lastLines(String text, int count) {
        if (text == null || text.isBlank()) {
            return "(no output)";
        }
        String[] lines = text.strip().split("\\R");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, lines.length - count); i < lines.length; i++) {
            sb.append(lines[i]).append(" | ");
        }
        return sb.toString();
    }
}
