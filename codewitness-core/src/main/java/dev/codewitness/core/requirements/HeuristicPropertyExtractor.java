package dev.codewitness.core.requirements;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives candidate structured properties from the natural language of a requirement.
 *
 * <p>This is a deterministic, deliberately small heuristic pass - not natural language
 * understanding. Everything it produces is an <em>interpretation</em> and is labelled as such in the
 * report: the original sentence stays authoritative. The point is to give the obligation planner
 * and the test generators something concrete to work with when no language model is configured, and
 * to be honest when the interpretation is weak.
 */
public final class HeuristicPropertyExtractor {

    /** Matches money-like amounts: 1,000 / 1000 / €1,000 / 1000.50 */
    private static final Pattern AMOUNT = Pattern.compile(
            "(?:[€$£]\\s*)?(\\d{1,3}(?:[,\\s]\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)");

    private static final Pattern UPPER_BOUND = Pattern.compile(
            "(?i)\\b(at most|no more than|not exceed|must not exceed|maximum of|up to|"
                    + "cannot exceed|may not exceed|less than or equal to|ceiling of)\\b");

    private static final Pattern LOWER_BOUND = Pattern.compile(
            "(?i)\\b(at least|no less than|minimum of|greater than or equal to)\\b");

    private static final Pattern REJECTION = Pattern.compile(
            "(?i)\\b(reject(?:ed|s)?|refus(?:e|ed|es)|must fail|fail(?:s)? with|denied|declined)\\b");

    private static final Pattern CONCURRENCY = Pattern.compile(
            "(?i)\\b(concurren\\w*|simultaneous\\w*|parallel\\w*|thread[- ]safe\\w*|race condition)\\b");

    private static final Pattern NULL_HANDLING = Pattern.compile(
            "(?i)\\b(null|missing|absent|empty input)\\b");

    private static final Pattern DETERMINISM = Pattern.compile(
            "(?i)\\b(determinis\\w*|idempotent|reproducib\\w*)\\b");

    private static final Pattern ROLLING_WINDOW = Pattern.compile(
            "(?i)\\b(rolling|sliding)\\s+(\\d+)\\s*[- ]?(hour|day|minute|week)s?\\b");

    private HeuristicPropertyExtractor() {
    }

    /** @return a copy of the requirement with any properties the heuristics recognised. */
    public static Requirement enrich(Requirement requirement) {
        String text = requirement.description();
        if (text == null || text.isBlank()) {
            return requirement;
        }
        Requirement result = requirement;

        Matcher rolling = ROLLING_WINDOW.matcher(text);
        Map<String, Object> rollingValues = null;
        if (rolling.find()) {
            rollingValues = new LinkedHashMap<>();
            rollingValues.put("window", rolling.group(2) + " " + rolling.group(3) + "s");
            rollingValues.put("basis", "rolling");
        }

        if (UPPER_BOUND.matcher(text).find()) {
            BigDecimal amount = firstAmount(text);
            Map<String, Object> values = new LinkedHashMap<>();
            if (amount != null) {
                values.put("value", amount);
            }
            if (rollingValues != null) {
                values.putAll(rollingValues);
            }
            result = result.withProperty(new RequirementProperty("upper_bound", values,
                    "derived by CodeWitness from the wording of the requirement; verify against the text"));
        } else if (LOWER_BOUND.matcher(text).find()) {
            BigDecimal amount = firstAmount(text);
            Map<String, Object> values = new LinkedHashMap<>();
            if (amount != null) {
                values.put("value", amount);
            }
            result = result.withProperty(new RequirementProperty("lower_bound", values, null));
        }

        if (REJECTION.matcher(text).find()) {
            result = result.withProperty(new RequirementProperty("rejection",
                    Map.of("condition", "operation exceeds a stated limit"),
                    "derived by CodeWitness from the word \"reject\""));
        }
        if (CONCURRENCY.matcher(text).find()) {
            result = result.withProperty(RequirementProperty.of("concurrency", Map.of()));
        }
        if (NULL_HANDLING.matcher(text).find()) {
            result = result.withProperty(RequirementProperty.of("null_handling", Map.of()));
        }
        if (DETERMINISM.matcher(text).find()) {
            result = result.withProperty(RequirementProperty.of("determinism", Map.of()));
        }
        if (rollingValues != null) {
            result = result.withProperty(new RequirementProperty("rolling_window", rollingValues, null));
        }
        return result;
    }

    /** @return the first number that looks like an amount, or {@code null}. */
    public static BigDecimal firstAmount(String text) {
        Matcher matcher = AMOUNT.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group(1).replaceAll("[,\\s]", "");
            try {
                return new BigDecimal(raw);
            } catch (NumberFormatException _) {
                // keep scanning
            }
        }
        return null;
    }

    /** @return the property of the given type, if present. */
    public static java.util.Optional<RequirementProperty> property(Requirement requirement, String type) {
        return requirement.properties().stream()
                .filter(p -> p.type().equalsIgnoreCase(type))
                .findFirst();
    }

    /** @return {@code true} when the requirement text mentions the given requirement id. */
    public static boolean mentions(String text, String requirementId) {
        return text != null && text.toUpperCase(Locale.ROOT).contains(requirementId.toUpperCase(Locale.ROOT));
    }

    /** @return all properties as a flat map, for reports. */
    public static Map<String, Object> describe(List<RequirementProperty> properties) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (RequirementProperty property : properties) {
            map.put(property.type(), property.values());
        }
        return map;
    }
}
