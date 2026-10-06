package dev.codewitness.core.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The protocol line is the only channel from a generated test running inside the project's build back
 * to the engine, so it must carry the exact inputs and survive surrounding stack-trace noise.
 */
class CounterexampleProtocolTest {

    @Test
    @DisplayName("encode/decode round-trips simple fields")
    void encodeDecodeRoundTrip() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("case", "37");
        fields.put("seed", "42");
        fields.put("expected", "balance stays positive");
        fields.put("actual", "balance is -1");

        Optional<Map<String, String>> decoded = CounterexampleProtocol.decode(CounterexampleProtocol.encode(fields));

        assertTrue(decoded.isPresent());
        assertEquals(fields, decoded.orElseThrow());
    }

    @Test
    @DisplayName("values may contain '=' without breaking the format")
    void valuesMayContainEqualsSigns() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("inputs", "{expression=amount=1000 + tax}");
        fields.put("expected", "<=1000");

        String encoded = CounterexampleProtocol.encode(fields);
        Map<String, String> decoded = CounterexampleProtocol.decode(encoded).orElseThrow();

        assertEquals("{expression=amount=1000 + tax}", decoded.get("inputs"));
        assertEquals("<=1000", decoded.get("expected"));
    }

    @Test
    @DisplayName("values may contain spaces")
    void valuesMayContainSpaces() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("invariant", "the balance must never be negative");
        fields.put("actual", "withdrawal of 1000 accepted");

        Map<String, String> decoded = CounterexampleProtocol.decode(CounterexampleProtocol.encode(fields)).orElseThrow();

        assertEquals("the balance must never be negative", decoded.get("invariant"));
        assertEquals("withdrawal of 1000 accepted", decoded.get("actual"));
    }

    @Test
    @DisplayName("a '|' inside a value is replaced so it cannot forge a field")
    void pipesInValuesAreNeutralised() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("actual", "a|b|c");

        Map<String, String> decoded = CounterexampleProtocol.decode(CounterexampleProtocol.encode(fields)).orElseThrow();

        assertEquals(1, decoded.size(), decoded.toString());
        assertEquals("a/b/c", decoded.get("actual"));
    }

    @Test
    @DisplayName("newlines inside a value do not terminate the protocol line")
    void newlinesInValuesAreNeutralised() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("actual", "first\nsecond");

        Map<String, String> decoded = CounterexampleProtocol.decode(CounterexampleProtocol.encode(fields)).orElseThrow();

        assertEquals("first second", decoded.get("actual"));
    }

    @Test
    @DisplayName("a message without the marker decodes to empty")
    void messageWithoutMarkerDecodesToEmpty() {
        assertTrue(CounterexampleProtocol.decode("java.lang.AssertionError: expected true but was false").isEmpty());
        assertTrue(CounterexampleProtocol.decode("").isEmpty());
        assertTrue(CounterexampleProtocol.decode(null).isEmpty());
    }

    @Test
    @DisplayName("decode ignores stack-trace lines before and after the protocol line")
    void decodeIgnoresSurroundingStackTrace() {
        String message = "java.lang.AssertionError: failure\n"
                + "\tat com.example.LedgerTest.withdraw(LedgerTest.java:42)\n"
                + CounterexampleProtocol.encode(Map.of("case", "3", "actual", "boom"))
                + "\n\tat org.junit.platform.engine.run(NativeMethodAccessorImpl.java:62)";

        Map<String, String> decoded = CounterexampleProtocol.decode(message).orElseThrow();

        assertEquals("3", decoded.get("case"));
        assertEquals("boom", decoded.get("actual"));
    }

    @Test
    @DisplayName("encodeCounterexample carries case, seed and rendered inputs")
    void encodeCounterexampleCarriesStandardPayload() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("t", 86400000L);
        inputs.put("amount", "1000.01");

        String encoded = CounterexampleProtocol.encodeCounterexample(37, 42L, inputs,
                "at most 1000", "1000.01", "balance >= 0");
        Map<String, String> decoded = CounterexampleProtocol.decode(encoded).orElseThrow();

        assertEquals("37", decoded.get("case"));
        assertEquals("42", decoded.get("seed"));
        assertEquals("at most 1000", decoded.get("expected"));
        assertEquals("1000.01", decoded.get("actual"));
        assertEquals("balance >= 0", decoded.get("invariant"));

        Map<String, Object> parsed = CounterexampleProtocol.parseInputs(decoded.get("inputs"));
        assertEquals("86400000", parsed.get("t"));
        assertEquals("1000.01", parsed.get("amount"));
    }

    @Test
    @DisplayName("encodeCounterexample omits an absent case or seed")
    void encodeCounterexampleOmitsNullCaseAndSeed() {
        String encoded = CounterexampleProtocol.encodeCounterexample(null, null, Map.of("a", "1"),
                "e", "a", "inv");
        Map<String, String> decoded = CounterexampleProtocol.decode(encoded).orElseThrow();

        assertFalse(decoded.containsKey("case"));
        assertFalse(decoded.containsKey("seed"));
        assertEquals("{a=1}", decoded.get("inputs"));
    }

    @Test
    @DisplayName("renderInputs and parseInputs round-trip input maps")
    void renderInputsAndParseInputsRoundTrip() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("amount", "1000.01");
        inputs.put("note", "hello world");
        inputs.put("expression", "amount = limit + 1");

        String rendered = CounterexampleProtocol.renderInputs(inputs);
        assertEquals("{amount=1000.01, note=hello world, expression=amount = limit + 1}", rendered);

        Map<String, Object> parsed = CounterexampleProtocol.parseInputs(rendered);
        assertEquals("1000.01", parsed.get("amount"));
        assertEquals("hello world", parsed.get("note"));
        assertEquals("amount = limit + 1", parsed.get("expression"));
    }

    @Test
    @DisplayName("parseInputs tolerates missing braces, blanks and null")
    void parseInputsToleratesEdgeCases() {
        assertTrue(CounterexampleProtocol.parseInputs(null).isEmpty());
        assertTrue(CounterexampleProtocol.parseInputs("").isEmpty());
        assertTrue(CounterexampleProtocol.parseInputs("{}").isEmpty());
        assertTrue(CounterexampleProtocol.parseInputs("   ").isEmpty());
        assertEquals(Map.of("a", "1"), CounterexampleProtocol.parseInputs("a=1"));
        assertEquals(Map.of("a", "1"), CounterexampleProtocol.parseInputs("{ a = 1 }"));
    }

    @Test
    @DisplayName("renderInputs of an empty map is '{}'")
    void renderInputsOfEmptyMap() {
        assertEquals("{}", CounterexampleProtocol.renderInputs(Map.of()));
    }

    @Test
    @DisplayName("the marker and override properties are stable names")
    void protocolNamesAreStable() {
        assertEquals("CODEWITNESS_COUNTEREXAMPLE", CounterexampleProtocol.MARKER);
        assertEquals("codewitness.seed", CounterexampleProtocol.seedProperty());
        assertEquals("codewitness.case", CounterexampleProtocol.caseProperty());
    }
}
