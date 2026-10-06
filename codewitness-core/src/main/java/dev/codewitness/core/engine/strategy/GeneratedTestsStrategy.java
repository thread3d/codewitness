package dev.codewitness.core.engine.strategy;

import dev.codewitness.core.engine.CounterexampleProtocol;
import dev.codewitness.core.evidence.Evidence;
import dev.codewitness.core.evidence.EvidenceConfidence;
import dev.codewitness.core.evidence.EvidenceStatus;
import dev.codewitness.core.evidence.EvidenceType;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.requirements.ObligationKind;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.RequirementStatus;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.result.Counterexample;
import dev.codewitness.core.result.ObligationOutcome;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.GeneratedTestExecution;
import dev.codewitness.core.spi.StrategyOutcome;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationProviderRegistry;
import dev.codewitness.core.spi.VerificationStrategy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Generates additional tests for a requirement's obligations, executes them, and turns failures into
 * counterexamples.
 *
 * <p>This is the only strategy that can make a requirement {@code VERIFIED}, and it can only do so
 * by running something. Generated sources are compiled and executed by the project's own build tool
 * through a {@link dev.codewitness.core.spi.VerificationTools.GeneratedTestExecutor}; the strategy
 * itself never claims a result it did not observe.
 */
public final class GeneratedTestsStrategy implements VerificationStrategy {

    private final VerificationProviderRegistry providers;

    public GeneratedTestsStrategy(VerificationProviderRegistry providers) {
        this.providers = providers;
    }

    @Override
    public String id() {
        return "generated-tests";
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

        if (!context.config().propertyTesting().enabled()) {
            notes.add("Generated tests are disabled by configuration, so no executed experiment "
                    + "supports " + requirement.id() + ".");
            return new StrategyOutcome(id(), requirement.id(), evidence, counterexamples, outcomes, notes);
        }

        // Obligations that only static analysis can discharge are left to that strategy: emitting a
        // "no scenario could test this" outcome here would overwrite the analyzer's finding with a
        // weaker one and make an already-refuted obligation look merely unverified.
        List<VerificationObligation> attemptable = obligations.stream()
                .filter(o -> o.kind() != ObligationKind.MANUAL
                        && o.kind() != ObligationKind.STATIC
                        && o.kind() != ObligationKind.SECURITY)
                .toList();
        if (attemptable.isEmpty()) {
            notes.add("Every obligation of " + requirement.id()
                    + " is non-automatable, so CodeWitness cannot execute anything for it.");
            return new StrategyOutcome(id(), requirement.id(), evidence, counterexamples, outcomes, notes);
        }

        if (!context.budget().canGenerateTests()) {
            notes.add("The verification budget was exhausted before generated tests for "
                    + requirement.id() + " could run (" + context.budget().snapshot().exhaustedReason() + ").");
            return new StrategyOutcome(id(), requirement.id(), evidence, counterexamples, outcomes, notes);
        }

        var generators = providers.testGenerators(context.projectInfo());
        var executors = providers.generatedTestExecutors(context.projectInfo());
        if (generators.isEmpty() || executors.isEmpty()) {
            notes.add("No test generator/executor pair is registered for " + context.projectInfo().language()
                    + ", so " + requirement.id() + " cannot be exercised.");
            return new StrategyOutcome(id(), requirement.id(), evidence, counterexamples, outcomes, notes);
        }

        List<GeneratedTest> generated = new ArrayList<>();
        for (var generator : generators) {
            generated.addAll(generator.generate(context, requirement, attemptable));
        }
        int limit = context.budget().budget().maxGeneratedTests() - context.budget().snapshot().generatedTests();
        if (generated.size() > limit) {
            notes.add("Generated tests for " + requirement.id() + " were limited to " + limit
                    + " by the verification budget (max_generated_tests="
                    + context.budget().budget().maxGeneratedTests() + ").");
            generated = new ArrayList<>(generated.subList(0, Math.max(0, limit)));
        }
        if (generated.isEmpty()) {
            notes.add("No executable scenario is declared for " + requirement.id()
                    + ", so CodeWitness could not run any experiment against it. Declare a scenario in "
                    + "codewitness.yaml to make this requirement verifiable.");
            for (VerificationObligation obligation : attemptable) {
                outcomes.add(new ObligationOutcome(obligation.id(), RequirementStatus.UNVERIFIED,
                        "No declared scenario produces an executable experiment for this obligation.",
                        List.of(), List.of(), 0));
            }
            return new StrategyOutcome(id(), requirement.id(), evidence, counterexamples, outcomes, notes);
        }

        GeneratedTestExecution execution = executors.get(0).execute(context, generated);
        context.budget().recordIteration();
        context.budget().recordGeneratedTests(execution.executed().size());

        TestResult result = execution.result();
        Map<String, GeneratedTest> byClass = new LinkedHashMap<>();
        for (GeneratedTest test : generated) {
            byClass.put(test.className(), test);
        }

        Evidence batch = context.evidence().newEvidence(EvidenceType.GENERATED_TEST)
                .description("Executed " + execution.executed().size() + " generated tests for "
                        + requirement.id() + ": " + (result.executed() ? result.describe() : execution.note()))
                .source(id())
                .confidence(EvidenceConfidence.DETERMINISTIC)
                .status(!result.executed() ? EvidenceStatus.INCONCLUSIVE
                        : (result.anyFailure() ? EvidenceStatus.REFUTES : EvidenceStatus.SUPPORTS))
                .result(result.executed() ? result.describe() : "not executed: " + execution.note())
                .output(execution.note() == null ? "" : execution.note())
                .requirement(requirement.id())
                .metadata("generated_classes", generated.stream().map(GeneratedTest::className).toList())
                .metadata("seed", generated.stream().map(GeneratedTest::seed).filter(java.util.Objects::nonNull)
                        .findFirst().orElse(null))
                .metadata("artifacts", execution.artifacts().stream().map(java.nio.file.Path::toString).toList())
                .artifacts(execution.artifacts())
                .reproductionCommand(String.join(" ", reproductionCommand(context, generated,
                        generated.stream().map(GeneratedTest::seed).filter(java.util.Objects::nonNull).findFirst().orElse(null),
                        null)))
                .build();
        context.evidence().add(batch);
        evidence.add(batch);

        if (!result.executed()) {
            for (VerificationObligation obligation : attemptable) {
                outcomes.add(new ObligationOutcome(obligation.id(), RequirementStatus.BLOCKED,
                        "Generated tests could not be executed: " + execution.note(), List.of(batch.id()), List.of(), 0));
            }
            notes.add("Generated tests for " + requirement.id() + " could not be executed: " + execution.note());
            return new StrategyOutcome(id(), requirement.id(), evidence, counterexamples, outcomes, notes);
        }

        Map<String, List<TestCaseResult>> resultsByClass = new LinkedHashMap<>();
        for (TestCaseResult testCase : result.cases()) {
            resultsByClass.computeIfAbsent(testCase.suite(), k -> new ArrayList<>()).add(testCase);
        }

        Set<String> failedObligations = new LinkedHashSet<>();
        for (Map.Entry<String, List<TestCaseResult>> entry : resultsByClass.entrySet()) {
            GeneratedTest test = byClass.get(entry.getKey());
            if (test == null) {
                continue;
            }
            for (TestCaseResult testCase : entry.getValue()) {
                if (!testCase.outcome().isFailure()) {
                    continue;
                }
                test.obligationIds().forEach(failedObligations::add);
                counterexamples.add(counterexample(context, requirement, test, testCase, batch));
            }
        }

        for (VerificationObligation obligation : attemptable) {
            List<TestCaseResult> relevant = new ArrayList<>();
            boolean covered = false;
            for (GeneratedTest test : generated) {
                if (!test.obligationIds().contains(obligation.id())) {
                    continue;
                }
                covered = true;
                relevant.addAll(resultsByClass.getOrDefault(test.className(), List.of()));
            }
            if (!covered) {
                outcomes.add(new ObligationOutcome(obligation.id(), RequirementStatus.UNVERIFIED,
                        "No generated test covered this obligation.", List.of(batch.id()), List.of(), 0));
                continue;
            }
            long failures = relevant.stream().filter(tc -> tc.outcome().isFailure()).count();
            boolean anyFailure = failures > 0;
            outcomes.add(new ObligationOutcome(
                    obligation.id(),
                    anyFailure ? RequirementStatus.FAILED : RequirementStatus.VERIFIED,
                    anyFailure
                            ? failures + " generated test case(s) violated this obligation."
                            : relevant.size() + " generated test case(s) executed and all passed.",
                    List.of(batch.id()),
                    counterexamples.stream().map(Counterexample::id).toList(),
                    relevant.size()));
        }

        if (!failedObligations.isEmpty()) {
            notes.add("Counterexamples were reproduced for obligations: " + String.join(", ", failedObligations));
        }
        return new StrategyOutcome(id(), requirement.id(), evidence, counterexamples, outcomes, notes);
    }

    private Counterexample counterexample(
            VerificationContext context,
            Requirement requirement,
            GeneratedTest test,
            TestCaseResult testCase,
            Evidence batch) {

        Optional<Map<String, String>> decoded =
                CounterexampleProtocol.decode(testCase.failureMessage() == null
                        ? testCase.stackTrace() : testCase.failureMessage());
        Map<String, Object> inputs = new LinkedHashMap<>();
        String expected = "the requirement holds for this input";
        String actual = firstLine(testCase.failureMessage());
        String invariant = null;
        Long seed = test.seed();
        Integer caseIndex = test.caseIndex();

        if (decoded.isPresent()) {
            Map<String, String> fields = decoded.get();
            inputs.putAll(CounterexampleProtocol.parseInputs(fields.get("inputs")));
            expected = fields.getOrDefault("expected", expected);
            actual = fields.getOrDefault("actual", actual);
            invariant = fields.get("invariant");
            if (fields.containsKey("seed")) {
                try {
                    seed = Long.parseLong(fields.get("seed"));
                } catch (NumberFormatException ignored) {
                    // keep the declared seed
                }
            }
            if (fields.containsKey("case")) {
                try {
                    caseIndex = Integer.parseInt(fields.get("case"));
                } catch (NumberFormatException ignored) {
                    // keep the declared case index
                }
            }
        } else {
            inputs.put("generated_test", testCase.displayName());
            inputs.put("note", "the failing test did not report structured inputs");
        }

        List<String> command = reproductionCommand(context, List.of(test), seed, caseIndex);
        String reproduction = String.join(" ", command);
        java.nio.file.Path sourceArtifact = batch.artifacts().stream()
                .filter(path -> path.getFileName().toString().equals(test.simpleName() + ".java"))
                .findFirst()
                .orElse(null);

        Counterexample counterexample = new Counterexample(
                context.state().nextCounterexampleId(),
                requirement.id(),
                test.obligationIds().isEmpty() ? null : test.obligationIds().get(0),
                "Generated test " + testCase.displayName() + " violated " + requirement.id() + ": " + actual,
                inputs,
                expected,
                actual,
                invariant == null ? requirement.description() : invariant,
                testCase.displayName(),
                batch.id(),
                seed,
                new Counterexample.ReproductionRecipe(
                        "Run the generated test that exposed the violation",
                        command,
                        sourceArtifact,
                        seed,
                        reproductionProperties(caseIndex, seed)),
                Counterexample.DiscoveryMethod.GENERATED_TEST);

        Evidence evidence = context.evidence().newEvidence(EvidenceType.COUNTEREXAMPLE)
                .description("Counterexample " + counterexample.id() + " for " + requirement.id()
                        + " discovered by generated test " + testCase.displayName())
                .source(id())
                .confidence(EvidenceConfidence.DETERMINISTIC)
                .status(EvidenceStatus.REFUTES)
                .input("inputs", inputs)
                .result(actual)
                .output(firstLine(testCase.failureMessage()))
                .requirement(requirement.id())
                .obligations(test.obligationIds())
                .metadata("reproduce", reproduction)
                .metadata("seed", seed)
                .metadata("case", caseIndex)
                .build();
        context.evidence().add(evidence);
        return counterexample;
    }

    /**
     * Builds a command that replays exactly the failing case.
     *
     * <p>For Maven the seed and case index travel as system properties, which Surefire forwards to the
     * test JVM. Gradle does not forward system properties to the test JVM, so they are passed through
     * the environment instead; the generated tests read both.
     */
    private static List<String> reproductionCommand(
            VerificationContext context, List<GeneratedTest> tests, Long seed, Integer caseIndex) {
        boolean gradle = context.projectInfo().buildSystem().id().equals("gradle");
        List<String> command = new ArrayList<>();
        List<String> build = context.state().build().command();
        if (gradle) {
            command.add("env");
            if (seed != null) {
                command.add("CODEWITNESS_SEED=" + seed);
            }
            if (caseIndex != null) {
                command.add("CODEWITNESS_CASE=" + caseIndex);
            }
            command.add(build.isEmpty() ? "./gradlew" : build.get(0));
            command.add("test");
            for (GeneratedTest test : tests) {
                command.add("--tests");
                command.add(test.className());
            }
            return command;
        }
        command.add(build.isEmpty() ? "mvn" : build.get(0));
        command.add("-Dtest=" + tests.stream().map(GeneratedTest::simpleName)
                .collect(java.util.stream.Collectors.joining(",")));
        if (seed != null) {
            command.add("-D" + CounterexampleProtocol.seedProperty() + "=" + seed);
        }
        if (caseIndex != null) {
            command.add("-D" + CounterexampleProtocol.caseProperty() + "=" + caseIndex);
        }
        command.add("test");
        return command;
    }

    private static Map<String, String> reproductionProperties(Integer caseIndex, Long seed) {
        Map<String, String> properties = new LinkedHashMap<>();
        if (seed != null) {
            properties.put("seed", String.valueOf(seed));
        }
        if (caseIndex != null) {
            properties.put("case", String.valueOf(caseIndex));
        }
        return properties;
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "test failed";
        }
        int newline = text.indexOf('\n');
        String line = newline < 0 ? text : text.substring(0, newline);
        return line.length() > 400 ? line.substring(0, 400) + "..." : line;
    }
}
