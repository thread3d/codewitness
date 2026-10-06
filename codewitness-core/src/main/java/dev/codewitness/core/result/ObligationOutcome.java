package dev.codewitness.core.result;

import dev.codewitness.core.requirements.RequirementStatus;

import java.util.List;

/**
 * The outcome of attempting a single verification obligation.
 *
 * @param obligationId      the obligation that was attempted
 * @param status            resulting status
 * @param rationale         why this status was assigned
 * @param evidenceIds       evidence produced by the attempt
 * @param counterexampleIds counterexamples discovered while attempting it
 * @param attempts          number of experiments run for this obligation
 */
public record ObligationOutcome(
        String obligationId,
        RequirementStatus status,
        String rationale,
        List<String> evidenceIds,
        List<String> counterexampleIds,
        int attempts) {

    public ObligationOutcome {
        evidenceIds = List.copyOf(evidenceIds == null ? List.of() : evidenceIds);
        counterexampleIds = List.copyOf(counterexampleIds == null ? List.of() : counterexampleIds);
    }

    public static ObligationOutcome unverified(String obligationId, String rationale) {
        return new ObligationOutcome(obligationId, RequirementStatus.UNVERIFIED, rationale, List.of(), List.of(), 0);
    }
}
