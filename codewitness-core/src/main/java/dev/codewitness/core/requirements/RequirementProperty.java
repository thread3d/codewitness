package dev.codewitness.core.requirements;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A machine readable property extracted from a natural language requirement.
 *
 * <p>This is an <em>interpretation</em> produced by CodeWitness, not the requirement itself. Reports
 * always show the original text alongside it.
 */
public record RequirementProperty(String type, Map<String, Object> values, String rawText) {

    public RequirementProperty {
        Objects.requireNonNull(type, "type");
        values = Map.copyOf(new LinkedHashMap<>(values == null ? Map.of() : values));
    }

    public static RequirementProperty of(String type, Map<String, Object> values) {
        return new RequirementProperty(type, values, null);
    }

    public static RequirementProperty of(String type, String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return new RequirementProperty(type, map, null);
    }

    public Object value(String key) {
        return values.get(key);
    }

    public String stringValue(String key) {
        Object value = values.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public java.math.BigDecimal decimalValue(String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof java.math.BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new java.math.BigDecimal(number.toString());
        }
        try {
            return new java.math.BigDecimal(String.valueOf(value).replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
