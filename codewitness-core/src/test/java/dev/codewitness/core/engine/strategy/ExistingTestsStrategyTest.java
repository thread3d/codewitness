package dev.codewitness.core.engine.strategy;

import dev.codewitness.core.config.VerificationConfig;
import dev.codewitness.core.engine.RequirementCodeMapper;
import dev.codewitness.core.evidence.EvidenceLedger;
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
import dev.codewitness.core.requirements.RequirementSet;
import dev.codewitness.core.requirements.RequirementStatus;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.result.Budget;
import dev.codewitness.core.result.BudgetUsage;
import dev.codewitness.core.result.ObligationOutcome;
import dev.codewitness.core.spi.StrategyOutcome;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationProviderRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a passing project test suite is allowed to say about a requirement.
 *
 * <p>A passing test that was mapped to a requirement supports it, but it never discharges an
 * obligation: the tests were written by the same process that wrote the code, which is not independent
 * verification. A failing mapped test, by contrast, is a counterexample, because a test that fails is
 * an observation whatever wrote it.
 *
 * <p>This also pins where the outcome is recorded. It used to be keyed by a test class name instead of
 * an obligation id, so it could never be merged into the obligation table and the support it carried
 * was invisible.
 */
class ExistingTestsStrategyTest {

    @TempDir
    Path tmp;

    private static final String REQUIREMENT_ID = "R-001";
    private static final String OBLIGATION_ID = "OB-001";
    private static final String TEST_CLASS = "com.example.account.LedgerTest";

    @Test
    void aPassingMappedTestSupportsTheObligationWithoutDischargingIt() {
        Fixture fixture = fixture(List.of(new TestCaseResult(TEST_CLASS, "limitHolds", TestOutcome.PASSED,
                Duration.ofMillis(3), null, null, null)));

        StrategyOutcome outcome = verify(fixture);

        ObligationOutcome obligation = only(outcome);
        assertEquals(OBLIGATION_ID, obligation.obligationId(),
                "the outcome must be keyed by the obligation it bears on, not by a test class name");
        assertEquals(RequirementStatus.PARTIALLY_VERIFIED, obligation.status(),
                "a green test suite written alongside the code is support, not proof");
        assertTrue(outcome.counterexamples().isEmpty());
    }

    @Test
    void aFailingMappedTestIsACounterexample() {
        Fixture fixture = fixture(List.of(new TestCaseResult(TEST_CLASS, "limitHolds", TestOutcome.FAILED,
                Duration.ofMillis(3), "AssertionError", "expected 1000 but was 1400", "at LedgerTest")));

        StrategyOutcome outcome = verify(fixture);

        assertEquals(1, outcome.counterexamples().size(),
                "a failing test is an observation, whoever wrote it");
        assertEquals(REQUIREMENT_ID, outcome.counterexamples().getFirst().requirementId());
    }

    @Test
    void anUnrunSuiteSaysSoInsteadOfProducingAVerdict() {
        Fixture fixture = fixture(List.of());
        fixture.context().state().existingTests(TestResult.notExecuted("no test directory was detected"));

        StrategyOutcome outcome = verify(fixture);

        assertTrue(outcome.notes().stream().anyMatch(note -> note.contains("unavailable")),
                outcome.notes().toString());
        assertTrue(outcome.counterexamples().isEmpty());
    }

    // ------------------------------------------------------------------ fixture

    private record Fixture(VerificationContext context, Requirement requirement,
            List<VerificationObligation> obligations) {
    }

    private Fixture fixture(List<TestCaseResult> cases) {
        // Declaring the test class explicitly maps it to the requirement without needing a file tree.
        Requirement requirement = Requirement.builder(REQUIREMENT_ID, "A withdrawal limit must hold.")
                .title("Withdrawal limit")
                .attribute("tests", TEST_CLASS)
                .attribute("paths", "src/main/java/com/example/account/Ledger.java")
                .build();
        RequirementSet requirements = new RequirementSet(List.of(requirement));
        ProjectInfo project = ProjectInfo.builder(ProjectRef.of(tmp)).language(LanguageId.JAVA).build();
        RequirementCodeMapper mapper = RequirementCodeMapper.build(project, requirements);

        VerificationContext context = VerificationContext.builder()
                .runId("CW-2026-01-01-TEST0")
                .projectRef(ProjectRef.of(tmp))
                .projectInfo(project)
                .config(VerificationConfig.defaults())
                .workspace(new WorkspaceManager.Workspace(tmp, WorkspaceManager.Mode.IN_PLACE, "test"))
                .artifacts(new ArtifactStore(tmp.resolve("artifacts")))
                .budget(new BudgetUsage.Tracker(Budget.defaults()))
                .sandbox(new LocalProcessSandbox(List.of(tmp)))
                .codeMap(mapper)
                .evidenceLedger(new EvidenceLedger("CW-2026-01-01-TEST0"))
                .build();
        context.state().existingTests(TestResult.from(cases, Duration.ofMillis(3), List.of(), null));

        VerificationObligation obligation = new VerificationObligation(
                OBLIGATION_ID, REQUIREMENT_ID, "The limit must hold", ObligationKind.UPPER_BOUND,
                ObligationOrigin.USER_DECLARED, null, Map.of(), false);
        return new Fixture(context, requirement, List.of(obligation));
    }

    private static StrategyOutcome verify(Fixture fixture) {
        return new ExistingTestsStrategy(VerificationProviderRegistry.empty())
                .verify(fixture.context(), fixture.requirement(), fixture.obligations());
    }

    private static ObligationOutcome only(StrategyOutcome outcome) {
        List<ObligationOutcome> matching = outcome.obligations().stream()
                .filter(candidate -> candidate.obligationId().equals(OBLIGATION_ID))
                .toList();
        assertEquals(1, matching.size(),
                "the strategy must report one outcome for " + OBLIGATION_ID + ": " + outcome.obligations());
        return matching.getFirst();
    }
}
