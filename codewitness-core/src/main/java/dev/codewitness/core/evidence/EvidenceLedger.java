package dev.codewitness.core.evidence;

import java.util.ArrayList;
import java.util.List;

/**
 * Assigns evidence identifiers and keeps the evidence produced by a run.
 *
 * <p>Identifiers are sequential and stable within a run ({@code EV-001}, {@code EV-002}, ...) so that
 * a report can be read, and a counterexample can be cited, without ambiguity.
 */
public final class EvidenceLedger {

    private final String runId;
    private final List<Evidence> evidence = new ArrayList<>();

    public EvidenceLedger(String runId) {
        this.runId = runId;
    }

    public String runId() {
        return runId;
    }

    /** @return the next evidence identifier. */
    public String nextId() {
        return String.format(java.util.Locale.ROOT, "EV-%03d", evidence.size() + 1);
    }

    /** @return a builder pre-filled with the next id, the run id and the current timestamp. */
    public Evidence.Builder newEvidence(EvidenceType type) {
        return Evidence.builder(nextId(), type)
                .runId(runId)
                .timestamp(java.time.Instant.now());
    }

    /** Stores evidence and returns it. */
    public Evidence add(Evidence item) {
        evidence.add(item);
        return item;
    }

    /** Stores several pieces of evidence. */
    public List<Evidence> addAll(List<Evidence> items) {
        evidence.addAll(items);
        return items;
    }

    public List<Evidence> all() {
        return List.copyOf(evidence);
    }

    public int size() {
        return evidence.size();
    }

    public List<Evidence> byType(EvidenceType type) {
        return evidence.stream().filter(e -> e.type() == type).toList();
    }

    public List<Evidence> forRequirement(String requirementId) {
        return evidence.stream().filter(e -> e.requirementIds().contains(requirementId)).toList();
    }
}
