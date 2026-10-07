package dev.codewitness.core.engine;

import dev.codewitness.core.config.VerificationConfig;
import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.Capability;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.RequirementSet;
import dev.codewitness.core.requirements.RequirementStatus;
import dev.codewitness.core.result.Budget;
import dev.codewitness.core.result.RunStatus;
import dev.codewitness.core.result.VerificationReport;
import dev.codewitness.core.spi.LanguageAdapter;
import dev.codewitness.core.spi.LanguageAdapterRegistry;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationProviderRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The verification engine's contract, driven through a fake adapter.
 *
 * <p>{@link VerificationEngine} is the pipeline itself, and until now it had no test at all - every
 * rule it enforces was pinned only indirectly, through the strategies and the opt-in end-to-end suite.
 * The properties tested here are the ones the project states as ground rules, so a regression in any
 * of them is a regression in what the tool promises rather than in what it computes.
 *
 * <p>Each case uses the smallest adapter that can produce the situation: the engine only schedules the
 * phases an adapter declares, so an adapter that declares nothing but {@code INSPECT} and {@code BUILD}
 * keeps the run to the part under test.
 */
class VerificationEngineTest {

    @TempDir
    Path tmp;

    private static final Requirement REQUIREMENT =
            Requirement.builder("R-001", "The system must behave.").title("Behave").build();

    @Test
    @DisplayName("a failed build is BLOCKED for every requirement, never FAILED")
    void failedBuildBlocksEveryRequirement() {
        // Ground rule 2. A broken build says nothing about the code, so it must not be reported as a
        // defect - and it must not be quietly turned into a pass either.
        VerificationReport report = run(adapter(Set.of(Capability.INSPECT, Capability.BUILD), false));

        assertEquals(List.of(RequirementStatus.BLOCKED),
                report.verdicts().stream().map(v -> v.status()).toList());
        assertEquals(RunStatus.BLOCKED, report.run().status());
        assertTrue(report.limitations().stream().anyMatch(l -> l.contains("could not be built")),
                report.limitations().toString());
        assertFalse(report.verdicts().getFirst().rationale().isBlank(),
                "a blocked verdict must say why");
    }

    @Test
    @DisplayName("an exhausted budget makes the run BLOCKED, never COMPLETED")
    void exhaustedBudgetIsBlockedNotCompleted() {
        // A truncated run must not look like a finished one. The budget used to be consulted only after
        // every phase had already run, and exhaustion produced ABORTED, which mapped to exit code 0.
        VerificationConfig config = VerificationConfig.defaults()
                .withBudget(Budget.defaults().withMaxDuration(Duration.ofNanos(1)));

        VerificationReport report = run(adapter(Set.of(Capability.INSPECT, Capability.BUILD), true), config);

        assertEquals(RunStatus.BLOCKED, report.run().status(),
                "a run that ran out of time did not complete");
        assertTrue(report.run().usage().exhausted(), "the report must record that the budget ran out");
        assertTrue(report.limitations().stream().anyMatch(l -> l.contains("budget was exhausted")),
                report.limitations().toString());
    }

    @Test
    @DisplayName("no adapter for the project blocks every requirement instead of reporting nothing")
    void noAdapterBlocksEveryRequirement() {
        // An unsupported language is an infrastructure problem, not a clean result. The report must still
        // list the requirements, each BLOCKED, so a reader sees what was not checked.
        LanguageAdapterRegistry languages = LanguageAdapterRegistry.of(new UnsupportedAdapter());

        VerificationReport report = new VerificationEngine(languages, VerificationProviderRegistry.empty())
                .verify(ProjectRef.of(tmp), requirements(), VerificationConfig.defaults(),
                        (phase, message) -> { });

        assertEquals(1, report.verdicts().size());
        assertEquals(RequirementStatus.BLOCKED, report.verdicts().getFirst().status());
        assertEquals(RunStatus.BLOCKED, report.run().status());
        assertTrue(report.limitations().stream().anyMatch(l -> l.contains("No language adapter")),
                report.limitations().toString());
    }

    @Test
    @DisplayName("a requirement with nothing executable stays UNVERIFIED once the build succeeds")
    void buildThatSucceedsLeavesTheRequirementUnverified() {
        // The positive control for the two cases above: the pipeline runs, the build succeeds, and the
        // answer is still "not verified" - because nothing executed and no obligation was discharged.
        VerificationReport report = run(adapter(Set.of(Capability.INSPECT, Capability.BUILD), true));

        assertEquals(RunStatus.COMPLETED, report.run().status());
        assertEquals(RequirementStatus.UNVERIFIED, report.verdicts().getFirst().status());
        assertTrue(report.evidence().stream().anyMatch(e -> e.type()
                        == dev.codewitness.core.evidence.EvidenceType.PROJECT_INSPECTION),
                "inspection is always recorded");
    }

    // ------------------------------------------------------------------ fake adapter

    private static RequirementSet requirements() {
        return new RequirementSet(List.of(REQUIREMENT));
    }

    private VerificationReport run(LanguageAdapter adapter) {
        return run(adapter, VerificationConfig.defaults());
    }

    private VerificationReport run(LanguageAdapter adapter, VerificationConfig config) {
        return new VerificationEngine(LanguageAdapterRegistry.of(adapter), VerificationProviderRegistry.empty())
                .verify(ProjectRef.of(tmp), requirements(), config, (phase, message) -> { });
    }

    /** An adapter that can be told to succeed or fail its build, and that declares nothing else. */
    private LanguageAdapter adapter(Set<Capability> capabilities, boolean buildSucceeds) {
        return new LanguageAdapter() {
            @Override
            public String id() {
                return "fake";
            }

            @Override
            public boolean supports(ProjectRef project) {
                return true;
            }

            @Override
            public ProjectInfo inspect(ProjectRef project, VerificationContext context) {
                return ProjectInfo.builder(project).build();
            }

            @Override
            public BuildResult build(VerificationContext context) {
                return BuildResult.builder()
                        .attempted(true)
                        .success(buildSucceeds)
                        .exitCode(buildSucceeds ? 0 : 1)
                        .duration(Duration.ofMillis(1))
                        .command(List.of("fake", "build"))
                        .output(buildSucceeds ? "BUILD SUCCESS" : "error: cannot find symbol")
                        .failureReason(buildSucceeds ? null : "the compiler rejected the project")
                        .build();
            }

            @Override
            public TestResult runTests(VerificationContext context) {
                return TestResult.notExecuted("this adapter declares no test capability");
            }

            @Override
            public List<Finding> analyze(VerificationContext context) {
                return List.of();
            }

            @Override
            public Set<Capability> capabilities() {
                return capabilities;
            }
        };
    }

    /** An adapter that exists but does not claim the project. */
    private static final class UnsupportedAdapter implements LanguageAdapter {
        @Override
        public String id() {
            return "unsupported";
        }

        @Override
        public boolean supports(ProjectRef project) {
            return false;
        }

        @Override
        public ProjectInfo inspect(ProjectRef project, VerificationContext context) {
            throw new AssertionError("an unsupported adapter must never be asked to inspect");
        }

        @Override
        public BuildResult build(VerificationContext context) {
            throw new AssertionError("an unsupported adapter must never be asked to build");
        }

        @Override
        public TestResult runTests(VerificationContext context) {
            throw new AssertionError("an unsupported adapter must never be asked to test");
        }

        @Override
        public List<Finding> analyze(VerificationContext context) {
            throw new AssertionError("an unsupported adapter must never be asked to analyse");
        }
    }
}
