package dev.codewitness.core.llm;

import dev.codewitness.core.config.VerificationConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Creates the language model provider described by configuration.
 *
 * <p>Only two providers exist in the MVP: a disabled provider (the default) and an OpenAI-compatible
 * HTTP provider. Keeping the surface this small is intentional - the model is advisory, and a
 * verification tool that can be pointed at an arbitrary endpoint by a config file is a security
 * liability, so the endpoint and the API key environment variable are both explicit.
 */
public final class LlmProviderFactory {

    private LlmProviderFactory() {
    }

    public static LlmProvider create(VerificationConfig.Llm config) {
        if (config == null || !config.enabled()) {
            return LlmProvider.disabled("LLM assistance is disabled");
        }
        String provider = config.provider() == null ? "none" : config.provider().trim().toLowerCase(java.util.Locale.ROOT);
        return switch (provider) {
            case "none", "disabled" -> LlmProvider.disabled("provider is 'none'");
            case "auto", "openai", "openai-compatible", "http" -> new OpenAiCompatibleLlmProvider(config);
            default -> LlmProvider.disabled("unknown provider '" + provider + "'");
        };
    }

    /**
     * Minimal client for an OpenAI compatible {@code /chat/completions} endpoint.
     *
     * <p>Never throws to its caller: a failed request becomes an unavailable response that the
     * engine records as an infrastructure note, so a flaky model endpoint can never turn into a
     * verification result.
     */
    public static final class OpenAiCompatibleLlmProvider implements LlmProvider {

        private final VerificationConfig.Llm config;
        private final String apiKey;
        private final String baseUrl;
        private final HttpClient client;

        public OpenAiCompatibleLlmProvider(VerificationConfig.Llm config) {
            this.config = config;
            this.apiKey = System.getenv(config.apiKeyEnvironmentVariable());
            String url = config.baseUrl();
            if (url == null || url.isBlank()) {
                url = System.getenv("CODEWITNESS_LLM_BASE_URL");
            }
            this.baseUrl = url == null ? "https://api.openai.com/v1" : url.replaceAll("/+$", "");
            this.client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
        }

        @Override
        public String id() {
            return "openai-compatible";
        }

        @Override
        public boolean available() {
            return apiKey != null && !apiKey.isBlank();
        }

        @Override
        public String model() {
            return config.model() == null ? "provider default" : config.model();
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            java.time.Instant start = java.time.Instant.now();
            if (!available()) {
                return LlmResponse.unavailable(model(),
                        "no API key in environment variable " + config.apiKeyEnvironmentVariable());
            }
            String body = Json.minimal(
                    Map.of("model", model(),
                            "temperature", request.temperature(),
                            "max_tokens", request.maxTokens(),
                            "messages", java.util.List.of(
                                    Map.of("role", "system", "content", request.prompt().systemInstruction()),
                                    Map.of("role", "user", "content", request.prompt().render()))));
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(request.timeout() == null ? Duration.ofSeconds(60) : request.timeout())
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            try {
                HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                Duration duration = Duration.between(start, java.time.Instant.now());
                if (response.statusCode() / 100 != 2) {
                    return LlmResponse.unavailable(model(),
                            "HTTP " + response.statusCode() + ": " + truncate(response.body()));
                }
                String text = Json.extractFirstString(response.body(), "content");
                if (text == null) {
                    text = response.body();
                }
                return LlmResponse.of(text, model(), duration);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return LlmResponse.unavailable(model(), "interrupted");
            } catch (Exception e) {
                return LlmResponse.unavailable(model(), e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        private static String truncate(String text) {
            if (text == null) {
                return "";
            }
            return text.length() <= 500 ? text : text.substring(0, 500) + "...";
        }
    }

    /**
     * A tiny JSON writer/reader. CodeWitness core deliberately avoids a JSON dependency for the two
     * places it needs one, so that the verification core stays dependency free and auditable.
     */
    static final class Json {

        private Json() {
        }

        static String minimal(Map<String, Object> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(escape(entry.getKey())).append("\":").append(value(entry.getValue()));
            }
            return sb.append('}').toString();
        }

        @SuppressWarnings("unchecked")
        private static String value(Object value) {
            if (value == null) {
                return "null";
            }
            if (value instanceof String s) {
                return "\"" + escape(s) + "\"";
            }
            if (value instanceof Number || value instanceof Boolean) {
                return value.toString();
            }
            if (value instanceof Map<?, ?> map) {
                return minimal((Map<String, Object>) map);
            }
            if (value instanceof java.util.List<?> list) {
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (Object element : list) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    sb.append(value(element));
                }
                return sb.append(']').toString();
            }
            return "\"" + escape(String.valueOf(value)) + "\"";
        }

        private static String escape(String text) {
            return text.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        }

        /**
         * Extracts the first string value of a named field from a JSON document.
         *
         * @param json  document text
         * @param field field name, for example {@code content}
         * @return the value, or {@code null} when absent
         */
        static String extractFirstString(String json, String field) {
            if (json == null) {
                return null;
            }
            String needle = "\"" + field + "\"";
            int index = json.indexOf(needle);
            if (index < 0) {
                return null;
            }
            int colon = json.indexOf(':', index + needle.length());
            if (colon < 0) {
                return null;
            }
            int quote = json.indexOf('"', colon + 1);
            if (quote < 0) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = quote + 1; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) {
                    char next = json.charAt(++i);
                    switch (next) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        default -> sb.append(next);
                    }
                    continue;
                }
                if (c == '"') {
                    return sb.toString();
                }
                sb.append(c);
            }
            return null;
        }
    }
}
