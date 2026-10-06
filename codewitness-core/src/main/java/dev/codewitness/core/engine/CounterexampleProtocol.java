package dev.codewitness.core.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The wire format between generated tests and the engine.
 *
 * <p>A generated test that finds a violation cannot call back into CodeWitness (it runs inside the
 * project's own build). Instead it fails with a single structured line that carries everything
 * needed to reconstruct the counterexample: the exact inputs, the expected and observed results,
 * the violated invariant, the seed and the case index. The engine parses that line, which is why a
 * counterexample always contains reproducible data rather than just a stack trace.
 *
 * <p>Format:
 * <pre>
 * CODEWITNESS_COUNTEREXAMPLE|case=37|seed=42|inputs={t=86400000, amount=1000.01}|expected=...|actual=...|invariant=...
 * </pre>
 */
public final class CounterexampleProtocol {

    public static final String MARKER = "CODEWITNESS_COUNTEREXAMPLE";

    private CounterexampleProtocol() {
    }

    /** Encodes fields as a protocol line. Values must not contain {@code |}. */
    public static String encode(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder(MARKER);
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue().replace("|", "/").replace("\n", " ");
            sb.append('|').append(entry.getKey()).append('=').append(value);
        }
        return sb.toString();
    }

    /** Encodes the standard counterexample payload. */
    public static String encodeCounterexample(
            Integer caseIndex, Long seed, Map<String, Object> inputs, String expected, String actual, String invariant) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (caseIndex != null) {
            fields.put("case", String.valueOf(caseIndex));
        }
        if (seed != null) {
            fields.put("seed", String.valueOf(seed));
        }
        fields.put("inputs", renderInputs(inputs));
        fields.put("expected", expected);
        fields.put("actual", actual);
        fields.put("invariant", invariant);
        return encode(fields);
    }

    /** Renders inputs as {@code {name=value, name=value}}, the inverse of {@link #parseInputs(String)}. */
    public static String renderInputs(Map<String, Object> inputs) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : inputs.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.append('}').toString();
    }

    /** Parses {@code {name=value, name=value}} back into a map. */
    public static Map<String, Object> parseInputs(String text) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        if (text == null) {
            return inputs;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("{")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("}")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isBlank()) {
            return inputs;
        }
        for (String pair : trimmed.split(",")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                inputs.put(pair.substring(0, equals).trim(), pair.substring(equals + 1).trim());
            }
        }
        return inputs;
    }

    /**
     * Extracts and decodes a protocol line from a test failure message.
     *
     * @param text failure message or stack trace
     * @return the decoded fields, or empty when the text carries no counterexample
     */
    public static Optional<Map<String, String>> decode(String text) {
        if (text == null) {
            return Optional.empty();
        }
        int index = text.indexOf(MARKER);
        if (index < 0) {
            return Optional.empty();
        }
        String line = text.substring(index);
        int newline = line.indexOf('\n');
        if (newline >= 0) {
            line = line.substring(0, newline);
        }
        Map<String, String> fields = new LinkedHashMap<>();
        String[] parts = line.split("\\|");
        for (int i = 1; i < parts.length; i++) {
            int equals = parts[i].indexOf('=');
            if (equals > 0) {
                fields.put(parts[i].substring(0, equals).trim(), parts[i].substring(equals + 1).trim());
            }
        }
        return Optional.of(fields);
    }

    /** @return the system property that overrides the random seed of generated tests. */
    public static String seedProperty() {
        return "codewitness.seed";
    }

    /** @return the system property that pins generated tests to a single case index. */
    public static String caseProperty() {
        return "codewitness.case";
    }
}
