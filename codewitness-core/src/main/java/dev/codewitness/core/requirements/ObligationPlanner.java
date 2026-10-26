package dev.codewitness.core.requirements;

import dev.codewitness.core.config.Scenario;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Turns requirements into verification obligations.
 *
 * <p>Three sources of obligations, in decreasing order of strength:
 * <ol>
 *   <li>scenarios declared by the user - these are executable by construction;</li>
 *   <li>properties derived from the requirement text - these tell the generators which boundaries
 *       to probe;</li>
 *   <li>obligations suggested by a language model - advisory, and marked as such.</li>
 * </ol>
 *
 * <p>When none of those produce anything, the planner says so explicitly instead of inventing a
 * weak obligation that would later be mistaken for coverage.
 */
public final class ObligationPlanner {

    private final AtomicInteger counter = new AtomicInteger();

    /**
     * Plans obligations for one requirement.
     *
     * @param requirement requirement to plan for
     * @param scenarios   scenarios whose {@code requirement} matches
     * @return planned obligations, in a stable order
     */
    public List<VerificationObligation> plan(Requirement requirement, List<Scenario> scenarios) {
        List<VerificationObligation> obligations = new ArrayList<>();

        for (Scenario scenario : scenarios) {
            if (!scenario.requirement().equals(requirement.id())) {
                continue;
            }
            ObligationKind kind = switch (scenario.mode()) {
                case CONCURRENT -> ObligationKind.CONCURRENCY;
                case SEQUENCE -> ObligationKind.PROPERTY;
                case SINGLE -> ObligationKind.BOUNDARY_VALUE;
                case PROCESS -> ObligationKind.PROCESS;
            };
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("scenario", scenario.id());
            parameters.put("mode", scenario.mode().name());
            parameters.put("cases", scenario.cases());
            parameters.put("seed", scenario.seed());
            obligations.add(new VerificationObligation(
                    nextId(),
                    requirement.id(),
                    "Execute scenario " + scenario.id() + ": " + scenario.description(),
                    kind,
                    ObligationOrigin.USER_DECLARED,
                    scenario.id(),
                    parameters,
                    false));
        }

        if (!obligations.isEmpty()) {
            // The user declared how to execute this requirement. That is a stronger statement of
            // intent than anything CodeWitness can infer from the wording, so the text-derived
            // obligations below are not added: they would be permanent "not executed" entries that
            // would keep the requirement out of VERIFIED even when the declared experiment passes.
            // The interpreted properties are still reported as an interpretation.
            return List.copyOf(obligations);
        }

        for (RequirementProperty property : requirement.properties()) {
            switch (property.type().toLowerCase(Locale.ROOT)) {
                case "upper_bound" -> obligations.add(boundaryObligation(requirement, property, true));
                case "lower_bound" -> obligations.add(boundaryObligation(requirement, property, false));
                case "rejection" -> obligations.add(new VerificationObligation(
                        nextId(), requirement.id(),
                        "An operation that would exceed the stated limit must be rejected",
                        ObligationKind.REJECTION, ObligationOrigin.REQUIREMENT_TEXT,
                        matchingScenario(requirement, scenarios), Map.of(), false));
                case "concurrency" -> obligations.add(new VerificationObligation(
                        nextId(), requirement.id(),
                        "The requirement must hold when operations are performed concurrently",
                        ObligationKind.CONCURRENCY, ObligationOrigin.REQUIREMENT_TEXT, null, Map.of(), false));
                case "null_handling" -> obligations.add(new VerificationObligation(
                        nextId(), requirement.id(),
                        "Null and missing inputs must be handled as the requirement describes",
                        ObligationKind.NULL_HANDLING, ObligationOrigin.REQUIREMENT_TEXT, null, Map.of(), false));
                case "determinism" -> obligations.add(new VerificationObligation(
                        nextId(), requirement.id(),
                        "Repeating the same operation with the same input must produce the same result",
                        ObligationKind.DETERMINISM, ObligationOrigin.REQUIREMENT_TEXT, null, Map.of(), false));
                case "rolling_window" -> obligations.add(rollingWindowObligation(requirement, property, nextId()));
                case "manual" -> obligations.add(new VerificationObligation(
                        nextId(), requirement.id(),
                        "This requirement is declared as requiring human review",
                        ObligationKind.MANUAL, ObligationOrigin.USER_DECLARED, null, Map.of(), false));
                case "static" -> obligations.add(new VerificationObligation(
                        nextId(), requirement.id(),
                        "Static analysis over the files this requirement covers must not report a serious finding",
                        ObligationKind.STATIC, ObligationOrigin.USER_DECLARED, null, Map.of(), false));
                case "security" -> obligations.add(new VerificationObligation(
                        nextId(), requirement.id(),
                        "Security analysis must not report a vulnerability in the files this requirement covers",
                        ObligationKind.SECURITY, ObligationOrigin.USER_DECLARED, null, Map.of(), false));
                default -> {
                    // Unknown property types add nothing; they are still visible in the report.
                }
            }
        }

        if (obligations.isEmpty()) {
            obligations.add(new VerificationObligation(
                    nextId(),
                    requirement.id(),
                    "No executable verification was declared for this requirement. CodeWitness cannot "
                            + "attempt it, so it must remain UNVERIFIED.",
                    ObligationKind.MANUAL,
                    ObligationOrigin.REQUIREMENT_TEXT,
                    null,
                    Map.of("reason", "no scenario, no derived property and no test mapping"),
                    false));
        }
        return List.copyOf(obligations);
    }

    private VerificationObligation boundaryObligation(
            Requirement requirement, RequirementProperty property, boolean upper) {
        Map<String, Object> parameters = new LinkedHashMap<>(property.values());
        parameters.put("direction", upper ? "upper" : "lower");
        String value = String.valueOf(property.values().getOrDefault("value", "the stated bound"));
        return new VerificationObligation(
                nextId(),
                requirement.id(),
                (upper ? "Values at, just below and just above the upper bound " + value
                        : "Values at, just above and just below the lower bound " + value)
                        + " must behave as the requirement states",
                upper ? ObligationKind.UPPER_BOUND : ObligationKind.LOWER_BOUND,
                ObligationOrigin.REQUIREMENT_TEXT,
                null,
                parameters,
                false);
    }

    private VerificationObligation rollingWindowObligation(
            Requirement requirement, RequirementProperty property, String id) {
        return new VerificationObligation(
                id,
                requirement.id(),
                "The limit must apply over a rolling window ("
                        + property.values().getOrDefault("window", "stated period")
                        + "), not a calendar period",
                ObligationKind.INVARIANT,
                ObligationOrigin.REQUIREMENT_TEXT,
                null,
                property.values(),
                false);
    }

    private String matchingScenario(Requirement requirement, List<Scenario> scenarios) {
        for (Scenario scenario : scenarios) {
            if (scenario.requirement().equals(requirement.id())) {
                return scenario.id();
            }
        }
        return null;
    }

    /** @return a new obligation id of the form {@code OB-001}. */
    public String nextId() {
        return String.format(Locale.ROOT, "OB-%03d", counter.incrementAndGet());
    }

    /** @return the number of obligation ids handed out so far. */
    public int issued() {
        return counter.get();
    }

    /** Convenience: {@code true} when the property carries a usable decimal bound. */
    public static boolean hasUsableBound(RequirementProperty property) {
        BigDecimal value = property.decimalValue("value");
        return value != null;
    }
}
