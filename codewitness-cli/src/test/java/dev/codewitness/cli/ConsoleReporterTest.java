package dev.codewitness.cli;

import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ModuleInfo;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.RequirementSet;
import dev.codewitness.core.requirements.RequirementStatus;
import dev.codewitness.core.result.Budget;
import dev.codewitness.core.result.BudgetUsage;
import dev.codewitness.core.result.Counterexample;
import dev.codewitness.core.result.EnvironmentSnapshot;
import dev.codewitness.core.result.RequirementVerdict;
import dev.codewitness.core.result.RunStatus;
import dev.codewitness.core.result.VerificationReport;
import dev.codewitness.core.result.VerificationRun;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The console report is the artifact a user actually reads, so its shape is pinned: the run id, the
 * overall status, the status markers and - most importantly - the exact inputs of every
 * counterexample.
 */
class ConsoleReporterTest {

    @TempDir
    Path tmp;

    private static final String RUN_ID = "CW-2026-10-06-7F31A";

    @Test
    void renderContainsRunIdStatusMarkersAndCounterexampleInputs() {
        VerificationReport report = report(List.of(verdict("R-001", RequirementStatus.FAILED)), List.of(counterexample()));

        String rendered = ConsoleReporter.render(report);

        assertTrue(rendered.contains("CODEWITNESS  verification report"), rendered);
        assertTrue(rendered.contains("run          " + RUN_ID), rendered);
        assertTrue(rendered.contains("overall      FAILED"), rendered);
        assertTrue(rendered.contains("\u2717"), "the failed status marker must be rendered");
        assertTrue(rendered.contains("R-001"), rendered);
        assertTrue(rendered.contains("Withdrawal limit"), rendered);
        assertTrue(rendered.contains("a counterexample was observed"), rendered);
        assertTrue(rendered.contains("Counts"), rendered);
        assertTrue(rendered.contains("CE-001"), rendered);
        assertTrue(rendered.contains("amount=1200"), "the counterexample inputs must be shown: " + rendered);
        assertTrue(rendered.contains("expected: < 1000"), rendered);
        assertTrue(rendered.contains("actual:   1200"), rendered);
        assertTrue(rendered.contains("reproduce: mvn -q test -Dcodewitness.case=3"), rendered);
        assertTrue(rendered.contains("mutation testing was not measured"), rendered);
    }

    @Test
    void renderSaysSoWhenNothingWasVerified() {
        VerificationReport report = report(List.of(), List.of());

        String rendered = ConsoleReporter.render(report);

        assertTrue(rendered.contains("(none: no requirements were supplied)"), rendered);
        assertTrue(rendered.contains("none observed"), rendered);
        assertTrue(rendered.contains("overall      UNVERIFIED"),
                "an empty claim set is unverified, never verified: " + rendered);
    }

    @Test
    void renderCountsRequirementsByStatus() {
        VerificationReport report = report(
                List.of(verdict("R-001", RequirementStatus.VERIFIED),
                        verdict("R-002", RequirementStatus.FAILED),
                        verdict("R-003", RequirementStatus.FAILED)),
                List.of());

        String rendered = ConsoleReporter.render(report);

        assertEquals(RequirementStatus.FAILED, report.overallStatus());
        assertTrue(rendered.contains("VERIFIED"), rendered);
        assertTrue(rendered.contains("overall      FAILED"), rendered);
    }

    @Test
    void renderShowsTheTestCoverageAndMutationSummaries() {
        String rendered = ConsoleReporter.render(report(List.of(), List.of()));

        assertTrue(rendered.contains("Tests"), rendered);
        assertTrue(rendered.contains("tests not executed"), rendered);
        assertTrue(rendered.contains("Coverage"), rendered);
        assertTrue(rendered.contains("coverage not measured"), rendered);
        assertTrue(rendered.contains("Mutation testing"), rendered);
        assertTrue(rendered.contains("Static analysis"), rendered);
        assertTrue(rendered.contains("Dependencies"), rendered);
        assertTrue(rendered.contains("generated tests 4"), rendered);
    }

    // ------------------------------------------------------------------ helpers

    private VerificationReport report(List<RequirementVerdict> verdicts, List<Counterexample> counterexamples) {
        ProjectRef ref = ProjectRef.of(tmp);
        ProjectInfo project = ProjectInfo.builder(ref)
                .language(LanguageId.JAVA)
                .buildSystem(BuildSystemId.MAVEN)
                .addModule(new ModuleInfo("sample", tmp, "jar", List.of(), List.of(), "17"))
                .build();
        RequirementSet requirements = new RequirementSet(List.of(
                Requirement.builder("R-001", "at most 1000 per rolling 24 hours")
                        .title("Withdrawal limit").build(),
                Requirement.builder("R-002", "negative amounts are rejected")
                        .title("Negative amounts").build(),
                Requirement.builder("R-003", "the limit is a rolling window")
                        .title("Rolling window").build()));
        Instant start = Instant.parse("2026-10-06T10:00:00Z");
        VerificationRun run = new VerificationRun(
                RUN_ID, ref, RunStatus.COMPLETED, List.of(),
                new EnvironmentSnapshot("0.1.0", "17.0.11", "Eclipse Adoptium", "macOS 15", "maven", "3.9.9",
                        "abc1234", "req-hash", "config-hash", "none", "PROCESS", "isolated copy",
                        tmp.toString(), start, start.plusSeconds(3)),
                Budget.defaults(),
                new BudgetUsage(Duration.ofSeconds(3), 2, 4, 0, false, null),
                tmp, null, null, start, start.plusSeconds(3), List.of());
        return VerificationReport.builder(RUN_ID, project, requirements)
                .verdicts(verdicts)
                .counterexamples(counterexamples)
                .limitation("mutation testing was not measured")
                .run(run)
                .build();
    }

    private static RequirementVerdict verdict(String requirementId, RequirementStatus status) {
        return new RequirementVerdict(requirementId, status,
                status == RequirementStatus.FAILED
                        ? "a counterexample was observed"
                        : "evidence supports the requirement",
                List.of("EV-001"), List.of(), List.of(), List.of(), true);
    }

    private static Counterexample counterexample() {
        LinkedHashMap<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("amount", "1200");
        inputs.put("total", "1200");
        return new Counterexample(
                "CE-001", "R-001", "OB-001", "withdrawal limit exceeded",
                inputs, "< 1000", "1200", "total <= 1000", "generated test", "EV-001", 42L,
                new Counterexample.ReproductionRecipe("replay the failing case",
                        List.of("mvn", "-q", "test", "-Dcodewitness.case=3"), null, 42L, Map.of()),
                Counterexample.DiscoveryMethod.PROPERTY_SEARCH);
    }
}
