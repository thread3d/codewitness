package dev.codewitness.core.engine.strategy;

import dev.codewitness.core.evidence.Evidence;
import dev.codewitness.core.evidence.EvidenceConfidence;
import dev.codewitness.core.evidence.EvidenceStatus;
import dev.codewitness.core.evidence.EvidenceType;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.SurvivingMutant;
import dev.codewitness.core.requirements.ObligationOrigin;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.RequirementStatus;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.result.Counterexample;
import dev.codewitness.core.result.ObligationOutcome;
import dev.codewitness.core.spi.RequirementCodeMap;
import dev.codewitness.core.spi.StrategyOutcome;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * Uses mutation testing to judge how much a passing test suite actually constrains behaviour.
 *
 * <p>A surviving mutant is not proof that a requirement is violated - the mutation may be equivalent,
 * or simply untested behaviour nobody claimed. It is proof that <em>nobody checked</em>. So a
 * requirement whose covered files contain surviving mutants is downgraded to
 * {@code PARTIALLY_VERIFIED} with the surviving mutants listed, never marked
 * {@code VERIFIED} on the strength of tests that cannot tell the difference.
 */
public final class MutationStrategy implements VerificationStrategy {

    @Override
    public String id() {
        return "mutation-testing";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return true;
    }

    @Override
    public StrategyOutcome verify(
            VerificationContext context, Requirement requirement, List<VerificationObligation> obligations) {

        List<Evidence> evidence = new ArrayList<>();
        List<ObligationOutcome> outcomes = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        MutationReport report = context.state().mutation();

        if (!report.measured()) {
            notes.add("Mutation testing did not run (" + report.note() + "), so CodeWitness cannot say how "
                    + "strong the tests that touch " + requirement.id() + " are.");
            return new StrategyOutcome(id(), requirement.id(), evidence, List.of(), outcomes, notes);
        }

        List<SurvivingMutant> relevant = new ArrayList<>();
        RequirementCodeMap mapper = context.codeMap();
        for (SurvivingMutant mutant : report.survivors()) {
            if (mapper.requirementsForFile(mutant.className() == null ? "" : mutant.className().replace('.', '/'))
                    .contains(requirement.id())
                    || mentions(mutant, requirement.id())) {
                relevant.add(mutant);
            }
        }

        Evidence mutationEvidence = context.evidence().newEvidence(EvidenceType.MUTATION_TEST)
                .description("Mutation testing: " + report.describe()
                        + (relevant.isEmpty() ? "" : "; " + relevant.size() + " surviving mutant(s) in code "
                        + "covered by " + requirement.id()))
                .source(id())
                .confidence(EvidenceConfidence.DETERMINISTIC)
                .status(relevant.isEmpty() ? EvidenceStatus.SUPPORTS : EvidenceStatus.INCONCLUSIVE)
                .result(report.describe())
                .requirement(requirement.id())
                .metadata("survivors", relevant.stream().limit(25)
                        .map(m -> m.location() + " -> " + m.description()).toList())
                .metadata("score_percent", report.scorePercent())
                .artifacts(report.reports())
                .build();
        context.evidence().add(mutationEvidence);
        evidence.add(mutationEvidence);

        for (VerificationObligation obligation : obligations) {
            if (obligation.origin() != ObligationOrigin.ANALYZER_DERIVED) {
                continue;
            }
            if (relevant.isEmpty()) {
                outcomes.add(new ObligationOutcome(obligation.id(), RequirementStatus.VERIFIED,
                        "Every mutant generated in the code this requirement covers was killed by the tests.",
                        List.of(mutationEvidence.id()), List.of(), report.generated()));
            } else {
                outcomes.add(new ObligationOutcome(obligation.id(), RequirementStatus.UNVERIFIED,
                        relevant.size() + " mutant(s) survived, which means no test distinguishes the mutated "
                                + "behaviour from the original. CodeWitness will not treat this requirement as "
                                + "verified while that is true. Weakest spots: "
                                + relevant.stream().limit(3).map(SurvivingMutant::location)
                                .collect(java.util.stream.Collectors.joining(", ")),
                        List.of(mutationEvidence.id()), List.of(), report.generated()));
            }
        }
        return new StrategyOutcome(id(), requirement.id(), evidence, List.of(), outcomes, notes);
    }

    private static boolean mentions(SurvivingMutant mutant, String requirementId) {
        String needle = requirementId.toUpperCase(java.util.Locale.ROOT);
        return (mutant.description() != null && mutant.description().toUpperCase(java.util.Locale.ROOT).contains(needle))
                || (mutant.className() != null && mutant.className().toUpperCase(java.util.Locale.ROOT).contains(needle));
    }
}
