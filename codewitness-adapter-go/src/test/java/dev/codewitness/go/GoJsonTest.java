package dev.codewitness.go;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JSON reader exists only to read {@code go test -json}; these tests pin the shapes that stream
 * actually uses and the rule that malformed input is dropped rather than guessed at.
 */
class GoJsonTest {

    @Test
    @DisplayName("a flat event object is parsed with its string and number fields")
    void parsesAFlatObject() {
        Map<String, Object> event = GoJson.parseObject(
                "{\"Action\":\"fail\",\"Package\":\"codewitness.local/ledger\",\"Test\":\"TestX\",\"Elapsed\":0.02}");

        assertEquals("fail", event.get("Action"));
        assertEquals("codewitness.local/ledger", event.get("Package"));
        assertEquals("TestX", event.get("Test"));
        assertEquals(0.02, event.get("Elapsed"));
    }

    @Test
    @DisplayName("an output field keeps its escaped newlines and quotes")
    void unescapesStrings() {
        Map<String, Object> event = GoJson.parseObject(
                "{\"Output\":\"line one\\nline \\\"two\\\"\\n\"}");

        assertEquals("line one\nline \"two\"\n", event.get("Output"));
    }

    @Test
    @DisplayName("a line that is not a JSON object is dropped, not guessed at")
    void rejectsNonJson() {
        assertNull(GoJson.parseObject("go: downloading something"));
        assertNull(GoJson.parseObject("{not json}"));
        assertNull(GoJson.parseObject(""));
    }

    @Test
    @DisplayName("nested values and booleans are understood")
    void parsesNestedValues() {
        Map<String, Object> event = GoJson.parseObject(
                "{\"Nested\":{\"a\":1},\"List\":[1,2],\"Flag\":true,\"Nothing\":null}");

        assertTrue(event.get("Nested") instanceof Map);
        assertTrue(event.get("List") instanceof java.util.List);
        assertEquals(Boolean.TRUE, event.get("Flag"));
        assertNull(event.get("Nothing"));
    }
}
