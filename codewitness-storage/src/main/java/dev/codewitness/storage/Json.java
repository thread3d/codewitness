package dev.codewitness.storage;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader and writer used to persist complex domain structures into SQLite text columns.
 *
 * <p>CodeWitness deliberately avoids a JSON dependency inside the storage module: the persisted
 * payloads are small, self-contained value trees (string lists, property maps, counterexample
 * inputs, coverage detail) and a short, fully auditable implementation is preferable to another
 * transitive dependency. Only this class knows the on-disk encoding; the rest of the package works
 * with core domain objects.
 *
 * <p>The writer accepts {@code null}, strings, characters, booleans, numbers, enums, {@link Path},
 * {@link Instant}, {@link Map} instances with string-convertible keys and any {@link Collection} or
 * array. Any other value is written as the string returned by {@link String#valueOf(Object)}, so a
 * surprising value type degrades the stored representation but never breaks persistence.
 *
 * <p>The reader produces {@link LinkedHashMap} for objects, {@link ArrayList} for arrays,
 * {@link String}, {@link Boolean}, {@link Long} for integral numbers, {@link Double} for fractional
 * numbers and {@code null}. Insertion order is preserved so that reports render in the order the
 * original objects exposed.
 *
 * <p>Instances are stateless; every method is safe to call from any thread.
 */
final class Json {

    private Json() {
    }

    /**
     * Serialises a value tree to compact JSON text.
     *
     * @param value value to serialise, may be {@code null}
     * @return JSON text, never {@code null}
     */
    static String write(Object value) {
        StringBuilder out = new StringBuilder(128);
        writeValue(out, value);
        return out.toString();
    }

    private static void writeValue(StringBuilder out, Object value) {
        // A pattern switch makes the dispatch table explicit; the unnamed patterns mark the types
        // that are simply stringified, without inventing binding names nobody reads.
        switch (value) {
            case null -> out.append("null");
            case String _, Character _, Path _, Instant _, Enum<?> _ ->
                    writeString(out, String.valueOf(value));
            case Boolean bool -> out.append(bool.booleanValue());
            case Number number -> writeNumber(out, number);
            case Map<?, ?> map -> writeObject(out, map);
            case Collection<?> collection -> writeArray(out, collection);
            case Object[] array -> writeArray(out, Arrays.asList(array));
            case int[] array -> writeArray(out, box(array));
            case long[] array -> writeArray(out, box(array));
            case double[] array -> writeArray(out, box(array));
            default -> writeString(out, String.valueOf(value));
        }
    }

    private static List<Object> box(int[] values) {
        List<Object> boxed = new ArrayList<>(values.length);
        for (int value : values) {
            boxed.add(value);
        }
        return boxed;
    }

    private static List<Object> box(long[] values) {
        List<Object> boxed = new ArrayList<>(values.length);
        for (long value : values) {
            boxed.add(value);
        }
        return boxed;
    }

    private static List<Object> box(double[] values) {
        List<Object> boxed = new ArrayList<>(values.length);
        for (double value : values) {
            boxed.add(value);
        }
        return boxed;
    }

    private static void writeNumber(StringBuilder out, Number number) {
        if (number instanceof Double || number instanceof Float) {
            double value = number.doubleValue();
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                // Not representable in JSON; keep the value readable instead of emitting invalid text.
                writeString(out, String.valueOf(value));
                return;
            }
            out.append(value);
            return;
        }
        out.append(number.toString());
    }

    private static void writeObject(StringBuilder out, Map<?, ?> map) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeString(out, String.valueOf(entry.getKey()));
            out.append(':');
            writeValue(out, entry.getValue());
        }
        out.append('}');
    }

    private static void writeArray(StringBuilder out, Collection<?> values) {
        out.append('[');
        boolean first = true;
        for (Object value : values) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeValue(out, value);
        }
        out.append(']');
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /**
     * Parses JSON text produced by {@link #write(Object)} or by any other JSON producer.
     *
     * @param text JSON text, may be {@code null}
     * @return the parsed value tree, or {@code null} when {@code text} is {@code null}
     * @throws IllegalArgumentException when the text is not valid JSON
     */
    static Object parse(String text) {
        if (text == null) {
            return null;
        }
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        if (parser.atEnd()) {
            throw new IllegalArgumentException("empty JSON document");
        }
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("unexpected trailing content at offset " + parser.position);
        }
        return value;
    }

    /** @return the value as a map, an empty map when {@code value} is {@code null}. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("expected a JSON object but found " + describe(value));
    }

    /** @return the value as a list, an empty list when {@code value} is {@code null}. */
    @SuppressWarnings("unchecked")
    static List<Object> array(Object value) {
        if (value == null) {
            return new ArrayList<>();
        }
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        throw new IllegalArgumentException("expected a JSON array but found " + describe(value));
    }

    /** @return the value as text, or {@code null}. */
    static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** @return the entry as text, or {@code null} when absent. */
    static String string(Map<String, Object> map, String key) {
        return string(map.get(key));
    }

    /** @return the entry as text, or {@code fallback} when absent. */
    static String string(Map<String, Object> map, String key, String fallback) {
        String value = string(map.get(key));
        return value == null ? fallback : value;
    }

    /** @return the entry as a boolean, or {@code fallback} when absent or not a boolean. */
    static boolean bool(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        return value instanceof Boolean bool ? bool : fallback;
    }

    /** @return the entry as an int, or {@code fallback} when absent or not a number. */
    static int integer(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    /** @return the entry as a long, or {@code fallback} when absent or not a number. */
    static long longValue(Map<String, Object> map, String key, long fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.longValue() : fallback;
    }

    /** @return the entry as a boxed long, or {@code null} when absent or not a number. */
    static Long longObject(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Number number ? number.longValue() : null;
    }

    /** @return the entry as a boxed int, or {@code null} when absent or not a number. */
    static Integer intObject(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : null;
    }

    /** @return the entry as a boxed double, or {@code null} when absent or not a number. */
    static Double doubleObject(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Number number ? number.doubleValue() : null;
    }

    /** @return the value as an ordered list of strings, never {@code null}. */
    static List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        for (Object element : array(value)) {
            if (element != null) {
                result.add(String.valueOf(element));
            }
        }
        return result;
    }

    /** @return the value as an ordered map of string to string, never {@code null}. */
    static Map<String, String> stringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : object(value).entrySet()) {
            if (entry.getValue() != null) {
                result.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        return result;
    }

    /** @return the value as an ordered map of string to number, never {@code null}. */
    static Map<String, Double> doubleMap(Object value) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : object(value).entrySet()) {
            if (entry.getValue() instanceof Number number) {
                result.put(entry.getKey(), number.doubleValue());
            }
        }
        return result;
    }

    private static String describe(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    /** Recursive descent parser; instances are confined to a single {@link #parse(String)} call. */
    private static final class Parser {

        private final String text;
        private int position;

        private Parser(String text) {
            this.text = text;
        }

        private boolean atEnd() {
            return position >= text.length();
        }

        private void skipWhitespace() {
            while (!atEnd()) {
                char c = text.charAt(position);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    position++;
                } else {
                    break;
                }
            }
        }

        private Object parseValue() {
            char c = peek();
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private char peek() {
            if (atEnd()) {
                throw new IllegalArgumentException("unexpected end of JSON document");
            }
            return text.charAt(position);
        }

        private Object parseLiteral(String literal, Object value) {
            if (!text.startsWith(literal, position)) {
                throw new IllegalArgumentException("invalid literal at offset " + position);
            }
            position += literal.length();
            return value;
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                position++;
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                result.put(key, parseValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    position++;
                } else if (c == '}') {
                    position++;
                    return result;
                } else {
                    throw new IllegalArgumentException("expected ',' or '}' at offset " + position);
                }
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                position++;
                return result;
            }
            while (true) {
                skipWhitespace();
                result.add(parseValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    position++;
                } else if (c == ']') {
                    position++;
                    return result;
                } else {
                    throw new IllegalArgumentException("expected ',' or ']' at offset " + position);
                }
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new IllegalArgumentException("unterminated string");
                }
                char c = text.charAt(position++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw new IllegalArgumentException("unterminated escape sequence");
                }
                char escaped = text.charAt(position++);
                switch (escaped) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (position + 4 > text.length()) {
                            throw new IllegalArgumentException("truncated unicode escape at offset " + position);
                        }
                        out.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                        position += 4;
                    }
                    default -> throw new IllegalArgumentException(
                            "invalid escape '\\" + escaped + "' at offset " + (position - 1));
                }
            }
        }

        private Object parseNumber() {
            int start = position;
            while (!atEnd()) {
                char c = text.charAt(position);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                    position++;
                } else {
                    break;
                }
            }
            String token = text.substring(start, position);
            if (token.isEmpty()) {
                throw new IllegalArgumentException("invalid JSON value at offset " + start);
            }
            if (token.indexOf('.') >= 0 || token.indexOf('e') >= 0 || token.indexOf('E') >= 0) {
                try {
                    return Double.valueOf(token);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("invalid number '" + token + "' at offset " + start, e);
                }
            }
            try {
                return Long.valueOf(token);
            } catch (NumberFormatException e) {
                try {
                    return Double.valueOf(token);
                } catch (NumberFormatException _) {
                    throw new IllegalArgumentException("invalid number '" + token + "' at offset " + start, e);
                }
            }
        }

        private void expect(char expected) {
            if (atEnd() || text.charAt(position) != expected) {
                throw new IllegalArgumentException(
                        "expected '" + expected + "' at offset " + position);
            }
            position++;
        }
    }
}
