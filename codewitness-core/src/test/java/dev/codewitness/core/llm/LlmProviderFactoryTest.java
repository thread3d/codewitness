package dev.codewitness.core.llm;

import dev.codewitness.core.config.VerificationConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The provider surface is deliberately tiny. A verification tool that can be pointed at an arbitrary
 * endpoint from a config file is a liability, and an unknown or disabled provider must degrade to a
 * provider that is never available rather than one that guesses.
 */
class LlmProviderFactoryTest {

    private static VerificationConfig.Llm llm(boolean enabled, String provider) {
        // A deliberately unlikely variable name keeps the "no API key" path deterministic.
        return new VerificationConfig.Llm(enabled, provider, "some-model", null,
                "CODEWITNESS_TEST_KEY_MUST_NOT_BE_SET_12345", 10, 0.2, Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("a null configuration yields a disabled provider")
    void nullConfigYieldsDisabledProvider() {
        LlmProvider provider = LlmProviderFactory.create(null);

        assertEquals("none", provider.id());
        assertFalse(provider.available());
    }

    @Test
    @DisplayName("LLM use switched off yields a disabled provider")
    void disabledConfigYieldsDisabledProvider() {
        LlmProvider provider = LlmProviderFactory.create(llm(false, "openai"));

        assertEquals("none", provider.id());
        assertFalse(provider.available());
        assertTrue(provider.model().contains("disabled"), provider.model());
    }

    @Test
    @DisplayName("provider 'none' yields a disabled provider even when enabled")
    void providerNoneYieldsDisabledProvider() {
        LlmProvider provider = LlmProviderFactory.create(llm(true, "none"));

        assertEquals("none", provider.id());
        assertFalse(provider.available());
    }

    @Test
    @DisplayName("an unknown provider yields a disabled provider naming the unknown provider")
    void unknownProviderYieldsDisabledProvider() {
        LlmProvider provider = LlmProviderFactory.create(llm(true, "definitely-not-a-provider"));

        assertEquals("none", provider.id());
        assertFalse(provider.available());
        assertTrue(provider.model().contains("unknown provider"), provider.model());
    }

    @Test
    @DisplayName("a configured openai provider is created (it does not touch the network here)")
    void configuredOpenAiProviderIsCreated() {
        LlmProvider provider = LlmProviderFactory.create(llm(true, "openai"));

        assertEquals("openai-compatible", provider.id());
        assertEquals("some-model", provider.model());
    }

    @Test
    @DisplayName("provider names are case-insensitive and aliases are accepted")
    void providerAliasesAreAccepted() {
        assertEquals("openai-compatible", LlmProviderFactory.create(llm(true, "OpenAI")).id());
        assertEquals("openai-compatible", LlmProviderFactory.create(llm(true, "openai-compatible")).id());
        assertEquals("openai-compatible", LlmProviderFactory.create(llm(true, "http")).id());
        assertEquals("openai-compatible", LlmProviderFactory.create(llm(true, "auto")).id());
        assertEquals("none", LlmProviderFactory.create(llm(true, "disabled")).id());
    }

    @Test
    @DisplayName("without an API key the provider reports unavailable instead of failing")
    void providerWithoutApiKeyIsUnavailable() {
        // The configured environment variable does not exist in the test environment, so this is
        // deterministic and never performs network I/O.
        LlmProvider provider = LlmProviderFactory.create(llm(true, "openai"));

        assertFalse(provider.available());
        LlmResponse response = provider.complete(LlmRequest.of(PromptBundle.builder("instructions").build()));
        assertFalse(response.available());
        assertTrue(response.error().contains("no API key"), response.error());
        assertEquals("", response.text());
    }

    @Test
    @DisplayName("a disabled provider answers every request with an unavailable response")
    void disabledProviderNeverThrows() {
        LlmProvider provider = LlmProvider.disabled("not configured");

        assertFalse(provider.available());
        LlmResponse response = provider.complete(LlmRequest.of(PromptBundle.builder("instructions").build()));
        assertFalse(response.available());
        assertEquals("not configured", response.error());
        assertEquals(Duration.ZERO, response.duration());
    }

    @Test
    @DisplayName("LlmRequest.of applies safe defaults")
    void llmRequestDefaults() {
        LlmRequest request = LlmRequest.of(PromptBundle.builder("instructions").build());

        assertEquals(0.2, request.temperature(), 1e-9);
        assertEquals(2048, request.maxTokens());
        assertEquals(Duration.ofSeconds(60), request.timeout());
        org.junit.jupiter.api.Assertions.assertNull(request.model());
    }

    @Test
    @DisplayName("the minimal JSON writer escapes strings and nests structures")
    void jsonWriterEscapesAndNests() {
        String json = LlmProviderFactory.Json.minimal(java.util.Map.of(
                "model", "gpt-4o",
                "messages", java.util.List.of(java.util.Map.of("role", "user", "content", "line1\nline2 \"quoted\""))));

        assertTrue(json.contains("\"model\":\"gpt-4o\""), json);
        assertTrue(json.contains("\\n"), json);
        assertTrue(json.contains("\\\"quoted\\\""), json);
        assertTrue(json.startsWith("{") && json.endsWith("}"), json);
    }

    @Test
    @DisplayName("the JSON reader extracts and unescapes the first named string field")
    void jsonReaderExtractsField() {
        String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hello\\nworld\"}}]}";

        String content = LlmProviderFactory.Json.extractFirstString(json, "content");

        assertEquals("hello\nworld", content);
    }

    @Test
    @DisplayName("the JSON reader returns null when the field is absent")
    void jsonReaderReturnsNullWhenAbsent() {
        assertEquals(null, LlmProviderFactory.Json.extractFirstString("{\"a\":1}", "content"));
        assertEquals(null, LlmProviderFactory.Json.extractFirstString(null, "content"));
    }
}
