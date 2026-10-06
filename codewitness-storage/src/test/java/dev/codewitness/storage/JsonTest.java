package dev.codewitness.storage;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the storage module's dependency-free JSON helper. */
class JsonTest {

    @Test
    void roundTripsNestedStructures() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("text", "hello");
        value.put("number", 42);
        value.put("decimal", 1.5);
        value.put("flag", true);
        value.put("nothing", null);
        value.put("list", List.of("a", "b"));
        value.put("nested", Map.of("inner", 7));

        Object parsed = Json.parse(Json.write(value));

        assertEquals("hello", Json.string(Json.object(parsed), "text"));
        assertEquals(42L, Json.longValue(Json.object(parsed), "number", -1L));
        assertEquals(1.5, Json.doubleObject(Json.object(parsed), "decimal"));
        assertTrue(Json.bool(Json.object(parsed), "flag", false));
        assertNull(Json.object(parsed).get("nothing"));
        assertEquals(List.of("a", "b"), Json.strings(Json.object(parsed).get("list")));
        assertEquals(7L, Json.longValue(Json.object(Json.object(parsed).get("nested")), "inner", -1L));
    }

    @Test
    void writesPathsInstantsAndEnumsAsText() {
        String json = Json.write(List.of(Path.of("/tmp/report.json"), Instant.parse("2026-10-06T10:15:30Z")));
        assertEquals("[\"/tmp/report.json\",\"2026-10-06T10:15:30Z\"]", json);
    }

    @Test
    void escapesControlCharactersAndQuotes() {
        String json = Json.write("line\n\"quoted\"\ttab\\slash");
        assertEquals("\"line\\n\\\"quoted\\\"\\ttab\\\\slash\"", json);
        assertEquals("line\n\"quoted\"\ttab\\slash", Json.parse(json));
    }

    @Test
    void emptyValuesRoundTrip() {
        assertEquals("[]", Json.write(List.of()));
        assertEquals("{}", Json.write(Map.of()));
        assertTrue(Json.array(Json.parse(Json.write(List.of()))).isEmpty());
        assertTrue(Json.object(Json.parse(Json.write(Map.of()))).isEmpty());
        assertTrue(Json.strings(null).isEmpty());
        assertTrue(Json.stringMap(null).isEmpty());
        assertNull(Json.parse(null));
    }

    @Test
    void rejectsMalformedDocuments() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse(""));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{]"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1} trailing"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("\"unterminated"));
    }
}
