package dev.codewitness.core.config;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A verification scenario declared by the user in {@code codewitness.yaml}.
 *
 * <p>A scenario answers the question "how do I execute this requirement against this code?".
 * CodeWitness cannot invent a project's API, so the user declares the setup, the calls, the inputs
 * to search over and the oracle to check. CodeWitness then supplies the part that is mechanical and
 * error prone: boundary enumeration, seeded random search, execution, and reproducible
 * counterexamples.
 *
 * <p>Every fragment in a scenario is authored by the user and is recorded as
 * {@link dev.codewitness.core.requirements.ObligationOrigin#USER_DECLARED}. A scenario never marks a
 * requirement verified on its own; it produces evidence that the engine weighs like any other.
 *
 * @param id          scenario identifier, for example {@code SC-ROLLING}
 * @param requirement requirement the scenario is intended to exercise
 * @param description human readable description
 * @param mode        how the search runs
 * @param setup       Java statements executed before each case, or {@code null}
 * @param steps       calls executed per case, in order
 * @param observation Java expression evaluated after the steps, made available to the invariant
 * @param invariant   Java boolean expression that must hold; may reference {@code observation} and inputs
 * @param cases       number of randomised cases to run
 * @param seed        random seed; recorded in evidence so a failing case can be replayed exactly
 * @param timeout     per-case wall-clock budget in seconds
 */
public record Scenario(
        String id,
        String requirement,
        String description,
        Mode mode,
        String setup,
        List<Step> steps,
        String observation,
        String invariant,
        int cases,
        long seed,
        int timeoutSeconds) {

    public Scenario {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(requirement, "requirement");
        description = description == null ? id : description;
        mode = mode == null ? Mode.SINGLE : mode;
        steps = List.copyOf(steps == null ? List.of() : steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("scenario " + id + " declares no steps");
        }
        cases = Math.max(1, cases);
        timeoutSeconds = timeoutSeconds <= 0 ? 60 : timeoutSeconds;
    }

    /** How the declared steps are driven. */
    public enum Mode {
        /** One execution of the step list; inputs come from explicit boundary values. */
        SINGLE,
        /** Seeded random search over the declared input domains, checking the invariant after each case. */
        SEQUENCE,
        /** The step list is executed concurrently from several threads. */
        CONCURRENT
    }

    /** One call in a scenario. */
    public record Step(String call, List<Input> inputs, String description) {
        public Step {
            Objects.requireNonNull(call, "call");
            inputs = List.copyOf(inputs == null ? List.of() : inputs);
            description = description == null ? call : description;
        }
    }

    /**
     * A searchable input of a scenario step.
     *
     * @param name       identifier used inside the scenario's fragments: {@code ${name}} for the Java,
     *     Python, Rust and Go generators, and {@code #{name}} for the JavaScript and TypeScript
     *     generators, where {@code ${...}} would be template-literal interpolation
     * @param type       fully qualified Java type for Java, a JavaScript/TypeScript type for the Node
     *     generators, or a primitive such as {@code long} / {@code i64} / {@code int64} / {@code bigint}
     * @param domain     how values are drawn
     * @param values     explicit values used for boundary cases
     * @param min        inclusive lower bound for random search, or {@code null}
     * @param max        inclusive upper bound for random search, or {@code null}
     * @param scale      decimal scale for {@code decimal} domains
     */
    public record Input(
            String name,
            String type,
            Domain domain,
            List<String> values,
            String min,
            String max,
            int scale) {

        public Input {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            domain = domain == null ? Domain.fromType(type) : domain;
            values = List.copyOf(values == null ? List.of() : values);
        }

        public BigDecimal minDecimal() {
            return min == null ? null : new BigDecimal(min);
        }

        public BigDecimal maxDecimal() {
            return max == null ? null : new BigDecimal(max);
        }

        /** Boundary values to try before randomised search: explicit values plus 1 ulp inside/outside the range. */
        public List<String> boundaryValues() {
            if (!values.isEmpty()) {
                return values;
            }
            if (domain == Domain.DECIMAL || domain == Domain.INTEGER || domain == Domain.LONG) {
                List<String> generated = new java.util.ArrayList<>();
                if (min != null) {
                    generated.add(min);
                }
                if (max != null) {
                    generated.add(max);
                }
                return List.copyOf(generated);
            }
            return List.of();
        }
    }

    /** Value domains understood by the built in generators. */
    public enum Domain {
        DECIMAL,
        INTEGER,
        LONG,
        DOUBLE,
        BOOLEAN,
        STRING,
        INSTANT_MILLIS,
        NULL,
        RAW;

        public static Domain fromType(String type) {
            return switch (type) {
                case "java.math.BigDecimal" -> DECIMAL;
                case "int", "java.lang.Integer", "java.lang.Short", "java.lang.Byte" -> INTEGER;
                case "long", "java.lang.Long" -> LONG;
                case "double", "float", "java.lang.Double", "java.lang.Float" -> DOUBLE;
                case "boolean", "java.lang.Boolean" -> BOOLEAN;
                case "java.lang.String" -> STRING;
                case "java.time.Instant" -> INSTANT_MILLIS;
                default -> RAW;
            };
        }

        /**
         * Parses a {@code domain:} value case-insensitively, ignoring underscores, hyphens and spaces.
         *
         * <p>The domain is written by hand in YAML, so the spelling has to be forgiving in the
         * direction the documentation uses it: {@code instantMillis} and {@code instant_millis} both
         * mean {@link #INSTANT_MILLIS}. A bare {@code Enum.valueOf} on the upper-cased text rejected
         * {@code instantMillis} - the spelling the scenario reference and the {@code init} template
         * both teach - and the {@link IllegalArgumentException} escaped as a stack trace rather than a
         * configuration error. An unknown domain is still an error, but a stated one.
         *
         * @param value    text from the configuration, or {@code null}
         * @param fallback domain to use when {@code value} is absent or blank
         * @return the parsed domain
         */
        public static Domain parse(String value, Domain fallback) {
            if (value == null || value.isBlank()) {
                return fallback;
            }
            String normalized = normalize(value);
            for (Domain domain : values()) {
                if (normalize(domain.name()).equals(normalized)) {
                    return domain;
                }
            }
            throw new IllegalArgumentException("unknown input domain \"" + value.trim() + "\"; known domains are "
                    + java.util.Arrays.stream(values())
                            .map(domain -> domain.name().toLowerCase(java.util.Locale.ROOT))
                            .collect(java.util.stream.Collectors.joining(", ")));
        }

        private static String normalize(String value) {
            return value.trim().toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
        }
    }

    /** Convenience view of a scenario as a flat map, used by templates and reports. */
    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("requirement", requirement);
        map.put("mode", mode.name());
        map.put("cases", cases);
        map.put("seed", seed);
        return map;
    }
}
