package dev.codewitness.core.engine.strategy;

import dev.codewitness.core.config.Scenario;
import dev.codewitness.core.config.VerificationConfig;
import dev.codewitness.core.evidence.Evidence;
import dev.codewitness.core.evidence.EvidenceLedger;
import dev.codewitness.core.evidence.EvidenceStatus;
import dev.codewitness.core.exec.LocalProcessSandbox;
import dev.codewitness.core.io.ArtifactStore;
import dev.codewitness.core.io.WorkspaceManager;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.requirements.ObligationKind;
import dev.codewitness.core.requirements.ObligationOrigin;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.RequirementStatus;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.result.Budget;
import dev.codewitness.core.result.BudgetUsage;
import dev.codewitness.core.result.ObligationOutcome;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.GeneratedTestExecution;
import dev.codewitness.core.spi.StrategyOutcome;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationProviderRegistry;
import dev.codewitness.core.spi.VerificationTools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract that makes {@code VERIFIED} mean something.
 *
 * <p>{@link GeneratedTestsStrategy} is the only strategy that can discharge an obligation to
 * {@code VERIFIED}, so it is the only place where a bug produces a false pass rather than a false
 * alarm. Two such bugs were found in it, and both came from the same mistake: treating "no failing
 * case was reported" as "cases ran, asserted the requirement and passed".
 *
 * <ul>
 *   <li>a generated test that the runner never reported at all - a class with no test methods, a case
 *       dropped by a parser - contributed zero failures to an empty list and was scored as discharged;
 *   <li>a scenario that declares no {@code observation} and {@code invariant} produces a test whose
 *       body is just the declared steps, so it can only fail if a step throws - it asserts nothing
 *       about the requirement, yet it too was scored as discharged.
 * </ul>
 *
 * <p>These tests pin the conservative behaviour: support requires evidence, and the absence of
 * evidence is never evidence.
 */
class GeneratedTestsStrategyTest {

    @TempDir
    Path tmp;

    private static final String REQUIREMENT_ID = "R-001";
    private static final String OBLIGATION_ID = "OB-001";
    private static final String CLASS_NAME = "dev.codewitness.generated.Cw_SC_OB_001_Test";

    @Test
    void aCaseThatRanAndPassedWithAnOracleVerifiesTheObligation() {
        Fixture fixture = fixture(true, List.of(passed("case0000")));

        StrategyOutcome outcome = verify(fixture);

        ObligationOutcome obligation = only(fixture, outcome);
        assertEquals(RequirementStatus.VERIFIED, obligation.status(),
                "an executed case that asserted the declared invariant and passed is evidence");
        assertEquals(1, obligation.attempts());
        assertTrue(outcome.counterexamples().isEmpty());
    }

    @Test
    void aGeneratedTestThatReportedNoCaseDoesNotVerifyTheObligation() {
        // The runner ran and reported an empty case list. Before the fix this produced the rationale
        // "0 generated test case(s) executed and all passed" and the requirement came out VERIFIED.
        Fixture fixture = fixture(true, List.of());

        StrategyOutcome outcome = verify(fixture);

        ObligationOutcome obligation = only(fixture, outcome);
        assertEquals(RequirementStatus.UNVERIFIED, obligation.status(),
                "nothing was executed, so nothing was verified");
        assertTrue(obligation.rationale().contains("no case"),
                "the rationale must say that the runner reported no case: " + obligation.rationale());
    }

    @Test
    void aGeneratedTestWithoutAnOracleDoesNotVerifyTheObligation() {
        // The scenario declared no observation and no invariant, so the generated test only runs the
        // declared steps. It executed and passed; it asserted nothing.
        Fixture fixture = fixture(false, List.of(passed("randomSearch")));

        StrategyOutcome outcome = verify(fixture);

        ObligationOutcome obligation = only(fixture, outcome);
        assertEquals(RequirementStatus.UNVERIFIED, obligation.status(),
                "a test that cannot fail is not evidence that a requirement holds");
        assertTrue(obligation.rationale().contains("asserted nothing")
                        || obligation.rationale().contains("no observation"),
                "the rationale must explain the missing oracle: " + obligation.rationale());
    }

    @Test
    void theBatchEvidenceIsInconclusiveWhenNoScenarioDeclaredAnOracle() {
        Fixture fixture = fixture(false, List.of(passed("randomSearch")));

        verify(fixture);

        // A test that asserted nothing must not be recorded as evidence that supports the requirement,
        // because that record is what a reader and the verdict calculator both see.
        Evidence batch = fixture.context().evidence().forRequirement(REQUIREMENT_ID).stream()
                .filter(evidence -> evidence.type() == dev.codewitness.core.evidence.EvidenceType.GENERATED_TEST)
                .findFirst()
                .orElseThrow();
        assertEquals(EvidenceStatus.INCONCLUSIVE, batch.status());
        assertTrue(batch.description().contains("asserted nothing"),
                "the evidence must say why it proves nothing: " + batch.description());
    }

    @Test
    void aFailingGeneratedCaseFailsTheObligation() {
        Fixture fixture = fixture(true, List.of(failed("case0000")));

        StrategyOutcome outcome = verify(fixture);

        ObligationOutcome obligation = only(fixture, outcome);
        assertEquals(RequirementStatus.FAILED, obligation.status());
        assertEquals(1, outcome.counterexamples().size(), "a failing case is a counterexample");
    }

    @Test
    void aDeclaredProcessScenarioIsNotReportedAsNoScenario() {
        // This project's own self-check: its exit-code requirement declares a process scenario, which
        // the process strategy executed and discharged. A process scenario has no steps, observation or
        // invariant, so the generator emits nothing for it unless it asks for a native test - and this
        // strategy used to announce "No executable scenario is declared ... so CodeWitness could not run
        // any experiment against it" on a requirement the same run had just verified by running it.
        // (The requirement id is deliberately not repeated: this mapping is by mention, and naming it
        // here would make this test "evidence" for that requirement.)
        Scenario process = new Scenario("SC-TEST", REQUIREMENT_ID, "a command", Scenario.Mode.PROCESS, null,
                List.of(), null, null, 1, 42L, 30, List.of("/bin/true"),
                new Scenario.ProcessExpectation(0, List.of(), List.of()));
        Fixture fixture = fixture(VerificationConfig.defaults().withScenarios(List.of(process)), false);

        StrategyOutcome outcome = verify(fixture);

        assertFalse(outcome.notes().stream().anyMatch(note -> note.contains("No executable scenario is declared")),
                "a scenario is declared, so the note must not say none is: " + outcome.notes());
        assertTrue(outcome.notes().stream().anyMatch(note -> note.contains("generated test")),
                "the note must say why the generated-test strategy ran nothing: " + outcome.notes());
        ObligationOutcome obligation = only(fixture, outcome);
        assertEquals(RequirementStatus.UNVERIFIED, obligation.status(),
                "the generated-test strategy observed nothing, so it cannot verify anything");
        assertFalse(obligation.rationale().contains("No declared scenario"),
                "a scenario is declared; the rationale must not deny it: " + obligation.rationale());
    }

    @Test
    void withoutAScenarioTheNoteStillSaysThereIsNone() {
        // The counterpart of the test above: when nothing is declared, the note that says so is true
        // and must survive. A fix that silenced both cases would hide a real gap.
        Fixture fixture = fixture(VerificationConfig.defaults(), false);

        StrategyOutcome outcome = verify(fixture);

        assertTrue(outcome.notes().stream()
                        .anyMatch(note -> note.contains("No executable scenario is declared")),
                outcome.notes().toString());
        assertTrue(only(fixture, outcome).rationale().contains("No declared scenario"),
                only(fixture, outcome).rationale());
    }

    // ------------------------------------------------------------------ fixture

    private record Fixture(
            VerificationContext context,
            Requirement requirement,
            List<VerificationObligation> obligations,
            VerificationProviderRegistry providers) {
    }

    private static TestCaseResult passed(String name) {
        return new TestCaseResult(CLASS_NAME, name, TestOutcome.PASSED, Duration.ofMillis(1), null, null, null);
    }

    private static TestCaseResult failed(String name) {
        return new TestCaseResult(CLASS_NAME, name, TestOutcome.FAILED, Duration.ofMillis(1),
                "AssertionError", "expected 0 but was 100", "at " + CLASS_NAME);
    }

    /**
     * A context whose generator produces exactly one test for {@link #OBLIGATION_ID}, and whose executor
     * reports exactly the cases the test asks for. {@code checksInvariant} models whether the scenario
     * declared an oracle.
     */
    private Fixture fixture(boolean checksInvariant, List<TestCaseResult> cases) {
        return fixture(VerificationConfig.defaults(), true, checksInvariant, cases);
    }

    /**
     * The same context with the two knobs the "no generated test" branch depends on: the scenarios the
     * run declares, and whether the generator produces a test at all.
     */
    private Fixture fixture(VerificationConfig config, boolean generatorProducesTest) {
        return fixture(config, generatorProducesTest, true, List.of(passed("case0000")));
    }

    private Fixture fixture(
            VerificationConfig config, boolean generatorProducesTest, boolean checksInvariant,
            List<TestCaseResult> cases) {
        Requirement requirement = Requirement.builder(REQUIREMENT_ID, "A withdrawal limit must hold.")
                .title("Withdrawal limit").build();
        VerificationObligation obligation = new VerificationObligation(
                OBLIGATION_ID, REQUIREMENT_ID, "The invariant must hold for every case",
                ObligationKind.PROPERTY, ObligationOrigin.USER_DECLARED, "SC-TEST",
                java.util.Map.of("scenario", "SC-TEST"), false);

        VerificationTools.TestGenerator generator = new VerificationTools.TestGenerator() {
            @Override
            public String id() {
                return "test-generator";
            }

            @Override
            public boolean supports(ProjectInfo project) {
                return true;
            }

            @Override
            public List<GeneratedTest> generate(
                    VerificationContext context, Requirement target, List<VerificationObligation> obligations) {
                return generatorProducesTest
                        ? List.of(new GeneratedTest(CLASS_NAME, "class Cw {}", "randomSearch",
                                List.of(REQUIREMENT_ID), List.of(OBLIGATION_ID), "test", 42L, null,
                                checksInvariant))
                        : List.of();
            }
        };
        VerificationTools.GeneratedTestExecutor executor = new VerificationTools.GeneratedTestExecutor() {
            @Override
            public String id() {
                return "generated-test-executor";
            }

            @Override
            public boolean supports(ProjectInfo project) {
                return true;
            }

            @Override
            public GeneratedTestExecution execute(VerificationContext context, List<GeneratedTest> tests) {
                TestResult result = TestResult.from(cases, Duration.ofMillis(cases.size()), List.of(),
                        cases.isEmpty() ? "the runner reported no case for the generated test" : null);
                return new GeneratedTestExecution(tests, result, List.of(), result.describe());
            }
        };

        VerificationProviderRegistry providers = VerificationProviderRegistry.empty()
                .register(generator)
                .register(executor);

        VerificationContext context = VerificationContext.builder()
                .runId("CW-2026-01-01-TEST0")
                .projectRef(ProjectRef.of(tmp))
                .projectInfo(ProjectInfo.builder(ProjectRef.of(tmp)).language(LanguageId.JAVA).build())
                .config(config)
                .workspace(new WorkspaceManager.Workspace(tmp, WorkspaceManager.Mode.IN_PLACE, "test"))
                .artifacts(new ArtifactStore(tmp.resolve("artifacts")))
                .budget(new BudgetUsage.Tracker(Budget.defaults()))
                .sandbox(new LocalProcessSandbox(List.of(tmp)))
                .evidenceLedger(new EvidenceLedger("CW-2026-01-01-TEST0"))
                .build();
        return new Fixture(context, requirement, List.of(obligation), providers);
    }

    private static StrategyOutcome verify(Fixture fixture) {
        return new GeneratedTestsStrategy(fixture.providers())
                .verify(fixture.context(), fixture.requirement(), fixture.obligations());
    }

    private static ObligationOutcome only(Fixture fixture, StrategyOutcome outcome) {
        List<ObligationOutcome> matching = outcome.obligations().stream()
                .filter(candidate -> candidate.obligationId().equals(OBLIGATION_ID))
                .toList();
        assertFalse(matching.isEmpty(), "the strategy must report an outcome for " + OBLIGATION_ID);
        assertEquals(1, matching.size(), "exactly one outcome per obligation");
        return matching.getFirst();
    }
}
