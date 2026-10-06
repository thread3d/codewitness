package dev.codewitness.core.result;

import dev.codewitness.core.requirements.RequirementStatus;

import java.util.List;

/**
 * The verdict for one requirement, with the evidence chain that justifies it.
 *
 * @param requirementId          requirement this verdict is about
 * @param status                 final status
 * @param rationale              human readable explanation of the status
 * @param evidenceIds            every piece of evidence considered
 * @param obligations            per-obligation outcomes
 * @param unknownAspects         things that remain unknown and are visible in the report
 * @param counterexampleIds      counterexamples attached to this requirement
 * @param deterministicEvidence  {@code true} when at least one piece of deterministic evidence exists
 */
public record RequirementVerdict(
        String requirementId,
        RequirementStatus status,
        String rationale,
        List<String> evidenceIds,
        List<ObligationOutcome> obligations,
        List<String> unknownAspects,
        List<String> counterexampleIds,
        boolean deterministicEvidence) {

    public RequirementVerdict {
        evidenceIds = List.copyOf(evidenceIds == null ? List.of() : evidenceIds);
        obligations = List.copyOf(obligations == null ? List.of() : obligations);
        unknownAspects = List.copyOf(unknownAspects == null ? List.of() : unknownAspects);
        counterexampleIds = List.copyOf(counterexampleIds == null ? List.of() : counterexampleIds);
    }
}
