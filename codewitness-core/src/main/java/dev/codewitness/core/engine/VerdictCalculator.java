package dev.codewitness.core.engine;

import dev.codewitness.core.evidence.Evidence;
import dev.codewitness.core.evidence.EvidenceStatus;
import dev.codewitness.core.evidence.EvidenceType;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.RequirementStatus;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.result.Counterexample;
import dev.codewitness.core.result.ObligationOutcome;
import dev.codewitness.core.result.RequirementVerdict;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes requirement verdicts from evidence.
 *
 * <p>This class is the place where CodeWitness can fail to be honest, so the rules are deliberately
 * simple, explicit and conservative:
 *
 * <ul>
 *   <li>a counterexample observed by execution makes the requirement {@code FAILED};</li>
 *   <li>a failed build makes it {@code BLOCKED}, never {@code FAILED} - a broken build is not a
 *       statement about the code;</li>
 *   <li>{@code VERIFIED} requires that <em>every</em> obligation is verified, that at least one
 *       experiment was actually executed, and that no obligation is of a kind that cannot be
 *       automated;</li>
 *   <li>insufficient evidence yields {@code UNVERIFIED}, and the reason is stated;</li>
 *   <li>advisory evidence (language model output, heuristics) never on its own reaches
 *       {@code VERIFIED}.</li>
 * </ul>
 */
public final class VerdictCalculator {

    /** Evidence types that represent an experiment CodeWitness actually executed. */
    private static final Set<EvidenceType> EXPERIMENT_TYPES = Set.of(
            EvidenceType.GENERATED_TEST, EvidenceType.PROPERTY_TEST, EvidenceType.FUZZ_TEST);

    private VerdictCalculator() {
    }

    /**
     * Computes the verdict for one requirement.
     *
     * @param requirement         requirement being judged
     * @param obligations         obligations that were planned
     * @param outcomes            per-obligation outcomes produced by the strategies
     * @param requirementEvidence all evidence tagged with this requirement
     * @param counterexamples     counterexamples observed for this requirement
     * @param buildBlocked        {@code true} when the project could not be built
     * @param buildBlockReason    explanation when {@code buildBlocked} is {@code true}
     * @param strategyNotes       things the strategies could not do
     * @return the verdict
     */
    public static RequirementVerdict compute(
            Requirement requirement,
            List<VerificationObligation> obligations,
            List<ObligationOutcome> outcomes,
            List<Evidence> requirementEvidence,
            List<Counterexample> counterexamples,
            boolean buildBlocked,
            String buildBlockReason,
            List<String> strategyNotes) {

        List<String> unknownAspects = new ArrayList<>(strategyNotes == null ? List.of() : strategyNotes);
        List<String> evidenceIds = requirementEvidence.stream().map(Evidence::id).toList();
        List<String> counterexampleIds = counterexamples.stream().map(Counterexample::id).toList();
        boolean deterministic = requirementEvidence.stream().anyMatch(Evidence::qualifiesAsDeterministicProof);

        if (buildBlocked) {
            // The ordering is deliberate and documented: a project that does not build cannot be verified,
            // so BLOCKED outranks FAILED. What must not happen is that a counterexample the run actually
            // observed disappears from the report because a sibling language failed to build, so the reason
            // names what was found even though it does not decide the verdict.
            String observed = counterexamples.isEmpty() ? ""
                    : " An executed experiment did record " + counterexamples.size()
                            + " counterexample(s) for this requirement (see "
                            + String.join(", ", counterexampleIds) + "); they are reported but do not decide "
                            + "this verdict, because a project that does not build cannot be verified.";
            return new RequirementVerdict(requirement.id(), RequirementStatus.BLOCKED,
                    "The project could not be built, so no verification could be attempted. "
                            + "A failed build is not evidence that the code is incorrect. Reason: "
                            + buildBlockReason + observed,
                    evidenceIds, outcomes, unknownAspects, counterexampleIds, deterministic);
        }

        if (!counterexamples.isEmpty()) {
            Counterexample first = counterexamples.getFirst();
            return new RequirementVerdict(requirement.id(), RequirementStatus.FAILED,
                    "A counterexample reproduced by execution violates this requirement: " + first.summary()
                            + " (see " + first.id() + ")",
                    evidenceIds, outcomes, unknownAspects, counterexampleIds, deterministic);
        }

        List<Evidence> refuting = requirementEvidence.stream()
                .filter(e -> e.status() == EvidenceStatus.REFUTES)
                .filter(Evidence::qualifiesAsDeterministicProof)
                .toList();
        if (!refuting.isEmpty()) {
            return new RequirementVerdict(requirement.id(), RequirementStatus.FAILED,
                    "Deterministic evidence contradicts this requirement: " + refuting.getFirst().description(),
                    evidenceIds, outcomes, unknownAspects, counterexampleIds, true);
        }

        List<Evidence> experiments = requirementEvidence.stream()
                .filter(e -> EXPERIMENT_TYPES.contains(e.type()))
                .filter(e -> e.status() == EvidenceStatus.SUPPORTS)
                .filter(Evidence::qualifiesAsDeterministicProof)
                .toList();

        Map<String, ObligationOutcome> byObligation = new LinkedHashMap<>();
        for (ObligationOutcome outcome : outcomes) {
            byObligation.put(outcome.obligationId(), outcome);
        }

        Set<String> unverifiedObligations = new LinkedHashSet<>();
        for (VerificationObligation obligation : obligations) {
            ObligationOutcome outcome = byObligation.get(obligation.id());
            boolean automatable = obligation.kind() != dev.codewitness.core.requirements.ObligationKind.MANUAL;
            if (!automatable || outcome == null || outcome.status() != RequirementStatus.VERIFIED) {
                unverifiedObligations.add(obligation.id());
                if (obligation.kind() == dev.codewitness.core.requirements.ObligationKind.MANUAL) {
                    // A manual obligation is not automatable, so no outcome - not even one claiming
                    // success - may discharge it. It stays visible as an unknown aspect instead.
                    unknownAspects.add("Obligation " + obligation.id() + " cannot be discharged automatically: "
                            + obligation.description());
                } else if (obligation.kind() == dev.codewitness.core.requirements.ObligationKind.STATIC) {
                    unknownAspects.add("Obligation " + obligation.id()
                            + " requires static analysis evidence that was not produced: " + obligation.description());
                }
            }
        }

        if (experiments.isEmpty() && unverifiedObligations.size() == obligations.size()) {
            String reason = obligations.isEmpty()
                    ? "No verification obligations were derived for this requirement."
                    : "CodeWitness found no way to execute this requirement: every planned obligation is "
                            + "still unverified. The requirement must not be treated as passing.";
            return new RequirementVerdict(requirement.id(), RequirementStatus.UNVERIFIED, reason,
                    evidenceIds, outcomes, distinct(unknownAspects), counterexampleIds, deterministic);
        }

        if (unverifiedObligations.isEmpty() && !experiments.isEmpty()) {
            return new RequirementVerdict(requirement.id(), RequirementStatus.VERIFIED,
                    "Every planned obligation was discharged by executed experiments ("
                            + experiments.size() + " experiment evidence records, evidence "
                            + experiments.getFirst().id() + " onward).",
                    evidenceIds, outcomes, distinct(unknownAspects), counterexampleIds, deterministic);
        }

        String rationale = experiments.isEmpty()
                ? "Some obligations were addressed but no executed experiment supports this requirement yet; "
                        + unverifiedObligations.size() + " of " + obligations.size() + " obligations remain unverified."
                : "Partially verified: " + unverifiedObligations.size() + " of " + obligations.size()
                        + " obligations remain unverified, including "
                        + String.join(", ", unverifiedObligations.stream().limit(3).toList()) + ".";
        return new RequirementVerdict(requirement.id(), RequirementStatus.PARTIALLY_VERIFIED, rationale,
                evidenceIds, outcomes, distinct(unknownAspects), counterexampleIds, deterministic);
    }

    private static List<String> distinct(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }
}
