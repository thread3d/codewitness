package dev.codewitness.go;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader for {@code go test -json}.
 *
 * <p>The adapter modules depend only on the core, and the core deliberately does not pull a JSON
 * library in, so the one JSON shape this adapter must read is parsed here: a stream of one flat JSON
 * object per line. Supporting the full specification would be a dependency in disguise; this reader
 * handles exactly the value types Go's test protocol emits and returns {@code null} rather than
 * guessing when it meets something it does not understand.
 *
 * <p>It is not a general purpose parser: it never throws, it returns {@code null} on malformed input,
 * and callers must treat {@code null} as "this line was not an event" rather than as an error.
 */
final class GoJson {

    private final String text;
    private int index;

    private GoJson(String text) {
        this.text = text;
    }

    /**
     * Parses a single JSON value.
     *
     * @return the parsed value (a {@link Map}, {@link List}, {@link String}, {@link Double},
     *     {@link Boolean} or {@code null}), or {@code null} when the text is not valid JSON
     */
    static Object parse(String text) {
        if (text == null) {
            return null;
        }
        GoJson reader = new GoJson(text);
        try {
            reader.skipWhitespace();
            Object value = reader.readValue();
            reader.skipWhitespace();
            return reader.index == reader.text.length() ? value : null;
        } catch (RuntimeException _) {
            return null;
        }
    }

    /** @return the object stored in {@code text}, or {@code null} when it is not a JSON object. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private Object readValue() {
        char c = peek();
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        expect('{');
        Map<String, Object> object = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            index++;
            return object;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            object.put(key, readValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                index++;
                continue;
            }
            expect('}');
            return object;
        }
    }

    private List<Object> readArray() {
        expect('[');
        List<Object> values = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            index++;
            return values;
        }
        while (true) {
            skipWhitespace();
            values.add(readValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                index++;
                continue;
            }
            expect(']');
            return values;
        }
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (index < text.length()) {
            char c = text.charAt(index++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (index >= text.length()) {
                throw new IllegalStateException("truncated escape");
            }
            char escape = text.charAt(index++);
            switch (escape) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (index + 4 > text.length()) {
                        throw new IllegalStateException("truncated unicode escape");
                    }
                    sb.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
                    index += 4;
                }
                default -> throw new IllegalStateException("unknown escape");
            }
        }
        throw new IllegalStateException("unterminated string");
    }

    private Object readLiteral(String literal, Object value) {
        if (!text.startsWith(literal, index)) {
            throw new IllegalStateException("expected " + literal);
        }
        index += literal.length();
        return value;
    }

    private Double readNumber() {
        int start = index;
        if (peek() == '-') {
            index++;
        }
        while (index < text.length()) {
            char c = text.charAt(index);
            if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                index++;
                continue;
            }
            break;
        }
        if (start == index) {
            throw new IllegalStateException("expected a value");
        }
        return Double.valueOf(text.substring(start, index));
    }

    private void skipWhitespace() {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
    }

    private char peek() {
        if (index >= text.length()) {
            throw new IllegalStateException("unexpected end of input");
        }
        return text.charAt(index);
    }

    private void expect(char expected) {
        if (peek() != expected) {
            throw new IllegalStateException("expected " + expected);
        }
        index++;
    }
}
