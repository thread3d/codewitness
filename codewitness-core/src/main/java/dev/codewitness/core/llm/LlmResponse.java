package dev.codewitness.core.llm;

import java.time.Duration;

/**
 * A completion response.
 *
 * @param available whether the provider could be reached
 * @param text      response text, empty when unavailable
 * @param model     model that produced the response
 * @param duration  request duration
 * @param error     failure description when {@code available} is {@code false}
 */
public record LlmResponse(boolean available, String text, String model, Duration duration, String error) {

    public static LlmResponse unavailable(String model, String error) {
        return new LlmResponse(false, "", model, Duration.ZERO, error);
    }

    public static LlmResponse of(String text, String model, Duration duration) {
        return new LlmResponse(true, text == null ? "" : text, model, duration, null);
    }
}
