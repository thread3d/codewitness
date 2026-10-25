package dev.codewitness.core.engine;

import dev.codewitness.core.config.VerificationConfig;
import dev.codewitness.core.evidence.EvidenceStatus;
import dev.codewitness.core.evidence.EvidenceType;
import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.Capability;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ModuleInfo;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.RequirementSet;
import dev.codewitness.core.requirements.RequirementStatus;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.result.Budget;
import dev.codewitness.core.result.Counterexample;
import dev.codewitness.core.result.RunStatus;
import dev.codewitness.core.result.VerificationReport;
import dev.codewitness.core.spi.DatabaseFixture;
import dev.codewitness.core.spi.LanguageAdapter;
import dev.codewitness.core.spi.LanguageAdapterRegistry;
import dev.codewitness.core.spi.LanguageDetector;
import dev.codewitness.core.spi.LanguageDetectorRegistry;
import dev.codewitness.core.spi.PreparedDatabase;
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
import java.util.Map;
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
    @DisplayName("a failed build says no build succeeded, not that no analyzer is registered")
    void failedBuildExplainsWhyDependencyAndCoverageAnalysisDidNotRun() {
        // Both phases select languages by a successful build, so an empty selection is ambiguous: no
        // adapter declares the capability, or no language built. The second is an infrastructure answer
        // and must not be reported as the first, which would send a reader looking for an adapter that
        // is in fact registered.
        VerificationReport report = run(adapter(
                Set.of(Capability.INSPECT, Capability.BUILD, Capability.COVERAGE), false));

        assertTrue(report.dependencies().note().startsWith("no language's build succeeded"),
                "dependencies must name the failed build, not a missing analyzer: "
                        + report.dependencies().note());
        assertTrue(report.coverage().note().startsWith("no language's build succeeded"),
                "coverage must name the failed build, not a missing provider: " + report.coverage().note());
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

    @Test
    @DisplayName("a verification strategy registered as a provider runs, exactly like a tool")
    void aRegisteredStrategyRuns() {
        // Strategies used to be a list the engine built for itself, so an adapter or an embedder could
        // add a compiler, a test runner or an analyzer but never a strategy; the only way to run one was
        // to edit the engine. Registering one now has the same standing as registering a tool, which is
        // what a data-logic strategy - or any third party's - needs in order to exist outside this tree.
        VerificationProviderRegistry providers = VerificationProviderRegistry.empty();
        providers.register(new DeclaringStrategy());
        LanguageAdapterRegistry languages = LanguageAdapterRegistry.of(
                adapter(Set.of(Capability.INSPECT, Capability.BUILD), true));

        VerificationReport report = new VerificationEngine(languages, providers)
                .verify(ProjectRef.of(tmp), requirements(), VerificationConfig.defaults(),
                        (phase, message) -> { });

        assertEquals(RequirementStatus.FAILED, report.verdicts().getFirst().status(),
                "the registered strategy's counterexample must reach the verdict: " + report.verdicts());
    }

    @Test
    @DisplayName("a registry that has strategies is honoured even when none claims the project")
    void aRegistryWithStrategiesIsNotSubstituted() {
        // The default set is a fallback for an empty registry, not a floor under every run. A caller that
        // registers a strategy that declines this project has made a choice, and the engine must not
        // quietly add its own strategies on top - that is the difference between a fallback and a surprise.
        VerificationProviderRegistry providers = VerificationProviderRegistry.empty();
        providers.register(new DecliningStrategy());
        LanguageAdapterRegistry languages = LanguageAdapterRegistry.of(
                adapter(Set.of(Capability.INSPECT, Capability.BUILD), true));

        VerificationReport report = new VerificationEngine(languages, providers)
                .verify(ProjectRef.of(tmp), requirements(), VerificationConfig.defaults(),
                        (phase, message) -> { });

        assertEquals(RequirementStatus.UNVERIFIED, report.verdicts().getFirst().status(),
                "no registered strategy claimed the requirement, so nothing verified it: " + report.verdicts());
    }

    @Test
    @DisplayName("a database fixture is prepared and its result is on the context a strategy sees")
    void aDatabaseFixtureIsPreparedForStrategies() {
        // The point of the fixture SPI: a strategy in any language reaches the run's database through the
        // context, without the engine knowing which engine or driver is behind it.
        VerificationProviderRegistry providers = VerificationProviderRegistry.empty();
        providers.register(new RecordingDatabaseFixture(true));
        providers.register(new RequiresDatabaseStrategy());

        VerificationReport report = runProviders(
                List.of(adapter(Set.of(Capability.INSPECT, Capability.BUILD), true)), requirements(),
                configWithDatabase(true), providers);

        assertEquals(RequirementStatus.UNVERIFIED, report.verdicts().getFirst().status(),
                "the strategy saw a prepared database, so it had nothing to report: " + report.verdicts());
        assertTrue(report.evidence().stream().anyMatch(e -> e.type() == EvidenceType.DATABASE_FIXTURE
                && e.status() == EvidenceStatus.SUPPORTS), report.evidence().toString());
    }

    @Test
    @DisplayName("a database that was requested and not prepared is a limitation, not a silent absence")
    void anUnpreparedDatabaseIsReported() {
        VerificationProviderRegistry providers = VerificationProviderRegistry.empty();
        providers.register(new RequiresDatabaseStrategy());

        VerificationReport report = runProviders(
                List.of(adapter(Set.of(Capability.INSPECT, Capability.BUILD), true)), requirements(),
                configWithDatabase(true), providers);

        assertEquals(RequirementStatus.FAILED, report.verdicts().getFirst().status(),
                "nothing prepared a database and the strategy said so: " + report.verdicts());
        assertTrue(report.limitations().stream().anyMatch(l -> l.contains("A database was requested but none")),
                report.limitations().toString());
        assertTrue(report.evidence().stream().anyMatch(e -> e.type() == EvidenceType.DATABASE_FIXTURE
                && e.status() == EvidenceStatus.INCONCLUSIVE), report.evidence().toString());
    }

    /**
     * The shape an embedder registers: it claims the project and refutes the requirement with a single
     * counterexample, so whether it ran is visible in the verdict rather than only in a log.
     */
    private static final class DeclaringStrategy implements VerificationStrategy {

        @Override
        public String id() {
            return "declaring-strategy";
        }

        @Override
        public boolean supports(ProjectInfo project) {
            return true;
        }

        @Override
        public StrategyOutcome verify(VerificationContext context, Requirement requirement,
                List<VerificationObligation> obligations) {
            Counterexample counterexample = new Counterexample("CE-STRATEGY", requirement.id(), null,
                    "the registered strategy ran", Map.<String, Object>of(), "expected", "observed",
                    "the strategy must be consulted", "declaring-strategy", null, null, null,
                    Counterexample.DiscoveryMethod.STATIC_ANALYSIS);
            return new StrategyOutcome(id(), requirement.id(), List.of(), List.of(counterexample), List.of(),
                    List.of("the registered strategy ran"));
        }
    }

    /** A registered strategy that claims nothing, so the engine must run nothing. */
    private static final class DecliningStrategy implements VerificationStrategy {

        @Override
        public String id() {
            return "declining-strategy";
        }

        @Override
        public boolean supports(ProjectInfo project) {
            return false;
        }

        @Override
        public StrategyOutcome verify(VerificationContext context, Requirement requirement,
                List<VerificationObligation> obligations) {
            throw new AssertionError("a strategy that declines the project must never be called");
        }
    }

    /**
     * A database fixture an embedder might register: it prepares a database whose URL is a test value, so
     * the test can tell the difference between "a database was prepared" and "the pipeline ran anyway".
     */
    private static final class RecordingDatabaseFixture implements DatabaseFixture {

        private final boolean prepared;

        RecordingDatabaseFixture(boolean prepared) {
            this.prepared = prepared;
        }

        @Override
        public String id() {
            return "recording";
        }

        @Override
        public boolean supports(Request request) {
            return request.config().database().enabled();
        }

        @Override
        public PreparedDatabase prepare(Request request) {
            if (!prepared) {
                return PreparedDatabase.notPrepared(id(), "the test fixture declined to prepare one");
            }
            return PreparedDatabase.prepared(id(), "jdbc:sqlite::memory:",
                    Map.of(URL_VARIABLE, "jdbc:sqlite::memory:"), "a test database");
        }
    }

    /**
     * Refutes the requirement when the context carries no prepared database, so a run that prepared one is
     * distinguishable from a run that did not.
     */
    private static final class RequiresDatabaseStrategy implements VerificationStrategy {

        @Override
        public String id() {
            return "requires-database";
        }

        @Override
        public boolean supports(ProjectInfo project) {
            return true;
        }

        @Override
        public StrategyOutcome verify(VerificationContext context, Requirement requirement,
                List<VerificationObligation> obligations) {
            if (context.database().prepared()) {
                return StrategyOutcome.empty(id(), requirement.id());
            }
            Counterexample counterexample = new Counterexample("CE-NO-DB", requirement.id(), null,
                    "no database was prepared", Map.<String, Object>of(), "a database", "none",
                    "the requirement needs a database", "requires-database", null, null, null,
                    Counterexample.DiscoveryMethod.STATIC_ANALYSIS);
            return new StrategyOutcome(id(), requirement.id(), List.of(), List.of(counterexample), List.of(),
                    List.of("no database was prepared"));
        }
    }

    // ------------------------------------------------------------------ polyglot projects

    @Test
    @DisplayName("every adapter that claims part of a project is inspected and built")
    void aPolyglotProjectIsVerifiedByEveryAdapterThatClaimsIt() throws Exception {
        // A Java service with a TypeScript frontend is two verifiable projects in one tree. Selecting one
        // adapter for the whole tree verified one side and left the other absent from the report.
        LanguageAdapter service = fake("java", LanguageId.JAVA, "java", true);
        LanguageAdapter frontend = fake("node", LanguageId.TYPESCRIPT, "ts", true);

        VerificationReport report = run(service, frontend);

        assertEquals(LanguageId.POLYGLOT, report.project().language(),
                "a tree verified in two languages must not be reported as one of them");
        assertEquals(2, report.evidence().stream()
                        .filter(e -> e.type() == dev.codewitness.core.evidence.EvidenceType.COMPILER).count(),
                "each language's build is recorded in its own right: " + report.evidence());
        assertTrue(report.limitations().stream().anyMatch(l -> l.contains("polyglot")),
                report.limitations().toString());
        assertTrue(report.evidence().stream().anyMatch(e -> "java".equals(e.metadata().get("adapter"))));
        assertTrue(report.evidence().stream().anyMatch(e -> "node".equals(e.metadata().get("adapter"))));
    }

    @Test
    @DisplayName("a language that fails to build blocks its own requirements and not the other language's")
    void aFailedBuildBlocksOnlyTheLanguageThatFailed() throws Exception {
        LanguageAdapter service = fake("java", LanguageId.JAVA, "java", false);
        LanguageAdapter frontend = fake("node", LanguageId.TYPESCRIPT, "ts", true);

        VerificationReport report = run(List.of(service, frontend), requirements("R-JAVA", "R-TS"));

        assertEquals(RequirementStatus.BLOCKED, report.verdict("R-JAVA").orElseThrow().status(),
                "the Java requirement is blocked by the Java build: " + report.verdicts());
        assertTrue(report.verdict("R-TS").orElseThrow().status() != RequirementStatus.BLOCKED,
                "the TypeScript requirement is written in code that built, and a sibling language failing "
                        + "to compile says nothing about it: " + report.verdicts());
    }

    @Test
    @DisplayName("a configured adapter narrows a polyglot project to the one language asked for")
    void anExplicitAdapterNarrowsTheSelection() throws Exception {
        LanguageAdapter service = fake("java", LanguageId.JAVA, "java", true);
        LanguageAdapter frontend = fake("node", LanguageId.TYPESCRIPT, "ts", true);
        VerificationConfig config = VerificationConfig.defaults()
                .withLanguage(new VerificationConfig.Language("java"));

        VerificationReport report = run(List.of(service, frontend), requirements("R-JAVA", "R-TS"), config);

        assertEquals(LanguageId.JAVA, report.project().language());
        assertEquals(1, report.evidence().stream()
                .filter(e -> e.type() == dev.codewitness.core.evidence.EvidenceType.COMPILER).count());
    }

    @Test
    @DisplayName("a language nothing can verify appears in the report as present, not only in the limitations")
    void aLanguageWithNoAdapterIsReported() throws Exception {
        // The half of the polyglot problem that running more adapters cannot fix: a language present in the
        // tree that no adapter understands must still appear in the report. It is an entry in the same list
        // as the verified languages, so a reader cannot see one without seeing what was left out.
        java.nio.file.Files.writeString(tmp.resolve("Gemfile"), "source 'https://rubygems.org'\n");
        LanguageAdapter service = fake("java", LanguageId.JAVA, "java", true);
        LanguageDetectorRegistry detectors = LanguageDetectorRegistry.of(new LanguageDetector() {
            @Override
            public String id() {
                return "ruby";
            }

            @Override
            public String language() {
                return "Ruby";
            }

            @Override
            public java.util.Optional<String> evidence(java.nio.file.Path root) {
                return java.nio.file.Files.isRegularFile(root.resolve("Gemfile"))
                        ? java.util.Optional.of("Gemfile") : java.util.Optional.empty();
            }
        });

        VerificationReport report = run(List.of(service), requirements(), VerificationConfig.defaults(), detectors);

        assertTrue(report.limitations().stream().anyMatch(l -> l.contains("Ruby") && l.contains("Gemfile")),
                report.limitations().toString());
        assertEquals(List.of("java", "ruby"), report.languages().stream()
                .map(language -> language.language().id()).toList());
        VerificationReport.LanguageSummary ruby = report.languages().get(1);
        assertFalse(ruby.verified(), "nothing built, tested or analysed the Ruby");
        assertEquals("Gemfile", ruby.evidence());
        assertTrue(ruby.describe().contains("no adapter in this run can verify it"), ruby.describe());
    }

    @Test
    @DisplayName("a run whose requirements were all evaluated is COMPLETED even if a sibling language did not build")
    void aPolyglotRunIsNotBlockedByAnUnrelatedBuildFailure() throws Exception {
        // BLOCKED is what makes a truncated run exit non-zero. A language failing to build when no
        // requirement lives in it did not truncate anything, and calling that run blocked would be as wrong
        // as calling it clean.
        LanguageAdapter service = fake("java", LanguageId.JAVA, "java", false);
        LanguageAdapter frontend = fake("node", LanguageId.TYPESCRIPT, "ts", true);

        VerificationReport report = run(List.of(service, frontend), requirements("R-TS"));

        assertEquals(RequirementStatus.UNVERIFIED, report.verdict("R-TS").orElseThrow().status());
        assertEquals(RunStatus.COMPLETED, report.run().status(),
                "no requirement was blocked, so the run was not truncated: " + report.limitations());
        assertTrue(report.limitations().stream().anyMatch(l -> l.contains("Not every language")),
                report.limitations().toString());
    }

    // ------------------------------------------------------------------ fake adapter

    private static RequirementSet requirements() {
        return new RequirementSet(List.of(REQUIREMENT));
    }

    /** One requirement per id, each mentioned by a source file in the fake adapter's language. */
    private static RequirementSet requirements(String... ids) {
        List<Requirement> requirements = new java.util.ArrayList<>();
        for (String id : ids) {
            requirements.add(Requirement.builder(id, "The system must behave.").title(id).build());
        }
        return new RequirementSet(requirements);
    }

    private VerificationReport run(LanguageAdapter adapter) {
        return run(adapter, VerificationConfig.defaults());
    }

    private VerificationReport run(LanguageAdapter adapter, VerificationConfig config) {
        return run(List.of(adapter), requirements(), config);
    }

    private VerificationReport run(LanguageAdapter first, LanguageAdapter second) {
        return run(List.of(first, second), requirements(), VerificationConfig.defaults());
    }

    private VerificationReport run(List<LanguageAdapter> adapters, RequirementSet requirements) {
        return run(adapters, requirements, VerificationConfig.defaults());
    }

    private VerificationReport run(List<LanguageAdapter> adapters, RequirementSet requirements,
            VerificationConfig config) {
        return run(adapters, requirements, config, LanguageDetectorRegistry.of());
    }

    private VerificationReport run(List<LanguageAdapter> adapters, RequirementSet requirements,
            VerificationConfig config, LanguageDetectorRegistry detectors) {
        LanguageAdapterRegistry registry = new LanguageAdapterRegistry();
        adapters.forEach(registry::register);
        return new VerificationEngine(registry, VerificationProviderRegistry.empty(), detectors,
                new dev.codewitness.core.exec.LocalProcessSandbox(List.of(tmp)).runner())
                .verify(ProjectRef.of(tmp), requirements, config, (phase, message) -> { });
    }

    /** Runs with a registry the test filled itself, which is what a database fixture needs. */
    private VerificationReport runProviders(List<LanguageAdapter> adapters, RequirementSet requirements,
            VerificationConfig config, VerificationProviderRegistry providers) {
        LanguageAdapterRegistry registry = new LanguageAdapterRegistry();
        adapters.forEach(registry::register);
        return new VerificationEngine(registry, providers, LanguageDetectorRegistry.of(),
                new dev.codewitness.core.exec.LocalProcessSandbox(List.of(tmp)).runner())
                .verify(ProjectRef.of(tmp), requirements, config, (phase, message) -> { });
    }

    /** The defaults with only the database section changed, so a test says one thing at a time. */
    private static VerificationConfig configWithDatabase(boolean enabled) {
        VerificationConfig defaults = VerificationConfig.defaults();
        return new VerificationConfig(defaults.project(), defaults.language(), defaults.limits(), defaults.tests(),
                defaults.mutation(), defaults.propertyTesting(), defaults.staticAnalysis(), defaults.security(),
                defaults.llm(), defaults.report(), defaults.sandbox(), defaults.scenarios(),
                new VerificationConfig.Database(enabled, "sqlite", null, null, null, Duration.ofSeconds(30)));
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
                return buildResult(buildSucceeds);
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

    /**
     * A language adapter that claims the project, writes one source file per requirement id into the
     * project, and reports the language and extension it speaks for.
     *
     * <p>Real adapters decide which files matter by extension; the requirement mapper and the engine's
     * per-language blocking both rely on that, so the fake has to answer the same question the same way.
     */
    private LanguageAdapter fake(String id, LanguageId language, String extension, boolean buildSucceeds) {
        return new LanguageAdapter() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public boolean supports(ProjectRef project) {
                return true;
            }

            @Override
            public ProjectInfo inspect(ProjectRef project, VerificationContext context) {
                Path source = tmp.resolve(id + "-source." + extension);
                try {
                    java.nio.file.Files.writeString(source, fileBodyFor(language));
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                return ProjectInfo.builder(project)
                        .language(language)
                        .sourceExtensions(Set.of(extension))
                        .addModule(new ModuleInfo(id, tmp, "directory", List.of(tmp), List.of(), null))
                        .build();
            }

            @Override
            public BuildResult build(VerificationContext context) {
                return buildResult(buildSucceeds);
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
                return Set.of(Capability.INSPECT, Capability.BUILD);
            }
        };
    }

    /** @return the source text that makes the requirement ids of this test map to this language's file. */
    private static String fileBodyFor(LanguageId language) {
        return language.equals(LanguageId.JAVA) ? "class Service { // R-JAVA\n}" : "// R-TS\nexport {};\n";
    }

    private static BuildResult buildResult(boolean succeeds) {
        return BuildResult.builder()
                .attempted(true)
                .success(succeeds)
                .exitCode(succeeds ? 0 : 1)
                .duration(Duration.ofMillis(1))
                .command(List.of("fake", "build"))
                .output(succeeds ? "BUILD SUCCESS" : "error: cannot find symbol")
                .failureReason(succeeds ? null : "the compiler rejected the project")
                .build();
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
