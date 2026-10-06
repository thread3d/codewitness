package dev.codewitness.core.evidence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Evidence is the atom of the system. The one rule that matters most here:
 * {@code qualifiesAsDeterministicProof()} is false for a model's opinion, and also for a model's
 * test until an independent execution has actually run it.
 */
class EvidenceTest {

    private static Evidence fullyPopulated() {
        return Evidence.builder("EV-001", EvidenceType.TEST)
                .runId("run-1")
                .status(EvidenceStatus.SUPPORTS)
                .confidence(EvidenceConfidence.DETERMINISTIC)
                .description("the mapped test passes")
                .source("existing-tests")
                .timestamp(Instant.parse("2026-01-01T00:00:00Z"))
                .command(List.of("mvn", "test"))
                .input("seed", 42)
                .inputs(Map.of("cases", 10))
                .output("Tests run: 3")
                .result("pass")
                .metadata("suite", "LedgerTest")
                .artifact(Path.of("/tmp/report.xml"))
                .requirement("R-001")
                .obligation("OB-001")
                .generatedByLlm(false)
                .executedIndependently(true)
                .reproducible(true)
                .reproductionCommand("mvn -Dtest=LedgerTest test")
                .build();
    }

    @Test
    @DisplayName("advisory evidence is never deterministic proof")
    void advisoryEvidenceIsNotProof() {
        Evidence advisory = Evidence.builder("EV-001", EvidenceType.LLM_ANALYSIS)
                .confidence(EvidenceConfidence.ADVISORY)
                .status(EvidenceStatus.SUPPORTS)
                .build();

        assertFalse(advisory.qualifiesAsDeterministicProof());
        assertFalse(EvidenceConfidence.ADVISORY.isDeterministic());
    }

    @Test
    @DisplayName("LLM-generated evidence that was not independently executed is not proof")
    void llmGeneratedEvidenceNotExecutedIsNotProof() {
        Evidence evidence = Evidence.builder("EV-001", EvidenceType.GENERATED_TEST)
                .confidence(EvidenceConfidence.DETERMINISTIC)
                .status(EvidenceStatus.SUPPORTS)
                .generatedByLlm(true)
                .executedIndependently(false)
                .build();

        assertFalse(evidence.qualifiesAsDeterministicProof());
    }

    @Test
    @DisplayName("LLM-generated evidence that was independently executed is proof")
    void llmGeneratedEvidenceExecutedIsProof() {
        Evidence evidence = Evidence.builder("EV-001", EvidenceType.GENERATED_TEST)
                .confidence(EvidenceConfidence.DETERMINISTIC)
                .status(EvidenceStatus.SUPPORTS)
                .generatedByLlm(true)
                .executedIndependently(true)
                .build();

        assertTrue(evidence.qualifiesAsDeterministicProof());
    }

    @Test
    @DisplayName("deterministic evidence built without an LLM is proof")
    void plainDeterministicEvidenceIsProof() {
        Evidence evidence = Evidence.builder("EV-001", EvidenceType.COMPILER)
                .confidence(EvidenceConfidence.DETERMINISTIC)
                .build();

        assertTrue(evidence.qualifiesAsDeterministicProof());
    }

    @Test
    @DisplayName("default confidence is MEDIUM, which is not proof")
    void defaultConfidenceIsMedium() {
        Evidence evidence = Evidence.builder("EV-001", EvidenceType.GENERATED_TEST).build();

        assertEquals(EvidenceConfidence.MEDIUM, evidence.confidence());
        assertFalse(evidence.qualifiesAsDeterministicProof());
    }

    @Test
    @DisplayName("only DETERMINISTIC confidence is deterministic; HIGH is not")
    void onlyDeterministicConfidenceCounts() {
        assertTrue(EvidenceConfidence.DETERMINISTIC.isDeterministic());
        assertFalse(EvidenceConfidence.HIGH.isDeterministic());
        assertFalse(EvidenceConfidence.MEDIUM.isDeterministic());
        assertFalse(EvidenceConfidence.LOW.isDeterministic());
        assertTrue(EvidenceConfidence.DETERMINISTIC.canRefute());
        assertTrue(EvidenceConfidence.HIGH.canRefute());
        assertFalse(EvidenceConfidence.MEDIUM.canRefute());
        assertFalse(EvidenceConfidence.ADVISORY.canRefute());
    }

    @Test
    @DisplayName("withRequirement preserves every other field")
    void withRequirementPreservesEverythingElse() {
        Evidence original = fullyPopulated();

        Evidence tagged = original.withRequirement("R-002");

        assertEquals(original.id(), tagged.id());
        assertEquals(original.runId(), tagged.runId());
        assertEquals(original.type(), tagged.type());
        assertEquals(original.status(), tagged.status());
        assertEquals(original.confidence(), tagged.confidence());
        assertEquals(original.description(), tagged.description());
        assertEquals(original.timestamp(), tagged.timestamp());
        assertEquals(original.command(), tagged.command());
        assertEquals(original.inputs(), tagged.inputs());
        assertEquals(original.output(), tagged.output());
        assertEquals(original.result(), tagged.result());
        assertEquals(original.metadata(), tagged.metadata());
        assertEquals(original.artifacts(), tagged.artifacts());
        assertEquals(original.obligationIds(), tagged.obligationIds());
        assertEquals(original.generatedByLlm(), tagged.generatedByLlm());
        assertEquals(original.executedIndependently(), tagged.executedIndependently());
        assertEquals(original.reproductionCommand(), tagged.reproductionCommand());
        assertEquals(List.of("R-001", "R-002"), tagged.requirementIds());
    }

    @Test
    @DisplayName("withRequirement does not duplicate an id that is already present")
    void withRequirementDoesNotDuplicate() {
        Evidence tagged = fullyPopulated().withRequirement("R-001");

        assertEquals(List.of("R-001"), tagged.requirementIds());
    }

    @Test
    @DisplayName("withObligation preserves every other field")
    void withObligationPreservesEverythingElse() {
        Evidence original = fullyPopulated();

        Evidence tagged = original.withObligation("OB-002");

        assertEquals(original.id(), tagged.id());
        assertEquals(original.description(), tagged.description());
        assertEquals(original.command(), tagged.command());
        assertEquals(original.requirementIds(), tagged.requirementIds());
        assertEquals(original.metadata(), tagged.metadata());
        assertEquals(List.of("OB-001", "OB-002"), tagged.obligationIds());
    }

    @Test
    @DisplayName("withObligation does not duplicate an id that is already present")
    void withObligationDoesNotDuplicate() {
        assertEquals(List.of("OB-001"), fullyPopulated().withObligation("OB-001").obligationIds());
    }

    @Test
    @DisplayName("with-ers return new instances and leave the original untouched")
    void withErsAreImmutable() {
        Evidence original = fullyPopulated();

        Evidence tagged = original.withRequirement("R-002");

        assertTrue(tagged != original);
        assertEquals(List.of("R-001"), original.requirementIds());
        assertEquals(List.of("R-001", "R-002"), tagged.requirementIds());
    }

    @Test
    @DisplayName("collections and maps are defensively copied and immutable")
    void collectionsAreImmutableCopies() {
        Evidence original = fullyPopulated();

        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> original.command().add("rm -rf /"));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> original.metadata().put("x", "y"));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> original.requirementIds().add("R-003"));
    }

    @Test
    @DisplayName("builder defaults are explicit and safe")
    void builderDefaultsAreExplicit() {
        Evidence evidence = Evidence.builder("EV-001", EvidenceType.PROJECT_INSPECTION).build();

        assertEquals(EvidenceStatus.INCONCLUSIVE, evidence.status());
        assertEquals("codewitness", evidence.source());
        assertEquals("", evidence.output());
        assertEquals("", evidence.result());
        assertEquals(List.of(), evidence.command());
        assertEquals(List.of(), evidence.requirementIds());
        assertEquals(List.of(), evidence.obligationIds());
        assertTrue(evidence.executedIndependently());
        assertTrue(evidence.reproducible());
        assertFalse(evidence.generatedByLlm());
        assertTrue(evidence.timestamp() != null);
    }

    @Test
    @DisplayName("toString names the evidence for logs")
    void toStringNamesEvidence() {
        String text = fullyPopulated().toString();

        assertTrue(text.contains("EV-001"), text);
        assertTrue(text.contains("TEST"), text);
        assertTrue(text.contains("DETERMINISTIC"), text);
    }

    @Test
    @DisplayName("an EvidenceLedger assigns sequential ids and can filter by requirement")
    void ledgerAssignsIdsAndFilters() {
        EvidenceLedger ledger = new EvidenceLedger("run-1");

        Evidence first = ledger.add(ledger.newEvidence(EvidenceType.COMPILER).description("build ok").build());
        Evidence second = ledger.add(ledger.newEvidence(EvidenceType.TEST)
                .description("tests pass").requirement("R-001").build());

        assertEquals("EV-001", first.id());
        assertEquals("EV-002", second.id());
        assertEquals("run-1", first.runId());
        assertEquals(2, ledger.size());
        assertEquals(2, ledger.all().size());
        assertEquals(List.of(second), ledger.forRequirement("R-001"));
        assertEquals(1, ledger.byType(EvidenceType.TEST).size());
        assertEquals("EV-003", ledger.nextId());
    }
}
