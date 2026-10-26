package dev.codewitness.core.engine;

import dev.codewitness.core.evidence.Evidence;
import dev.codewitness.core.evidence.EvidenceConfidence;
import dev.codewitness.core.evidence.EvidenceStatus;
import dev.codewitness.core.evidence.EvidenceType;
import dev.codewitness.core.requirements.ObligationKind;
import dev.codewitness.core.requirements.ObligationOrigin;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.RequirementStatus;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.result.Counterexample;
import dev.codewitness.core.result.ObligationOutcome;
import dev.codewitness.core.result.RequirementVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Specifies the verdict rules - the place where CodeWitness must not be flattering.
 *
 * <p>Each test that encodes a deliberate design decision carries a comment saying why. Those tests
 * are part of the product's specification, not just regression guards.
 */
class VerdictCalculatorTest {

    private static final String R = "R-001";

    // ---------------------------------------------------------------- fixtures

    private static Requirement requirement(String id) {
        return Requirement.builder(id, "The system shall satisfy " + id).build();
    }

    private static VerificationObligation obligation(String id, ObligationKind kind) {
        return VerificationObligation.of(id, R, "Description of " + id, kind, ObligationOrigin.REQUIREMENT_TEXT);
    }

    private static ObligationOutcome outcome(String obligationId, RequirementStatus status) {
        return new ObligationOutcome(obligationId, status, "recorded by a strategy",
                List.of("EV-001"), List.of(), 1);
    }

    private static Evidence experiment(String id, EvidenceStatus status, EvidenceConfidence confidence) {
        return Evidence.builder(id, EvidenceType.GENERATED_TEST)
                .status(status)
                .confidence(confidence)
                .description("generated test evidence")
                .requirement(R)
                .build();
    }

    private static Evidence evidence(String id, EvidenceType type, EvidenceStatus status, EvidenceConfidence confidence) {
        return Evidence.builder(id, type)
                .status(status)
                .confidence(confidence)
                .description("evidence " + id)
                .requirement(R)
                .build();
    }

    private static Counterexample counterexample(String id) {
        return new Counterexample(id, R, "OB-001", "amount exceeds the allowed limit",
                Map.of("amount", "1000.01"), "at most 1000", "1000.01", "balance never negative",
                "WithdrawalTest", "EV-009", null, null, Counterexample.DiscoveryMethod.GENERATED_TEST);
    }

    private static RequirementVerdict compute(
            Requirement requirement,
            List<VerificationObligation> obligations,
            List<ObligationOutcome> outcomes,
            List<Evidence> evidence,
            List<Counterexample> counterexamples) {
        return VerdictCalculator.compute(requirement, obligations, outcomes, evidence, counterexamples,
                false, null, List.of());
    }

    // ---------------------------------------------------------------- verdicts

    @Test
    @DisplayName("an executed counterexample makes the requirement FAILED")
    void executedCounterexampleMakesRequirementFailed() {
        RequirementVerdict verdict = compute(requirement(R),
                List.of(obligation("OB-001", ObligationKind.BOUNDARY_VALUE)),
                List.of(outcome("OB-001", RequirementStatus.VERIFIED)),
                List.of(experiment("EV-001", EvidenceStatus.SUPPORTS, EvidenceConfidence.DETERMINISTIC)),
                List.of(counterexample("CE-001")));

        assertEquals(RequirementStatus.FAILED, verdict.status());
        assertTrue(verdict.rationale().contains("counterexample"), verdict.rationale());
        assertTrue(verdict.rationale().contains("CE-001"), verdict.rationale());
        assertEquals(List.of("CE-001"), verdict.counterexampleIds());
    }

    @Test
    @DisplayName("a failed build is BLOCKED, never FAILED - a broken build says nothing about the code")
    void failedBuildMakesRequirementBlockedAndNeverFailed() {
        // Design decision: an unbuildable project is an infrastructure problem, not a code defect.
        // Reporting FAILED here would accuse the code of violating a requirement that was never run.
        RequirementVerdict verdict = VerdictCalculator.compute(requirement(R),
                List.of(obligation("OB-001", ObligationKind.BOUNDARY_VALUE)),
                List.of(),
                List.of(),
                List.of(counterexample("CE-001")),
                true,
                "mvn test exited with code 1",
                List.of());

        assertEquals(RequirementStatus.BLOCKED, verdict.status());
        assertNotEquals(RequirementStatus.FAILED, verdict.status());
        assertTrue(verdict.rationale().contains("A failed build is not evidence that the code is incorrect"),
                verdict.rationale());
        assertTrue(verdict.rationale().contains("mvn test exited with code 1"), verdict.rationale());
    }

    @Test
    @DisplayName("BLOCKED takes precedence even when a counterexample object exists")
    void blockedBuildWinsOverCounterexample() {
        RequirementVerdict verdict = VerdictCalculator.compute(requirement(R),
                List.of(obligation("OB-001", ObligationKind.INVARIANT)),
                List.of(outcome("OB-001", RequirementStatus.FAILED)),
                List.of(evidence("EV-001", EvidenceType.COUNTEREXAMPLE, EvidenceStatus.REFUTES,
                        EvidenceConfidence.DETERMINISTIC)),
                List.of(counterexample("CE-001")),
                true,
                "compilation failed",
                List.of());

        assertEquals(RequirementStatus.BLOCKED, verdict.status());
        assertFalse(verdict.status().isFailure());
    }


    @Test
    @DisplayName("a failed build with nothing observed says nothing about counterexamples")
    void aBlockedBuildWithNoCounterexampleDoesNotClaimOne() {
        // The counterpart of the test above, and the direction that matters more. When a build fails and no
        // experiment recorded anything, the rationale must not claim an executed experiment found something: that
        // would be a fabricated observation inside the one report whose value is that it only says what it saw.
        // Mutation testing found this gap - negating the emptiness check in VerdictCalculator survived the suite.
        RequirementVerdict verdict = VerdictCalculator.compute(requirement(R),
                List.of(obligation("OB-001", ObligationKind.BOUNDARY_VALUE)),
                List.of(),
                List.of(),
                List.of(),
                true,
                "mvn test exited with code 1",
                List.of());

        assertEquals(RequirementStatus.BLOCKED, verdict.status());
        assertFalse(verdict.rationale().contains("did record"), verdict.rationale());
        assertTrue(verdict.counterexampleIds().isEmpty(), verdict.counterexampleIds().toString());
    }

    @Test
    @DisplayName("no evidence at all yields UNVERIFIED, never VERIFIED")
    void noEvidenceAtAllYieldsUnverified() {
        RequirementVerdict verdict = compute(requirement(R),
                List.of(obligation("OB-001", ObligationKind.BOUNDARY_VALUE)),
                List.of(),
                List.of(),
                List.of());

        assertEquals(RequirementStatus.UNVERIFIED, verdict.status());
        assertNotEquals(RequirementStatus.VERIFIED, verdict.status());
        assertFalse(verdict.deterministicEvidence());
        assertTrue(verdict.rationale().contains("no way to execute"), verdict.rationale());
    }

    @Test
    @DisplayName("a requirement with no obligations at all is UNVERIFIED and says why")
    void requirementWithoutObligationsIsUnverified() {
        RequirementVerdict verdict = compute(requirement(R), List.of(), List.of(), List.of(), List.of());

        assertEquals(RequirementStatus.UNVERIFIED, verdict.status());
        assertEquals("No verification obligations were derived for this requirement.", verdict.rationale());
    }

    @Test
    @DisplayName("every obligation discharged by an executed experiment yields VERIFIED")
    void allObligationsVerifiedWithAnExperimentYieldsVerified() {
        RequirementVerdict verdict = compute(requirement(R),
                List.of(obligation("OB-001", ObligationKind.BOUNDARY_VALUE)),
                List.of(outcome("OB-001", RequirementStatus.VERIFIED)),
                List.of(experiment("EV-001", EvidenceStatus.SUPPORTS, EvidenceConfidence.DETERMINISTIC)),
                List.of());

        assertEquals(RequirementStatus.VERIFIED, verdict.status());
        assertTrue(verdict.rationale().contains("Every planned obligation"), verdict.rationale());
        assertTrue(verdict.deterministicEvidence());
        assertTrue(verdict.unknownAspects().isEmpty(), verdict.unknownAspects().toString());
    }

    @Test
    @DisplayName("outcomes claiming success are not enough: VERIFIED needs an executed experiment")
    void verifiedRequiresAnExecutedExperiment() {
        // Design decision: a strategy saying "done" is not evidence. Without an experiment record the
        // best CodeWitness may say is PARTIALLY_VERIFIED.
        RequirementVerdict verdict = compute(requirement(R),
                List.of(obligation("OB-001", ObligationKind.BOUNDARY_VALUE)),
                List.of(outcome("OB-001", RequirementStatus.VERIFIED)),
                List.of(),
                List.of());

        assertNotEquals(RequirementStatus.VERIFIED, verdict.status());
        assertEquals(RequirementStatus.PARTIALLY_VERIFIED, verdict.status());
    }

    @Test
    @DisplayName("running the project's existing test suite is not by itself an experiment for VERIFIED")
    void existingTestEvidenceIsNotAnExperiment() {
        // Design decision: only GENERATED_TEST / PROPERTY_TEST / FUZZ_TEST count as experiments that can
        // discharge obligations. A passing pre-existing TEST evidence record is supporting context.
        RequirementVerdict verdict = compute(requirement(R),
                List.of(obligation("OB-001", ObligationKind.BOUNDARY_VALUE)),
                List.of(outcome("OB-001", RequirementStatus.VERIFIED)),
                List.of(evidence("EV-001", EvidenceType.TEST, EvidenceStatus.SUPPORTS, EvidenceConfidence.DETERMINISTIC)),
                List.of());

        assertEquals(RequirementStatus.PARTIALLY_VERIFIED, verdict.status());
        assertNotEquals(RequirementStatus.VERIFIED, verdict.status());
    }

    @Test
    @DisplayName("some obligations verified and some not yields PARTIALLY_VERIFIED")
    void someObligationsVerifiedYieldsPartiallyVerified() {
        RequirementVerdict verdict = compute(requirement(R),
                List.of(obligation("OB-001", ObligationKind.BOUNDARY_VALUE),
                        obligation("OB-002", ObligationKind.CONCURRENCY)),
                List.of(outcome("OB-001", RequirementStatus.VERIFIED)),
                List.of(experiment("EV-001", EvidenceStatus.SUPPORTS, EvidenceConfidence.DETERMINISTIC)),
                List.of());

        assertEquals(RequirementStatus.PARTIALLY_VERIFIED, verdict.status());
        assertTrue(verdict.rationale().contains("Partially verified"), verdict.rationale());
        assertTrue(verdict.rationale().contains("OB-002"), verdict.rationale());
    }

    @Test
    @DisplayName("ADVISORY evidence can never produce FAILED")
    void advisoryEvidenceCannotRefute() {
        // Design decision: model output (and heuristic opinion) is advisory. It may never on its own
        // mark a requirement as failing.
        RequirementVerdict verdict = compute(requirement(R),
                List.of(obligation("OB-001", ObligationKind.INVARIANT)),
                List.of(),
                List.of(evidence("EV-001", EvidenceType.LLM_ANALYSIS, EvidenceStatus.REFUTES,
                        EvidenceConfidence.ADVISORY)),
                List.of());

        assertNotEquals(RequirementStatus.FAILED, verdict.status());
        assertNotEquals(RequirementStatus.VERIFIED, verdict.status());
        assertEquals(RequirementStatus.UNVERIFIED, verdict.status());
        assertFalse(verdict.deterministicEvidence());
    }

    @Test
    @DisplayName("ADVISORY evidence can never produce VERIFIED")
    void advisoryEvidenceCannotVerify() {
        RequirementVerdict verdict = compute(requirement(R),
                List.of(obligation("OB-001", ObligationKind.BOUNDARY_VALUE)),
                List.of(outcome("OB-001", RequirementStatus.VERIFIED)),
                List.of(experiment("EV-001", EvidenceStatus.SUPPORTS, EvidenceConfidence.ADVISORY)),
                List.of());

        assertNotEquals(RequirementStatus.VERIFIED, verdict.status());
        assertEquals(RequirementStatus.PARTIALLY_VERIFIED, verdict.status());
        assertFalse(verdict.deterministicEvidence());
    }

    @Test
    @DisplayName("LLM-generated evidence that was not independently executed is not proof")
    void llmGeneratedEvidenceNotExecutedIndependentlyIsNotProof() {
        Evidence llmEvidence = Evidence.builder("EV-001", EvidenceType.GENERATED_TEST)
                .status(EvidenceStatus.SUPPORTS)
                .confidence(EvidenceConfidence.DETERMINISTIC)
                .description("a model wrote this test")
                .requirement(R)
                .generatedByLlm(true)
                .executedIndependently(false)
                .build();

        RequirementVerdict verdict = compute(requirement(R),
                List.of(obligation("OB-001", ObligationKind.BOUNDARY_VALUE)),
                List.of(outcome("OB-001", RequirementStatus.VERIFIED)),
                List.of(llmEvidence),
                List.of());

        assertNotEquals(RequirementStatus.VERIFIED, verdict.status());
        assertEquals(RequirementStatus.PARTIALLY_VERIFIED, verdict.status());
    }

    @Test
    @DisplayName("LLM-generated evidence that was independently executed is proof")
    void llmGeneratedEvidenceExecutedIndependentlyIsProof() {
        Evidence llmEvidence = Evidence.builder("EV-001", EvidenceType.GENERATED_TEST)
                .status(EvidenceStatus.SUPPORTS)
                .confidence(EvidenceConfidence.DETERMINISTIC)
                .description("a model wrote this test but the engine ran it")
                .requirement(R)
                .generatedByLlm(true)
                .executedIndependently(true)
                .build();

        RequirementVerdict verdict = compute(requirement(R),
                List.of(obligation("OB-001", ObligationKind.BOUNDARY_VALUE)),
                List.of(outcome("OB-001", RequirementStatus.VERIFIED)),
                List.of(llmEvidence),
                List.of());

        assertEquals(RequirementStatus.VERIFIED, verdict.status());
        assertTrue(verdict.deterministicEvidence());
    }

    @Test
    @DisplayName("a MANUAL obligation cannot be discharged and keeps the requirement out of VERIFIED")
    void manualObligationKeepsRequirementOutOfVerified() {
        // Design decision: MANUAL means "the system cannot discharge this automatically". It is never
        // silently dropped; its description is disclosed in unknownAspects so a reader sees exactly what
        // was not checked.
        RequirementVerdict verdict = compute(requirement(R),
                List.of(obligation("OB-001", ObligationKind.MANUAL),
                        obligation("OB-002", ObligationKind.BOUNDARY_VALUE)),
                List.of(outcome("OB-002", RequirementStatus.VERIFIED)),
                List.of(experiment("EV-001", EvidenceStatus.SUPPORTS, EvidenceConfidence.DETERMINISTIC)),
                List.of());

        assertEquals(RequirementStatus.PARTIALLY_VERIFIED, verdict.status());
        assertNotEquals(RequirementStatus.VERIFIED, verdict.status());
        assertTrue(verdict.unknownAspects().stream()
                        .anyMatch(s -> s.contains("OB-001") && s.contains("cannot be discharged automatically")),
                verdict.unknownAspects().toString());
    }

    @Test
    void manualObligationWithAVerifiedOutcomeMustStillBlockVerification() {
        RequirementVerdict verdict = compute(requirement(R),
                List.of(obligation("OB-001", ObligationKind.MANUAL)),
                List.of(outcome("OB-001", RequirementStatus.VERIFIED)),
                List.of(experiment("EV-001", EvidenceStatus.SUPPORTS, EvidenceConfidence.DETERMINISTIC)),
                List.of());

        // Currently FAILS: production trusts the outcome and returns VERIFIED.
        assertNotEquals(RequirementStatus.VERIFIED, verdict.status());
        assertTrue(verdict.unknownAspects().stream().anyMatch(s -> s.contains("OB-001")),
                verdict.unknownAspects().toString());
    }

    @Test
    @DisplayName("an undischarged STATIC obligation is disclosed as an unknown aspect")
    void staticObligationWithoutEvidenceIsDisclosed() {
        RequirementVerdict verdict = compute(requirement(R),
                List.of(obligation("OB-001", ObligationKind.STATIC)),
                List.of(),
                List.of(),
                List.of());

        assertEquals(RequirementStatus.UNVERIFIED, verdict.status());
        assertTrue(verdict.unknownAspects().stream()
                        .anyMatch(s -> s.contains("OB-001") && s.contains("static analysis evidence")),
                verdict.unknownAspects().toString());
    }

    @Test
    @DisplayName("deterministic REFUTES evidence fails the requirement without a counterexample object")
    void deterministicRefutingEvidenceYieldsFailedWithoutCounterexample() {
        RequirementVerdict verdict = compute(requirement(R),
                List.of(obligation("OB-001", ObligationKind.SECURITY)),
                List.of(),
                List.of(evidence("EV-001", EvidenceType.SECURITY_SCAN, EvidenceStatus.REFUTES,
                        EvidenceConfidence.DETERMINISTIC)),
                List.of());

        assertEquals(RequirementStatus.FAILED, verdict.status());
        assertTrue(verdict.rationale().contains("contradicts"), verdict.rationale());
        assertTrue(verdict.counterexampleIds().isEmpty());
        assertTrue(verdict.deterministicEvidence());
    }

    @Test
    @DisplayName("HIGH-confidence refuting evidence is not enough: only DETERMINISTIC evidence refutes")
    void highConfidenceRefutingEvidenceDoesNotFailTheRequirement() {
        // EvidenceConfidence.canRefute() also allows HIGH, but VerdictCalculator deliberately requires
        // deterministic proof for a FAILED verdict. Anything weaker leaves the requirement UNVERIFIED.
        RequirementVerdict verdict = compute(requirement(R),
                List.of(obligation("OB-001", ObligationKind.INVARIANT)),
                List.of(),
                List.of(evidence("EV-001", EvidenceType.STATIC_ANALYSIS, EvidenceStatus.REFUTES,
                        EvidenceConfidence.HIGH)),
                List.of());

        assertEquals(RequirementStatus.UNVERIFIED, verdict.status());
        assertNotEquals(RequirementStatus.FAILED, verdict.status());
    }

    @Test
    @DisplayName("INCONCLUSIVE experiment evidence does not discharge an obligation")
    void inconclusiveExperimentDoesNotVerify() {
        RequirementVerdict verdict = compute(requirement(R),
                List.of(obligation("OB-001", ObligationKind.INVARIANT)),
                List.of(outcome("OB-001", RequirementStatus.VERIFIED)),
                List.of(experiment("EV-001", EvidenceStatus.INCONCLUSIVE, EvidenceConfidence.DETERMINISTIC)),
                List.of());

        assertNotEquals(RequirementStatus.VERIFIED, verdict.status());
        assertEquals(RequirementStatus.PARTIALLY_VERIFIED, verdict.status());
    }

    @Test
    @DisplayName("strategy notes are carried into unknownAspects")
    void strategyNotesAreCarriedIntoUnknownAspects() {
        RequirementVerdict verdict = VerdictCalculator.compute(requirement(R),
                List.of(obligation("OB-001", ObligationKind.BOUNDARY_VALUE)),
                List.of(),
                List.of(),
                List.of(),
                false,
                null,
                List.of("Mutation testing did not run", "no coverage data"));

        assertTrue(verdict.unknownAspects().contains("Mutation testing did not run"),
                verdict.unknownAspects().toString());
        assertTrue(verdict.unknownAspects().contains("no coverage data"), verdict.unknownAspects().toString());
    }

    @Test
    @DisplayName("duplicate unknown aspects are reported once")
    void duplicateUnknownAspectsAreDeduplicated() {
        String note = "Mutation testing did not run";
        RequirementVerdict verdict = VerdictCalculator.compute(requirement(R),
                List.of(obligation("OB-001", ObligationKind.STATIC)),
                List.of(),
                List.of(),
                List.of(),
                false,
                null,
                List.of(note, note, "Obligation OB-001 requires static analysis evidence that was not produced: "
                        + "Description of OB-001"));

        long occurrences = verdict.unknownAspects().stream().filter(note::equals).count();
        assertEquals(1, occurrences, verdict.unknownAspects().toString());
    }

    @Test
    @DisplayName("evidence and counterexample ids are preserved on the verdict")
    void evidenceAndCounterexampleIdsArePreserved() {
        RequirementVerdict verdict = compute(requirement(R),
                List.of(obligation("OB-001", ObligationKind.BOUNDARY_VALUE)),
                List.of(outcome("OB-001", RequirementStatus.UNVERIFIED)),
                List.of(experiment("EV-001", EvidenceStatus.SUPPORTS, EvidenceConfidence.DETERMINISTIC),
                        experiment("EV-002", EvidenceStatus.INCONCLUSIVE, EvidenceConfidence.LOW)),
                List.of(counterexample("CE-007")));

        assertEquals(List.of("EV-001", "EV-002"), verdict.evidenceIds());
        assertEquals(List.of("CE-007"), verdict.counterexampleIds());
        assertEquals(R, verdict.requirementId());
    }

    @Test
    @DisplayName("unknownAspects is never null even when nothing produced notes")
    void unknownAspectsIsNeverNull() {
        RequirementVerdict verdict = VerdictCalculator.compute(requirement(R),
                List.of(obligation("OB-001", ObligationKind.BOUNDARY_VALUE)),
                List.of(outcome("OB-001", RequirementStatus.VERIFIED)),
                List.of(experiment("EV-001", EvidenceStatus.SUPPORTS, EvidenceConfidence.DETERMINISTIC)),
                List.of(),
                false,
                null,
                null);

        assertEquals(List.of(), verdict.unknownAspects());
    }
}
