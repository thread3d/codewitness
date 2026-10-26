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
 * @param command     argv for {@link Mode#PROCESS}, empty for the other modes
 * @param expect      what the process must print and return, or {@code null} for the other modes
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
        int timeoutSeconds,
        List<String> command,
        ProcessExpectation expect) {

    public Scenario {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(requirement, "requirement");
        description = description == null ? id : description;
        mode = mode == null ? Mode.SINGLE : mode;
        steps = List.copyOf(steps == null ? List.of() : steps);
        command = List.copyOf(command == null ? List.of() : command);
        if (mode == Mode.PROCESS) {
            // A process scenario names a command and what it must produce; it has no step list, because there is no
            // API to call. Requiring one would force every process scenario to carry a fragment nobody reads.
            if (command.isEmpty()) {
                throw new IllegalArgumentException("scenario " + id
                        + " has mode process but declares no command; a process scenario runs a command and asserts "
                        + "on what it returns");
            }
            if (expect == null) {
                throw new IllegalArgumentException("scenario " + id
                        + " has mode process but declares no expect block; a command with nothing to assert would "
                        + "run and prove nothing");
            }
        } else {
            if (!command.isEmpty()) {
                throw new IllegalArgumentException("scenario " + id + " declares a command but its mode is "
                        + mode.name().toLowerCase(java.util.Locale.ROOT) + "; command and expect are only read in "
                        + "mode process, and silently ignoring them would leave the scenario doing something other "
                        + "than what it says");
            }
            if (steps.isEmpty()) {
                throw new IllegalArgumentException("scenario " + id + " declares no steps");
            }
        }
        cases = Math.max(1, cases);
        timeoutSeconds = timeoutSeconds <= 0 ? 60 : timeoutSeconds;
    }

    /**
     * A scenario with no command, which is every mode but {@link Mode#PROCESS}.
     *
     * <p>Kept so that a caller written before process scenarios existed reads as it did: a step list, an observation
     * and an invariant.
     */
    public Scenario(
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
        this(id, requirement, description, mode, setup, steps, observation, invariant, cases, seed, timeoutSeconds,
                List.of(), null);
    }

    /** How the declared steps are driven. */
    public enum Mode {
        /** One execution of the step list; inputs come from explicit boundary values. */
        SINGLE,
        /** Seeded random search over the declared input domains, checking the invariant after each case. */
        SEQUENCE,
        /** The step list is executed concurrently from several threads. */
        CONCURRENT,
        /**
         * A command is executed once and its exit code, standard output and standard error are asserted.
         *
         * <p>This is the only mode that can say anything about a program's own interface. A generated test calls
         * methods inside the project's test framework; it cannot observe what a *caller of the built program* sees,
         * which is why an exit-code contract used to be permanently unverifiable — including CodeWitness's own.
         */
        PROCESS
    }

    /**
     * What a {@link Mode#PROCESS} scenario requires of the process it ran.
     *
     * <p>Assertions are deliberately declarative rather than an expression language. A process outcome is three
     * values, and a free-form expression evaluated inside CodeWitness would be a second, differently-behaved
     * scenario language — the one thing every other mode avoids by evaluating its invariant in the project's own
     * environment.
     *
     * @param exitCode       the exit code the command must return, or {@code null} to accept any
     * @param stdoutContains substrings that must appear in standard output, empty to accept anything
     * @param stderrContains substrings that must appear in standard error, empty to accept anything
     */
    public record ProcessExpectation(Integer exitCode, List<String> stdoutContains, List<String> stderrContains) {

        public ProcessExpectation {
            stdoutContains = List.copyOf(stdoutContains == null ? List.of() : stdoutContains);
            stderrContains = List.copyOf(stderrContains == null ? List.of() : stderrContains);
        }

        /** @return {@code true} when at least one assertion is stated. */
        public boolean hasAssertions() {
            return exitCode != null || !stdoutContains.isEmpty() || !stderrContains.isEmpty();
        }
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
