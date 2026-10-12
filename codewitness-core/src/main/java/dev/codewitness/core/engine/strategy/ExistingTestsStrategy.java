package dev.codewitness.core.engine.strategy;

import dev.codewitness.core.evidence.Evidence;
import dev.codewitness.core.evidence.EvidenceConfidence;
import dev.codewitness.core.evidence.EvidenceStatus;
import dev.codewitness.core.evidence.EvidenceType;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.result.Counterexample;
import dev.codewitness.core.result.ObligationOutcome;
import dev.codewitness.core.spi.RequirementCodeMap;
import dev.codewitness.core.spi.StrategyOutcome;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationProviderRegistry;
import dev.codewitness.core.spi.VerificationStrategy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Uses the project's existing test suite as evidence.
 *
 * <p>Existing tests are strong evidence when they <em>fail</em> and weak evidence when they pass,
 * which is exactly how they are treated here:
 *
 * <ul>
 *   <li>a failing test whose source mentions the requirement produces a reproducible
 *       counterexample;</li>
 *   <li>a passing mapped test produces supporting evidence with {@code HIGH} confidence, but it is
 *       deliberately not counted as a deterministic experiment, so a requirement whose only support
 *       is "the existing tests pass" stays {@code UNVERIFIED}.</li>
 * </ul>
 *
 * <p>That asymmetry is the whole point: tests written by the same process that wrote the code are
 * not independent verification.
 */
public final class ExistingTestsStrategy implements VerificationStrategy {

    private final VerificationProviderRegistry providers;

    public ExistingTestsStrategy(VerificationProviderRegistry providers) {
        this.providers = providers == null ? VerificationProviderRegistry.empty() : providers;
    }

    @Override
    public String id() {
        return "existing-tests";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.testSourceDirs() != null && !project.testSourceDirs().isEmpty();
    }

    @Override
    public StrategyOutcome verify(
            VerificationContext context, Requirement requirement, List<VerificationObligation> obligations) {

        List<Evidence> evidence = new ArrayList<>();
        List<Counterexample> counterexamples = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<ObligationOutcome> outcomes = new ArrayList<>();

        TestResult tests = context.state().existingTests();
        RequirementCodeMap mapper = context.codeMap();
        List<String> mappedClasses = mapper.testClassesFor(requirement.id());

        if (!tests.executed()) {
            notes.add("Existing test results are unavailable: " + tests.note());
            return new StrategyOutcome(id(), requirement.id(), evidence, counterexamples, outcomes, notes);
        }

        if (mappedClasses.isEmpty()) {
            notes.add("No existing test mentions " + requirement.id()
                    + ", so the existing suite provides no requirement specific evidence. "
                    + "CodeWitness will not infer correctness of " + requirement.id()
                    + " from unrelated passing tests.");
            Evidence global = context.evidence().newEvidence(EvidenceType.TEST)
                    .description("Existing test suite executed, but no test is mapped to " + requirement.id())
                    .source(id())
                    .confidence(EvidenceConfidence.DETERMINISTIC)
                    .status(EvidenceStatus.INCONCLUSIVE)
                    .result(tests.describe())
                    .requirement(requirement.id())
                    .metadata("mapped_test_classes", List.of())
                    .build();
            context.evidence().add(global);
            evidence.add(global);
            return new StrategyOutcome(id(), requirement.id(), evidence, counterexamples, outcomes, notes);
        }

        List<TestCaseResult> failedMapped = new ArrayList<>();
        List<TestCaseResult> passedMapped = new ArrayList<>();
        for (TestCaseResult testCase : tests.cases()) {
            String owner = mapper.requirementForTestClass(testCase.suite()).orElse(null);
            if (owner != null && owner.equals(requirement.id())) {
                if (testCase.outcome().isFailure()) {
                    failedMapped.add(testCase);
                } else if (testCase.outcome() == dev.codewitness.core.model.TestOutcome.PASSED) {
                    passedMapped.add(testCase);
                }
            }
        }

        Evidence evidenceRecord = context.evidence().newEvidence(EvidenceType.TEST)
                .description("Existing tests mapped to " + requirement.id() + ": " + mappedClasses.size()
                        + " classes, " + passedMapped.size() + " passing, " + failedMapped.size() + " failing")
                .source(id())
                .confidence(EvidenceConfidence.HIGH)
                .status(failedMapped.isEmpty() ? EvidenceStatus.SUPPORTS : EvidenceStatus.REFUTES)
                .result(tests.describeWithNote())
                .requirement(requirement.id())
                .metadata("mapped_test_classes", mappedClasses)
                .metadata("passing", passedMapped.stream().map(TestCaseResult::displayName).toList())
                .metadata("failing", failedMapped.stream().map(TestCaseResult::displayName).toList())
                .metadata("relevance_basis", "the mapped test source mentions the requirement id")
                .build();
        context.evidence().add(evidenceRecord);
        evidence.add(evidenceRecord);

        for (TestCaseResult failure : failedMapped) {
            Map<String, Object> inputs = new LinkedHashMap<>();
            inputs.put("test", failure.displayName());
            // The replay command is language specific, so it comes from the adapter's test runner.
            // When no runner can produce one, the recipe keeps an empty command and the report says
            // nothing it cannot back up rather than printing a Maven command for a Python project.
            List<String> replay = providers.testRunners(context.projectInfo()).stream()
                    .map(runner -> runner.replayCommand(context, failure))
                    .filter(command -> command != null && !command.isEmpty())
                    .findFirst()
                    .orElse(List.of());
            Counterexample counterexample = new Counterexample(
                    context.state().nextCounterexampleId(),
                    requirement.id(),
                    null,
                    "Existing test " + failure.displayName() + " fails: "
                            + firstLine(failure.failureMessage()),
                    inputs,
                    "The mapped test asserts that the requirement holds.",
                    firstLine(failure.failureMessage()),
                    failure.failureType() == null ? "assertion" : failure.failureType(),
                    failure.displayName(),
                    evidenceRecord.id(),
                    null,
                    new Counterexample.ReproductionRecipe(
                            "Re-run the failing test in the project",
                            replay,
                            null,
                            null,
                            Map.of()),
                    Counterexample.DiscoveryMethod.EXISTING_TEST);
            counterexamples.add(counterexample);

            Evidence ceEvidence = context.evidence().newEvidence(EvidenceType.COUNTEREXAMPLE)
                    .description("Counterexample " + counterexample.id() + " (" + requirement.id()
                            + ") observed by existing test " + failure.displayName())
                    .source(id())
                    .confidence(EvidenceConfidence.DETERMINISTIC)
                    .status(EvidenceStatus.REFUTES)
                    .output(truncate(failure.stackTrace(), 4000))
                    .result("test failed")
                    .requirement(requirement.id())
                    .metadata("test", failure.displayName())
                    .build();
            context.evidence().add(ceEvidence);
            evidence.add(ceEvidence);
        }

        if (failedMapped.isEmpty()) {
            // Report against the requirement's own obligations. This used to be keyed by a test class
            // name, which is not an obligation id, so the outcome could never be merged into the
            // obligation table: "the mapped tests pass" showed up as evidence while the obligations it
            // bears on stayed silently unaddressed. A passing mapped test supports an obligation; it
            // never discharges one, because tests written by the same process that wrote the code are
            // not independent verification.
            for (VerificationObligation obligation : obligations) {
                if (obligation.kind() == dev.codewitness.core.requirements.ObligationKind.MANUAL) {
                    continue;
                }
                outcomes.add(new ObligationOutcome(
                        obligation.id(),
                        dev.codewitness.core.requirements.RequirementStatus.PARTIALLY_VERIFIED,
                        "The " + mappedClasses.size() + " test class(es) mapped to " + requirement.id()
                                + " pass, which supports this obligation but does not discharge it.",
                        List.of(evidenceRecord.id()),
                        List.of(),
                        passedMapped.size()));
            }
        }
        return new StrategyOutcome(id(), requirement.id(), evidence, counterexamples, List.copyOf(outcomes), notes);
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        int newline = text.indexOf('\n');
        String line = newline < 0 ? text : text.substring(0, newline);
        return line.length() > 300 ? line.substring(0, 300) + "..." : line;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "\n[... truncated]";
    }
}
