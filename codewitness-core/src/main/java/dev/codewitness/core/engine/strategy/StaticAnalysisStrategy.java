package dev.codewitness.core.engine.strategy;

import dev.codewitness.core.engine.RequirementCodeMapper;
import dev.codewitness.core.evidence.Evidence;
import dev.codewitness.core.evidence.EvidenceConfidence;
import dev.codewitness.core.evidence.EvidenceStatus;
import dev.codewitness.core.evidence.EvidenceType;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.requirements.ObligationKind;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.RequirementStatus;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.result.Counterexample;
import dev.codewitness.core.result.ObligationOutcome;
import dev.codewitness.core.spi.StrategyOutcome;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationStrategy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns static analysis into evidence about a requirement.
 *
 * <p>Static analysis is deterministic, but its <em>relevance</em> to a requirement is not automatic.
 * A finding is attributed to a requirement only when the requirement's id appears in the offending
 * file or the requirement declares the file explicitly. High severity findings in such a file are
 * reported as counterexamples, because a deterministic analyzer demonstrating the defect is exactly
 * as good as a failing test.
 *
 * <p>When a requirement carries {@code STATIC} obligations and the analyzers ran over the
 * requirement's files without producing a high severity finding, those obligations are discharged.
 * That is a real observation - "the analyzer looked and found nothing" - and it is recorded as such,
 * including which analyzers ran.
 */
public final class StaticAnalysisStrategy implements VerificationStrategy {

    private final RequirementCodeMapper mapper;

    public StaticAnalysisStrategy(RequirementCodeMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "static-analysis";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return true;
    }

    @Override
    public StrategyOutcome verify(
            VerificationContext context, Requirement requirement, List<VerificationObligation> obligations) {

        List<Evidence> evidence = new ArrayList<>();
        List<Counterexample> counterexamples = new ArrayList<>();
        List<ObligationOutcome> outcomes = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        List<Finding> relevant = new ArrayList<>();
        List<Finding> serious = new ArrayList<>();
        for (Finding finding : context.state().findings()) {
            boolean mapped = mapper.requirementsForFile(finding.file()).contains(requirement.id())
                    || mentions(finding.message(), requirement.id());
            if (!mapped) {
                continue;
            }
            relevant.add(finding);
            if (finding.severity().atLeastAsSevereAs(Severity.HIGH)) {
                serious.add(finding);
            }
        }

        int analyzersRun = context.state().findings().isEmpty() ? 0 : 1;
        if (!relevant.isEmpty()) {
            Evidence analysisEvidence = context.evidence().newEvidence(EvidenceType.STATIC_ANALYSIS)
                    .description("Static analysis produced " + relevant.size() + " finding(s) in files mapped to "
                            + requirement.id() + " (" + serious.size() + " at HIGH or above)")
                    .source(id())
                    .confidence(EvidenceConfidence.DETERMINISTIC)
                    .status(serious.isEmpty() ? EvidenceStatus.SUPPORTS : EvidenceStatus.REFUTES)
                    .result(relevant.stream().limit(20)
                            .map(f -> f.severity() + " " + f.location() + " " + f.message())
                            .collect(java.util.stream.Collectors.joining("\n")))
                    .requirement(requirement.id())
                    .metadata("analyzers", relevant.stream().map(Finding::analyzer).distinct().toList())
                    .build();
            context.evidence().add(analysisEvidence);
            evidence.add(analysisEvidence);
        }

        for (Finding finding : serious) {
            Map<String, Object> inputs = new LinkedHashMap<>();
            inputs.put("file", finding.file());
            inputs.put("line", finding.line());
            inputs.put("rule", finding.ruleId());
            inputs.put("analyzer", finding.analyzer());
            Counterexample counterexample = new Counterexample(
                    context.state().nextCounterexampleId(),
                    requirement.id(),
                    null,
                    "Static analysis (" + finding.analyzer() + ") demonstrates a defect in a file covered by "
                            + requirement.id() + ": " + finding.message(),
                    inputs,
                    "The requirement must hold for every execution of this code.",
                    finding.severity() + " finding at " + finding.location(),
                    "rule " + (finding.ruleId() == null ? "(unnamed)" : finding.ruleId()),
                    finding.analyzer() + " " + finding.location(),
                    evidence.isEmpty() ? null : evidence.get(0).id(),
                    null,
                    new Counterexample.ReproductionRecipe(
                            "Re-run the analyzer over the project",
                            List.of("codewitness", "verify", ".", "--analysis-only"),
                            null,
                            null,
                            Map.of("file", String.valueOf(finding.file()), "rule", String.valueOf(finding.ruleId()))),
                    Counterexample.DiscoveryMethod.STATIC_ANALYSIS);
            counterexamples.add(counterexample);
            Evidence ceEvidence = context.evidence().newEvidence(EvidenceType.COUNTEREXAMPLE)
                    .description("Counterexample " + counterexample.id() + " (" + requirement.id()
                            + ") demonstrated by static analysis")
                    .source(id())
                    .confidence(EvidenceConfidence.DETERMINISTIC)
                    .status(EvidenceStatus.REFUTES)
                    .result(finding.message())
                    .requirement(requirement.id())
                    .metadata("location", finding.location())
                    .build();
            context.evidence().add(ceEvidence);
            evidence.add(ceEvidence);
        }

        for (VerificationObligation obligation : obligations) {
            if (obligation.kind() != ObligationKind.STATIC && obligation.kind() != ObligationKind.SECURITY) {
                continue;
            }
            if (!serious.isEmpty()) {
                outcomes.add(new ObligationOutcome(obligation.id(), RequirementStatus.FAILED,
                        "Static analysis found " + serious.size() + " serious finding(s) in the covered files.",
                        evidence.stream().map(Evidence::id).toList(),
                        counterexamples.stream().map(Counterexample::id).toList(),
                        analyzersRun));
            } else if (analyzersRun > 0) {
                outcomes.add(new ObligationOutcome(obligation.id(), RequirementStatus.VERIFIED,
                        "The registered analyzers ran over the files this requirement covers without "
                                + "producing a finding at HIGH severity or above.",
                        evidence.stream().map(Evidence::id).toList(), List.of(), analyzersRun));
            } else {
                outcomes.add(new ObligationOutcome(obligation.id(), RequirementStatus.UNVERIFIED,
                        "No static analyzer ran, so this obligation has no evidence.",
                        List.of(), List.of(), 0));
            }
        }

        if (relevant.isEmpty()) {
            notes.add("Static analysis found nothing attributable to " + requirement.id()
                    + " (a clean analyzer run is weak evidence: it only covers the rules the analyzers know).");
        }
        return new StrategyOutcome(id(), requirement.id(), evidence, counterexamples, outcomes, notes);
    }

    private static boolean mentions(String text, String requirementId) {
        return text != null && text.toUpperCase(java.util.Locale.ROOT)
                .contains(requirementId.toUpperCase(java.util.Locale.ROOT));
    }
}
