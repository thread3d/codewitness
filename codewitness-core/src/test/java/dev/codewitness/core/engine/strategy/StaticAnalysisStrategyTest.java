package dev.codewitness.core.engine.strategy;

import dev.codewitness.core.config.VerificationConfig;
import dev.codewitness.core.engine.RequirementCodeMapper;
import dev.codewitness.core.evidence.Evidence;
import dev.codewitness.core.evidence.EvidenceConfidence;
import dev.codewitness.core.evidence.EvidenceLedger;
import dev.codewitness.core.exec.LocalProcessSandbox;
import dev.codewitness.core.io.ArtifactStore;
import dev.codewitness.core.io.WorkspaceManager;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.Severity;
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two rules about static analysis, both of which the implementation used to get wrong.
 *
 * <p>First, whether an analyzer <em>ran</em> is a fact about the run, not an inference from its output.
 * The strategy used to derive it from "are there any findings anywhere", which is wrong in both
 * directions: a clean project looked like a project where nothing had executed, and one unrelated
 * finding discharged every requirement's {@code STATIC} obligations.
 *
 * <p>Second, only a deterministic tool may refute a requirement. A compiler or a linter reports a fact;
 * the pattern-matching heuristic rule tables ship a documented false-positive profile. A regex that
 * suspects a hardcoded credential used to become a counterexample and fail the requirement.
 */
class StaticAnalysisStrategyTest {

    @TempDir
    Path tmp;

    private static final String REQUIREMENT_ID = "R-001";
    private static final String OBLIGATION_ID = "OB-001";
    private static final String FILE = "src/main/java/com/example/A.java";

    @Test
    void aCleanRunVerifiesTheStaticObligationWhenTheAnalyzersRan() {
        Fixture fixture = fixture(List.of(), true);

        ObligationOutcome obligation = only(fixture, verify(fixture));

        assertEquals(RequirementStatus.VERIFIED, obligation.status(),
                "\"the analyzers looked and found nothing\" is the observation the obligation asks for");
    }

    @Test
    void aRunWithoutAnalyzersLeavesTheStaticObligationUnverifiedAndSaysSo() {
        Fixture fixture = fixture(List.of(), false);

        ObligationOutcome obligation = only(fixture, verify(fixture));

        assertEquals(RequirementStatus.UNVERIFIED, obligation.status(),
                "\"no analyzer ran\" must never discharge an obligation");
        assertTrue(obligation.rationale().contains("No static analyzer ran"), obligation.rationale());
    }

    @Test
    void aRunWithoutAnalyzersDoesNotClaimACleanAnalyzerRun() {
        Fixture fixture = fixture(List.of(), false);

        StrategyOutcome outcome = verify(fixture);

        assertTrue(outcome.notes().stream().anyMatch(note -> note.startsWith("No static analyzer ran")),
                "nothing looked, so the note must say so: " + outcome.notes());
        assertFalse(outcome.notes().stream().anyMatch(note -> note.contains("found nothing attributable")),
                "a run where no analyzer executed must not read as a clean analyzer run: "
                        + outcome.notes());
    }

    @Test
    void aHighSeverityHeuristicMatchIsAdvisoryAndNeverFailsTheRequirement() {
        Fixture fixture = fixture(List.of(heuristic("CW-SEC-004", Severity.CRITICAL)), true);

        StrategyOutcome outcome = verify(fixture);

        assertTrue(outcome.counterexamples().isEmpty(),
                "a rule match is a hypothesis to check by hand, not a demonstration that the requirement "
                        + "is violated");
        assertFalse(only(fixture, outcome).status() == RequirementStatus.FAILED);

        Evidence advisory = fixture.context().evidence().forRequirement(REQUIREMENT_ID).stream()
                .filter(evidence -> evidence.confidence() == EvidenceConfidence.MEDIUM)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "a heuristic match must be recorded as advisory evidence so a reader can see it"));
        assertTrue(advisory.description().contains("false-positive"), advisory.description());
        assertFalse(advisory.qualifiesAsDeterministicProof(),
                "advisory evidence must not qualify as proof");
    }

    @Test
    void aHighSeverityFindingFromARealToolStillRefutesTheRequirement() {
        Fixture fixture = fixture(List.of(deterministic("javac", Severity.CRITICAL)), true);

        StrategyOutcome outcome = verify(fixture);

        assertEquals(1, outcome.counterexamples().size(),
                "a compiler is a deterministic tool and its finding is a fact about the code");
        assertEquals(RequirementStatus.FAILED, only(fixture, outcome).status());
    }

    // ------------------------------------------------------------------ fixture

    private record Fixture(VerificationContext context, Requirement requirement) {
    }

    private static Finding heuristic(String rule, Severity severity) {
        return new Finding("java-heuristics", rule, severity, FindingCategory.SECURITY,
                "External process execution with a computed command.", FILE, 12, null, Map.of());
    }

    private static Finding deterministic(String analyzer, Severity severity) {
        return new Finding(analyzer, "unchecked", severity, FindingCategory.BUG,
                "unchecked conversion", FILE, 12, null, Map.of());
    }

    private Fixture fixture(List<Finding> findings, boolean analyzersRan) {
        Requirement requirement = Requirement.builder(REQUIREMENT_ID, "The requirement holds.")
                .title("R").attribute("paths", FILE).build();
        ProjectInfo project = ProjectInfo.builder(ProjectRef.of(tmp)).language(LanguageId.JAVA).build();
        RequirementCodeMapper mapper =
                RequirementCodeMapper.build(project, new RequirementSet(List.of(requirement)));
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
        context.state().addFindings(findings);
        context.state().staticAnalysisRan(analyzersRan);
        return new Fixture(context, requirement);
    }

    private static StrategyOutcome verify(Fixture fixture) {
        VerificationObligation obligation = new VerificationObligation(
                OBLIGATION_ID, REQUIREMENT_ID, "Static analysis must not report a serious finding",
                ObligationKind.STATIC, ObligationOrigin.USER_DECLARED, null, Map.of(), false);
        return new StaticAnalysisStrategy()
                .verify(fixture.context(), fixture.requirement(), List.of(obligation));
    }

    private static ObligationOutcome only(Fixture fixture, StrategyOutcome outcome) {
        List<ObligationOutcome> matching = outcome.obligations().stream()
                .filter(candidate -> candidate.obligationId().equals(OBLIGATION_ID))
                .toList();
        assertEquals(1, matching.size(), "exactly one outcome for " + OBLIGATION_ID);
        return matching.getFirst();
    }
}
