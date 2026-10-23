package dev.codewitness.core.engine;

import dev.codewitness.core.config.VerificationConfig;
import dev.codewitness.core.diagnostics.SelfIssueCategory;
import dev.codewitness.core.diagnostics.SelfIssueJournal;
import dev.codewitness.core.diagnostics.SelfIssueRecord;
import dev.codewitness.core.diagnostics.SelfIssueSeverity;
import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.Capability;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.RequirementSet;
import dev.codewitness.core.spi.LanguageAdapter;
import dev.codewitness.core.spi.LanguageAdapterRegistry;
import dev.codewitness.core.spi.LanguageDetectorRegistry;
import dev.codewitness.core.spi.StrategyOutcome;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationProviderRegistry;
import dev.codewitness.core.spi.VerificationStrategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A fault in the tool is recorded as well as reported.
 *
 * <p>The point of the self-issue journal is that the next fix request can start from evidence. These
 * tests pin the three places where the engine observes a fault it owns - inspection, a phase and a
 * strategy - and the one declared gap: a project no adapter can verify.
 */
class SelfIssueRecordingTest {

    @TempDir
    Path tmp;

    private static final RequirementSet REQUIREMENTS = new RequirementSet(List.of(
            Requirement.builder("R-001", "The system must behave.").title("Behave").build()));

    @Test
    @DisplayName("an inspection that throws is recorded as an internal error with its source frame")
    void inspectionFailureIsRecorded() {
        SelfIssueJournal journal = SelfIssueJournal.open(tmp.resolve("journal"));

        var report = engine(journal, ThrowingAdapter.throwingInspect(), VerificationProviderRegistry.empty())
                .verify(ProjectRef.of(tmp), REQUIREMENTS, VerificationConfig.defaults(), (phase, message) -> {
                });

        assertEquals(dev.codewitness.core.result.RunStatus.FAILED, report.run().status());
        SelfIssueRecord issue = only(journal);
        assertEquals(SelfIssueCategory.INTERNAL_ERROR, issue.category());
        assertEquals(SelfIssueSeverity.CRITICAL, issue.severity());
        assertEquals("inspect", issue.phase());
        assertNotNull(issue.source(), "the source frame is what a fix starts from");
        assertTrue(issue.source().contains("ThrowingAdapter.inspect"), issue.source());
        assertTrue(issue.detail().contains("inspection exploded"), issue.detail());
    }

    @Test
    @DisplayName("a phase that throws is recorded as a phase failure, and the run still completes honestly")
    void phaseFailureIsRecorded() {
        SelfIssueJournal journal = SelfIssueJournal.open(tmp.resolve("journal"));

        var report = engine(journal, ThrowingAdapter.throwingBuild(), VerificationProviderRegistry.empty())
                .verify(ProjectRef.of(tmp), REQUIREMENTS, VerificationConfig.defaults(), (phase, message) -> {
                });

        assertTrue(report.run().status() == dev.codewitness.core.result.RunStatus.BLOCKED
                        || report.run().status() == dev.codewitness.core.result.RunStatus.COMPLETED,
                "a failed phase must become a report, not an exception: " + report.run().status());
        SelfIssueRecord issue = only(journal);
        assertEquals(SelfIssueCategory.PHASE_FAILURE, issue.category());
        assertEquals(SelfIssueSeverity.HIGH, issue.severity());
        assertEquals("build", issue.phase());
        assertTrue(issue.source().contains("ThrowingAdapter.build"), issue.source());
    }

    @Test
    @DisplayName("a strategy that throws is recorded with its requirement and strategy id")
    void strategyFailureIsRecorded() {
        SelfIssueJournal journal = SelfIssueJournal.open(tmp.resolve("journal"));
        VerificationProviderRegistry providers = VerificationProviderRegistry.empty();
        providers.register(new ThrowingStrategy());

        engine(journal, ThrowingAdapter.inspectOnly(), providers)
                .verify(ProjectRef.of(tmp), REQUIREMENTS, VerificationConfig.defaults(), (phase, message) -> {
                });

        SelfIssueRecord issue = only(journal);
        assertEquals(SelfIssueCategory.STRATEGY_FAILURE, issue.category());
        assertEquals(SelfIssueSeverity.HIGH, issue.severity());
        assertEquals("boom-strategy", issue.strategy());
        assertEquals("R-001", issue.requirement());
        assertTrue(issue.source().contains("ThrowingStrategy.verify"), issue.source());
    }

    @Test
    @DisplayName("a project no adapter supports is recorded as a capability gap, not an exception")
    void unsupportedProjectIsRecordedAsCapabilityGap() {
        SelfIssueJournal journal = SelfIssueJournal.open(tmp.resolve("journal"));

        engine(journal, null, VerificationProviderRegistry.empty())
                .verify(ProjectRef.of(tmp), REQUIREMENTS, VerificationConfig.defaults(), (phase, message) -> {
                });

        SelfIssueRecord issue = only(journal);
        assertEquals(SelfIssueCategory.CAPABILITY_GAP, issue.category());
        assertEquals(SelfIssueSeverity.LOW, issue.severity());
        assertFalse(issue.message().contains("Exception"), issue.message());
    }

    private VerificationEngine engine(
            SelfIssueJournal journal,
            LanguageAdapter adapter,
            VerificationProviderRegistry providers) {
        LanguageAdapterRegistry languages = adapter == null
                ? LanguageAdapterRegistry.of()
                : LanguageAdapterRegistry.of(adapter);
        return new VerificationEngine(languages, providers, LanguageDetectorRegistry.of(),
                new dev.codewitness.core.exec.LocalProcessSandbox(List.of(tmp)).runner(), journal);
    }

    private static SelfIssueRecord only(SelfIssueJournal journal) {
        List<SelfIssueRecord> records = journal.records();
        assertEquals(1, records.size(), "one fault should produce one occurrence: " + records);
        return records.getFirst();
    }

    /** An adapter whose inspect or build can be made to throw on demand. */
    private static final class ThrowingAdapter implements LanguageAdapter {

        private final boolean throwInInspect;
        private final boolean throwInBuild;
        private final Set<Capability> capabilities;

        private ThrowingAdapter(boolean throwInInspect, boolean throwInBuild, Set<Capability> capabilities) {
            this.throwInInspect = throwInInspect;
            this.throwInBuild = throwInBuild;
            this.capabilities = capabilities;
        }

        static ThrowingAdapter throwingInspect() {
            return new ThrowingAdapter(true, false, Set.of(Capability.INSPECT));
        }

        static ThrowingAdapter throwingBuild() {
            return new ThrowingAdapter(false, true, Set.of(Capability.INSPECT, Capability.BUILD));
        }

        static ThrowingAdapter inspectOnly() {
            return new ThrowingAdapter(false, false, Set.of(Capability.INSPECT));
        }

        @Override
        public String id() {
            return "throwing";
        }

        @Override
        public boolean supports(ProjectRef project) {
            return true;
        }

        @Override
        public ProjectInfo inspect(ProjectRef project, VerificationContext context) {
            if (throwInInspect) {
                throw new IllegalStateException("inspection exploded");
            }
            return ProjectInfo.builder(project).build();
        }

        @Override
        public BuildResult build(VerificationContext context) {
            if (throwInBuild) {
                throw new IllegalStateException("build exploded");
            }
            return BuildResult.builder()
                    .attempted(true)
                    .success(true)
                    .exitCode(0)
                    .duration(Duration.ofMillis(1))
                    .command(List.of("fake", "build"))
                    .output("BUILD SUCCESS")
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
    }

    /** A strategy that throws instead of returning an outcome. */
    private static final class ThrowingStrategy implements VerificationStrategy {

        @Override
        public String id() {
            return "boom-strategy";
        }

        @Override
        public boolean supports(ProjectInfo project) {
            return true;
        }

        @Override
        public StrategyOutcome verify(
                VerificationContext context,
                Requirement requirement,
                List<dev.codewitness.core.requirements.VerificationObligation> obligations) {
            throw new IllegalStateException("strategy exploded");
        }
    }
}
