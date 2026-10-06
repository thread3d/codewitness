package dev.codewitness.core.requirements;

import dev.codewitness.core.config.Scenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The planner decides what "verifying" a requirement even means. A user-declared scenario is the
 * strongest statement of intent, derived properties come next, and a requirement with nothing
 * executable gets a MANUAL obligation that keeps it out of VERIFIED rather than a fake one.
 */
class ObligationPlannerTest {

    private static Scenario scenario(String id, String requirementId, Scenario.Mode mode) {
        return new Scenario(id, requirementId, "description of " + id, mode, null,
                List.of(new Scenario.Step("ledger.run()", List.of(), null)), null, null, 10, 5L, 30);
    }

    private static Requirement requirement(String id, RequirementProperty... properties) {
        Requirement.Builder builder = Requirement.builder(id, "The system shall satisfy " + id);
        for (RequirementProperty property : properties) {
            builder.property(property);
        }
        return builder.build();
    }

    private static RequirementProperty property(String type, Map<String, Object> values) {
        return RequirementProperty.of(type, values);
    }

    @Test
    @DisplayName("a scenario declared for R-001 produces exactly one obligation on R-001")
    void scenarioProducesExactlyOneObligation() {
        ObligationPlanner planner = new ObligationPlanner();
        Requirement requirement = requirement("R-001");

        List<VerificationObligation> obligations =
                planner.plan(requirement, List.of(scenario("SC-ROLLING", "R-001", Scenario.Mode.SEQUENCE)));

        assertEquals(1, obligations.size());
        VerificationObligation obligation = obligations.get(0);
        assertEquals("R-001", obligation.requirementId());
        assertEquals(ObligationOrigin.USER_DECLARED, obligation.origin());
        assertEquals(ObligationKind.PROPERTY, obligation.kind());
        assertEquals("SC-ROLLING", obligation.target());
        assertEquals("SC-ROLLING", obligation.parameter("scenario"));
        assertEquals("SEQUENCE", obligation.parameter("mode"));
        assertEquals("10", obligation.parameter("cases"));
        assertEquals("5", obligation.parameter("seed"));
        assertFalse(obligation.advisoryOnly());
    }

    @Test
    @DisplayName("a scenario declared for R-001 produces nothing on R-002")
    void scenarioForAnotherRequirementProducesNothingThere() {
        ObligationPlanner planner = new ObligationPlanner();
        Requirement other = requirement("R-002", property("upper_bound", Map.of("value", new BigDecimal("1000"))));

        List<VerificationObligation> obligations =
                planner.plan(other, List.of(scenario("SC-ROLLING", "R-001", Scenario.Mode.SEQUENCE)));

        assertTrue(obligations.stream().noneMatch(o -> o.origin() == ObligationOrigin.USER_DECLARED),
                obligations.toString());
        assertTrue(obligations.stream().noneMatch(o -> "SC-ROLLING".equals(o.target())), obligations.toString());
        assertEquals(1, obligations.size());
        assertEquals(ObligationKind.UPPER_BOUND, obligations.get(0).kind());
    }

    @Test
    @DisplayName("the obligation kind follows the scenario mode")
    void obligationKindFollowsScenarioMode() {
        ObligationPlanner planner = new ObligationPlanner();
        List<VerificationObligation> obligations = planner.plan(requirement("R-001"), List.of(
                scenario("SC-SINGLE", "R-001", Scenario.Mode.SINGLE),
                scenario("SC-SEQUENCE", "R-001", Scenario.Mode.SEQUENCE),
                scenario("SC-CONCURRENT", "R-001", Scenario.Mode.CONCURRENT)));

        assertEquals(List.of(ObligationKind.BOUNDARY_VALUE, ObligationKind.PROPERTY, ObligationKind.CONCURRENCY),
                obligations.stream().map(VerificationObligation::kind).toList());
    }

    @Test
    @DisplayName("property-derived obligations are only created when the requirement has no scenario")
    void scenarioSuppressesPropertyDerivedObligations() {
        // Design decision: a declared scenario is a stronger statement of intent than a guessed property.
        // Adding both would leave a permanent "not executed" entry that keeps a passing requirement out of
        // VERIFIED. The interpreted property is still reported, just not planned as an obligation.
        ObligationPlanner planner = new ObligationPlanner();
        Requirement requirement = requirement("R-001",
                property("upper_bound", Map.of("value", new BigDecimal("1000"))),
                property("concurrency", Map.of()));

        List<VerificationObligation> obligations =
                planner.plan(requirement, List.of(scenario("SC-ROLLING", "R-001", Scenario.Mode.SEQUENCE)));

        assertEquals(1, obligations.size());
        assertEquals(ObligationOrigin.USER_DECLARED, obligations.get(0).origin());
    }

    @Test
    @DisplayName("without a scenario the upper bound becomes a boundary obligation carrying its value")
    void upperBoundBecomesBoundaryObligation() {
        ObligationPlanner planner = new ObligationPlanner();
        Requirement requirement = requirement("R-001",
                property("upper_bound", Map.of("value", new BigDecimal("1000"))));

        List<VerificationObligation> obligations = planner.plan(requirement, List.of());

        assertEquals(1, obligations.size());
        VerificationObligation obligation = obligations.get(0);
        assertEquals(ObligationKind.UPPER_BOUND, obligation.kind());
        assertEquals(ObligationOrigin.REQUIREMENT_TEXT, obligation.origin());
        assertEquals("upper", obligation.parameter("direction"));
        assertEquals("1000", obligation.parameter("value"));
        assertTrue(obligation.description().contains("1000"), obligation.description());
    }

    @Test
    @DisplayName("a lower bound becomes a lower-bound obligation")
    void lowerBoundBecomesObligation() {
        ObligationPlanner planner = new ObligationPlanner();
        Requirement requirement = requirement("R-001", property("lower_bound", Map.of("value", new BigDecimal("50"))));

        List<VerificationObligation> obligations = planner.plan(requirement, List.of());

        assertEquals(ObligationKind.LOWER_BOUND, obligations.get(0).kind());
        assertEquals("lower", obligations.get(0).parameter("direction"));
    }

    @Test
    void rejectionBecomesObligation() {
        ObligationPlanner planner = new ObligationPlanner();

        List<VerificationObligation> obligations =
                planner.plan(requirement("R-001", property("rejection", Map.of())), List.of());

        assertEquals(1, obligations.size());
        assertEquals(ObligationKind.REJECTION, obligations.get(0).kind());
    }

    @Test
    @DisplayName("a concurrency property becomes a concurrency obligation")
    void concurrencyBecomesObligation() {
        ObligationPlanner planner = new ObligationPlanner();

        List<VerificationObligation> obligations =
                planner.plan(requirement("R-001", property("concurrency", Map.of())), List.of());

        assertEquals(ObligationKind.CONCURRENCY, obligations.get(0).kind());
    }

    @Test
    @DisplayName("a null_handling property becomes a null-handling obligation")
    void nullHandlingBecomesObligation() {
        ObligationPlanner planner = new ObligationPlanner();

        List<VerificationObligation> obligations =
                planner.plan(requirement("R-001", property("null_handling", Map.of())), List.of());

        assertEquals(ObligationKind.NULL_HANDLING, obligations.get(0).kind());
    }

    @Test
    @DisplayName("a determinism property becomes a determinism obligation")
    void determinismBecomesObligation() {
        ObligationPlanner planner = new ObligationPlanner();

        List<VerificationObligation> obligations =
                planner.plan(requirement("R-001", property("determinism", Map.of())), List.of());

        assertEquals(ObligationKind.DETERMINISM, obligations.get(0).kind());
    }

    @Test
    @DisplayName("a rolling_window property becomes an invariant obligation")
    void rollingWindowBecomesInvariantObligation() {
        ObligationPlanner planner = new ObligationPlanner();
        Requirement requirement = requirement("R-001",
                property("rolling_window", Map.of("window", "24 hours", "basis", "rolling")));

        List<VerificationObligation> obligations = planner.plan(requirement, List.of());

        assertEquals(ObligationKind.INVARIANT, obligations.get(0).kind());
        assertTrue(obligations.get(0).description().contains("24 hours"), obligations.get(0).description());
    }

    @Test
    @DisplayName("a manual property becomes a MANUAL obligation")
    void manualPropertyBecomesManualObligation() {
        ObligationPlanner planner = new ObligationPlanner();

        List<VerificationObligation> obligations =
                planner.plan(requirement("R-001", property("manual", Map.of())), List.of());

        assertEquals(ObligationKind.MANUAL, obligations.get(0).kind());
        assertEquals(ObligationOrigin.USER_DECLARED, obligations.get(0).origin());
    }

    @Test
    @DisplayName("a static property becomes a STATIC obligation")
    void staticPropertyBecomesStaticObligation() {
        ObligationPlanner planner = new ObligationPlanner();

        List<VerificationObligation> obligations =
                planner.plan(requirement("R-001", property("static", Map.of())), List.of());

        assertEquals(ObligationKind.STATIC, obligations.get(0).kind());
    }

    @Test
    @DisplayName("a security property becomes a SECURITY obligation")
    void securityPropertyBecomesSecurityObligation() {
        ObligationPlanner planner = new ObligationPlanner();

        List<VerificationObligation> obligations =
                planner.plan(requirement("R-001", property("security", Map.of())), List.of());

        assertEquals(ObligationKind.SECURITY, obligations.get(0).kind());
    }

    @Test
    @DisplayName("a requirement with nothing executable gets one MANUAL obligation")
    void nothingExecutableGetsOneManualObligation() {
        ObligationPlanner planner = new ObligationPlanner();

        List<VerificationObligation> obligations = planner.plan(requirement("R-001"), List.of());

        assertEquals(1, obligations.size());
        VerificationObligation obligation = obligations.get(0);
        assertEquals(ObligationKind.MANUAL, obligation.kind());
        assertEquals(ObligationOrigin.REQUIREMENT_TEXT, obligation.origin());
        assertEquals("no scenario, no derived property and no test mapping", obligation.parameter("reason"));
        assertTrue(obligation.description().contains("remain UNVERIFIED"), obligation.description());
    }

    @Test
    @DisplayName("an unknown property type adds nothing and still yields the MANUAL fallback")
    void unknownPropertyYieldsManualFallback() {
        ObligationPlanner planner = new ObligationPlanner();

        List<VerificationObligation> obligations =
                planner.plan(requirement("R-001", property("something_new", Map.of())), List.of());

        assertEquals(1, obligations.size());
        assertEquals(ObligationKind.MANUAL, obligations.get(0).kind());
    }

    @Test
    @DisplayName("obligation ids are unique across calls and formatted OB-nnn")
    void obligationIdsAreUniqueAcrossCalls() {
        ObligationPlanner planner = new ObligationPlanner();

        String first = planner.plan(requirement("R-001"), List.of()).get(0).id();
        String second = planner.plan(requirement("R-002"), List.of()).get(0).id();

        assertEquals("OB-001", first);
        assertEquals("OB-002", second);
        assertNotEquals(first, second);
        assertEquals(2, planner.issued());
    }

    @Test
    @DisplayName("all obligations planned in one call also get unique ids")
    void obligationsWithinOneCallGetUniqueIds() {
        ObligationPlanner planner = new ObligationPlanner();
        Requirement requirement = requirement("R-001",
                property("upper_bound", Map.of("value", new BigDecimal("10"))),
                property("null_handling", Map.of()),
                property("concurrency", Map.of()));

        List<String> ids = planner.plan(requirement, List.of()).stream()
                .map(VerificationObligation::id).toList();

        assertEquals(3, ids.size());
        assertEquals(ids.size(), ids.stream().distinct().count());
    }

    @Test
    @DisplayName("hasUsableBound only accepts a decimal value")
    void hasUsableBoundOnlyAcceptsADecimal() {
        assertTrue(ObligationPlanner.hasUsableBound(property("upper_bound", Map.of("value", new BigDecimal("1")))));
        assertTrue(ObligationPlanner.hasUsableBound(property("upper_bound", Map.of("value", 5))));
        assertFalse(ObligationPlanner.hasUsableBound(property("upper_bound", Map.of())));
        assertFalse(ObligationPlanner.hasUsableBound(property("upper_bound", Map.of("value", "not a number"))));
    }

    @Test
    @DisplayName("VerificationObligation with-ers preserve the rest of the obligation")
    void obligationWithErsPreserveTheRest() {
        VerificationObligation obligation = VerificationObligation.of(
                "OB-001", "R-001", "check it", ObligationKind.GENERIC, ObligationOrigin.REQUIREMENT_TEXT);

        VerificationObligation targeted = obligation.withTarget("com.example.Foo#bar")
                .withParameters(Map.of("k", "v"));

        assertEquals(obligation.id(), targeted.id());
        assertEquals(obligation.requirementId(), targeted.requirementId());
        assertEquals(obligation.description(), targeted.description());
        assertEquals(obligation.kind(), targeted.kind());
        assertEquals(obligation.origin(), targeted.origin());
        assertEquals("com.example.Foo#bar", targeted.target());
        assertEquals("v", targeted.parameter("k"));
    }
}
