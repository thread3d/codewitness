package dev.codewitness.core.engine.strategy;

import dev.codewitness.core.config.Scenario;
import dev.codewitness.core.config.VerificationConfig;
import dev.codewitness.core.evidence.Evidence;
import dev.codewitness.core.evidence.EvidenceStatus;
import dev.codewitness.core.evidence.EvidenceType;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.requirements.ObligationKind;
import dev.codewitness.core.requirements.ObligationOrigin;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.RequirementStatus;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.result.Counterexample;
import dev.codewitness.core.spi.StrategyOutcome;
import dev.codewitness.core.spi.VerificationContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A process scenario is the only way to say anything about a program's own interface — its exit code, what it
 * printed. These tests run a real process, because the whole point is that the assertion is about something that
 * actually happened.
 */
class ProcessScenarioStrategyTest {

    private static Scenario scenario(String id, Integer exitCode, List<String> stdoutContains,
            List<String> stderrContains, String... command) {
        return new Scenario(id, "R-001", "a command", Scenario.Mode.PROCESS, null, List.of(), null, null,
                1, 42L, 30, List.of(command),
                new Scenario.ProcessExpectation(exitCode, stdoutContains, stderrContains));
    }

    private static VerificationObligation obligation(String requirementId, String scenarioId) {
        return new VerificationObligation("OB-1", requirementId, "execute " + scenarioId,
                ObligationKind.PROCESS, ObligationOrigin.USER_DECLARED, scenarioId, java.util.Map.of(), false);
    }

    private static VerificationContext context(Path root, Scenario... scenarios) {
        ProjectRef project = ProjectRef.of(root);
        return VerificationContext.builder()
                .runId("CW-2026-01-01-TEST0")
                .projectRef(project)
                .projectInfo(ProjectInfo.builder(project).build())
                .config(VerificationConfig.defaults().withScenarios(List.of(scenarios)))
                .workspace(new dev.codewitness.core.io.WorkspaceManager.Workspace(
                        root, dev.codewitness.core.io.WorkspaceManager.Mode.IN_PLACE, "test"))
                .artifacts(new dev.codewitness.core.io.ArtifactStore(root.resolve("artifacts")))
                .budget(new dev.codewitness.core.result.BudgetUsage.Tracker(
                        dev.codewitness.core.result.Budget.defaults()))
                .sandbox(new dev.codewitness.core.exec.LocalProcessSandbox(List.of(root)))
                .evidenceLedger(new dev.codewitness.core.evidence.EvidenceLedger("CW-2026-01-01-TEST0"))
                .build();
    }

    @Test
    @DisplayName("a command that behaves as declared discharges its obligation with deterministic evidence")
    void aPassingCommandVerifies(@TempDir Path root) {
        StrategyOutcome outcome = new ProcessScenarioStrategy().verify(
                context(root, scenario("SC-OK", 0, List.of("hello"), List.of(), "/bin/sh", "-c", "echo hello")),
                requirement("R-001"),
                List.of(obligation("R-001", "SC-OK")));

        assertEquals(1, outcome.evidence().size(), String.valueOf(outcome.notes()));
        Evidence evidence = outcome.evidence().getFirst();
        assertEquals(EvidenceType.PROCESS_EXECUTION, evidence.type());
        assertEquals(EvidenceStatus.SUPPORTS, evidence.status());
        assertTrue(outcome.counterexamples().isEmpty(), "nothing was violated, so nothing may be reported as one");
        assertEquals(RequirementStatus.VERIFIED, outcome.obligations().getFirst().status());
    }

    @Test
    @DisplayName("a wrong exit code is a counterexample carrying the command and what it actually returned")
    void aWrongExitCodeIsACounterexample(@TempDir Path root) {
        StrategyOutcome outcome = new ProcessScenarioStrategy().verify(
                context(root, scenario("SC-EXIT", 3, List.of(), List.of(), "/bin/sh", "-c", "exit 7")),
                requirement("R-001"),
                List.of(obligation("R-001", "SC-EXIT")));

        assertEquals(1, outcome.counterexamples().size());
        Counterexample counterexample = outcome.counterexamples().getFirst();
        assertTrue(counterexample.actual().contains("7"), counterexample.actual());
        assertTrue(counterexample.actual().contains("3"), counterexample.actual());
        assertTrue(String.valueOf(counterexample.inputs()).contains("exit 7"),
                "the exact command must be in the counterexample: " + counterexample.inputs());
        assertEquals(RequirementStatus.FAILED, outcome.obligations().getFirst().status());
    }

    @Test
    @DisplayName("output assertions are about what was printed, on the stream named")
    void streamAssertions() {
        Path root = Path.of(".").toAbsolutePath();
        StrategyOutcome onStdout = new ProcessScenarioStrategy().verify(
                context(root, scenario("SC-OUT", null, List.of("to stdout"), List.of(), "/bin/sh", "-c",
                        "echo to stdout")),
                requirement("R-001"), List.of(obligation("R-001", "SC-OUT")));
        assertTrue(onStdout.counterexamples().isEmpty(), String.valueOf(onStdout.counterexamples()));

        StrategyOutcome onStderr = new ProcessScenarioStrategy().verify(
                context(root, scenario("SC-ERR", null, List.of("to stderr"), List.of(), "/bin/sh", "-c",
                        "echo to stdout")),
                requirement("R-001"), List.of(obligation("R-001", "SC-ERR")));
        assertEquals(1, onStderr.counterexamples().size(),
                "text printed to stdout must not satisfy an assertion about stderr");
    }

    @Test
    @DisplayName("a scenario whose command cannot start is unverified, never a pass and never a fabricated failure")
    void aCommandThatCannotStartIsUnverified(@TempDir Path root) {
        StrategyOutcome outcome = new ProcessScenarioStrategy().verify(
                context(root, scenario("SC-GONE", 0, List.of(), List.of(), "/nonexistent/command")),
                requirement("R-001"),
                List.of(obligation("R-001", "SC-GONE")));

        assertEquals(RequirementStatus.UNVERIFIED, outcome.obligations().getFirst().status());
        assertTrue(outcome.counterexamples().isEmpty(),
                "a tool that would not start is not evidence that the requirement fails");
        assertEquals(EvidenceStatus.INCONCLUSIVE, outcome.evidence().getFirst().status());
    }

    @Test
    @DisplayName("a requirement with no process scenario says so rather than claiming to have run one")
    void noScenarioIsStated(@TempDir Path root) {
        StrategyOutcome outcome = new ProcessScenarioStrategy().verify(
                context(root), requirement("R-001"), List.of(obligation("R-001", "SC-MISSING")));

        assertTrue(outcome.evidence().isEmpty());
        assertFalse(outcome.notes().isEmpty(), "the absence of an experiment must be stated");
        assertEquals(RequirementStatus.UNVERIFIED, outcome.obligations().getFirst().status());
    }


    @Test
    @DisplayName("a scenario the project asked to have as a native test is left to that test, and says so when none exists")
    void aNativeTestScenarioIsNotExecutedHere(@TempDir Path root) {
        // `native_test` moves the assertion into the project's own suite, and only the Java and Python adapters can
        // write one. On any other language the engine stands aside and the generator has nothing to generate, so the
        // scenario is not executed at all - and the requirement must come out unverified with that said, not silently
        // passing. This pins the sharp edge the reference documents.
        Scenario nativeTest = new Scenario("SC-NATIVE", "R-001", "a command", Scenario.Mode.PROCESS, null,
                List.of(), null, null, 1, 42L, 30, List.of("/bin/true"),
                new Scenario.ProcessExpectation(0, List.of(), List.of()), true);

        StrategyOutcome outcome = new ProcessScenarioStrategy().verify(
                context(root, nativeTest), requirement("R-001"), List.of(obligation("R-001", "SC-NATIVE")));

        assertTrue(outcome.evidence().isEmpty(), "the engine must not run a command a native test owns");
        assertFalse(outcome.notes().isEmpty(), "the absence of an execution must be stated");
        assertEquals(RequirementStatus.UNVERIFIED, outcome.obligations().getFirst().status());
    }

    private static Requirement requirement(String id) {
        return Requirement.builder(id, "A command exits with the documented code.").title("Exit code")
                .build();
    }
}
