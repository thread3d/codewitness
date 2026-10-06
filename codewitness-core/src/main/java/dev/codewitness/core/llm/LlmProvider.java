package dev.codewitness.core.llm;

/**
 * A language model, used strictly in an advisory role.
 *
 * <p>CodeWitness's engine never calls this interface to decide a verdict. Model output is recorded
 * as {@code LLM_ANALYSIS} evidence with {@code ADVISORY} confidence, and anything it suggests must
 * be turned into an executable test and run before it can count as a counterexample.
 */
public interface LlmProvider {

    /** Identifier of the provider, for example {@code none} or {@code openai-compatible}. */
    String id();

    /** @return {@code true} when the provider is configured and reachable. */
    boolean available();

    /** @return the model identifier recorded in the environment snapshot. */
    String model();

    /** Sends a completion request. Implementations must never throw; failures return an unavailable response. */
    LlmResponse complete(LlmRequest request);

    /** A provider that is never available. Used when LLM assistance is disabled. */
    static LlmProvider disabled(String reason) {
        return new LlmProvider() {
            @Override
            public String id() {
                return "none";
            }

            @Override
            public boolean available() {
                return false;
            }

            @Override
            public String model() {
                return "none (" + reason + ")";
            }

            @Override
            public LlmResponse complete(LlmRequest request) {
                return LlmResponse.unavailable(model(), reason);
            }
        };
    }
}
