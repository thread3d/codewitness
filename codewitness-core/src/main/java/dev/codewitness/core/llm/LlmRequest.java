package dev.codewitness.core.llm;

import java.time.Duration;

/**
 * A completion request.
 *
 * @param prompt      assembled prompt
 * @param model       requested model identifier, or {@code null} for the provider default
 * @param temperature sampling temperature
 * @param maxTokens   maximum response length
 * @param timeout     request timeout
 */
public record LlmRequest(PromptBundle prompt, String model, double temperature, int maxTokens, Duration timeout) {

    public LlmRequest {
        if (timeout == null) {
            timeout = Duration.ofSeconds(60);
        }
        if (maxTokens <= 0) {
            maxTokens = 2048;
        }
    }

    public static LlmRequest of(PromptBundle prompt) {
        return new LlmRequest(prompt, null, 0.2, 2048, Duration.ofSeconds(60));
    }
}
