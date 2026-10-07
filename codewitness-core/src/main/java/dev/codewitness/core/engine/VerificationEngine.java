package dev.codewitness.core.engine;

import dev.codewitness.core.config.VerificationConfig;
import dev.codewitness.core.engine.strategy.DependencyAdvisoryStrategy;
import dev.codewitness.core.engine.strategy.ExistingTestsStrategy;
import dev.codewitness.core.engine.strategy.GeneratedTestsStrategy;
import dev.codewitness.core.engine.strategy.LlmAdvisoryStrategy;
import dev.codewitness.core.engine.strategy.MutationStrategy;
import dev.codewitness.core.engine.strategy.StaticAnalysisStrategy;
import dev.codewitness.core.evidence.Evidence;
import dev.codewitness.core.evidence.EvidenceConfidence;
import dev.codewitness.core.evidence.EvidenceLedger;
import dev.codewitness.core.evidence.EvidenceStatus;
import dev.codewitness.core.evidence.EvidenceType;
import dev.codewitness.core.exec.ExecutionSandbox;
import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessRunner;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.io.ArtifactStore;
import dev.codewitness.core.io.WorkspaceManager;
import dev.codewitness.core.llm.LlmProvider;
import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.Capability;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.DiagnosticSeverity;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.RunId;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.model.SurvivingMutant;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.requirements.ObligationKind;
import dev.codewitness.core.requirements.ObligationOrigin;
import dev.codewitness.core.requirements.ObligationPlanner;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.RequirementSet;
import dev.codewitness.core.requirements.RequirementStatus;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.result.BudgetUsage;
import dev.codewitness.core.result.Counterexample;
import dev.codewitness.core.result.EnvironmentSnapshot;
import dev.codewitness.core.result.ObligationOutcome;
import dev.codewitness.core.result.PhaseRecord;
import dev.codewitness.core.result.RequirementVerdict;
import dev.codewitness.core.result.RunStatus;
import dev.codewitness.core.result.VerificationReport;
import dev.codewitness.core.result.VerificationRun;
import dev.codewitness.core.spi.LanguageAdapter;
import dev.codewitness.core.spi.LanguageAdapterRegistry;
import dev.codewitness.core.spi.ProgressListener;
import dev.codewitness.core.spi.RunState;
import dev.codewitness.core.spi.StrategyOutcome;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationProviderRegistry;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.core.spi.VerificationStrategy;
import dev.codewitness.core.vulnerability.OsvAdvisoryScanner;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The verification pipeline.
 *
 * <p>Phases, in order: inspect, build, existing tests, static analysis, security and dependency
 * analysis, coverage, mutation testing, obligation planning, generated tests, counterexample
 * extraction, requirement mapping and verdict computation.
 *
 * <p>Three behaviours are worth calling out because they are where a verification tool usually
 * becomes dishonest:
 *
 * <ul>
 *   <li>a phase that cannot run records an infrastructure note and leaves the requirements it would
 *       have covered {@code UNVERIFIED} or {@code BLOCKED} - never {@code VERIFIED};</li>
 *   <li>the engine never writes the word "verified" for a requirement unless
 *       {@link VerdictCalculator} produced that status from executed evidence;</li>
 *   <li>every phase is bounded and every phase result is recorded, so a truncated run is visibly
 *       truncated rather than silently partial.</li>
 * </ul>
 */
public final class VerificationEngine {

    /** Version recorded in every report. */
    public static final String VERSION = "0.1.0";

    private final LanguageAdapterRegistry languages;
    private final VerificationProviderRegistry providers;
    private final ProcessRunner hostRunner;

    public VerificationEngine(LanguageAdapterRegistry languages, VerificationProviderRegistry providers) {
        this(languages, providers, new dev.codewitness.core.exec.LocalProcessSandbox(List.of(Path.of("."))).runner());
    }

    public VerificationEngine(
            LanguageAdapterRegistry languages, VerificationProviderRegistry providers, ProcessRunner hostRunner) {
        this.languages = languages;
        this.providers = providers;
        this.hostRunner = hostRunner;
    }

    /** Runs the pipeline with an isolated workspace copy. */
    public VerificationReport verify(
            ProjectRef project,
            RequirementSet requirements,
            VerificationConfig config,
            ProgressListener listener) {
        return verify(project, requirements, config, listener, null, WorkspaceManager.Mode.COPY);
    }

    /**
     * Runs the whole verification pipeline.
     *
     * @param project      project under verification
     * @param requirements requirements to verify (possibly empty)
     * @param config       effective configuration
     * @param listener     progress listener
     * @param outputRoot   directory to keep run data in, or {@code null} for {@code <project>/.codewitness}
     * @param workspaceMode copy the project into the run directory, or operate in place
     * @return the report, including every verdict and all evidence
     */
    public VerificationReport verify(
            ProjectRef project,
            RequirementSet requirements,
            VerificationConfig config,
            ProgressListener listener,
            Path outputRoot,
            WorkspaceManager.Mode workspaceMode) {

        Instant startedAt = Instant.now();
        RunId runId = RunId.generate();
        Path base = outputRoot != null ? outputRoot
                : (config.report().outputDirectory() != null ? config.report().outputDirectory()
                        : project.root().resolve(".codewitness"));
        Path runDirectory = base.resolve("runs").resolve(runId.value());

        ArtifactStore artifacts = new ArtifactStore(runDirectory);
        EvidenceLedger ledger = new EvidenceLedger(runId.value());
        RunState state = new RunState();
        BudgetUsage.Tracker budget = new BudgetUsage.Tracker(config.toBudget());
        List<PhaseRecord> phases = new ArrayList<>();
        List<String> limitations = new ArrayList<>();

        listener.onEvent("start", "run " + runId + " for " + project.name() + " in " + runDirectory);

        WorkspaceManager.Workspace workspace = WorkspaceManager.prepare(project, runDirectory, workspaceMode);
        if (!workspace.isIsolated()) {
            limitations.add("The run operated in place: " + workspace.description());
        }

        Optional<LanguageAdapter> adapter = languages.select(project, config.language().adapter());
        if (adapter.isEmpty()) {
            limitations.add("No language adapter supports this project. Registered adapters: " + languages.ids());
            listener.onEvent("inspect", "no language adapter matched");
            Evidence evidence = ledger.add(ledger.newEvidence(EvidenceType.PROJECT_INSPECTION)
                    .description("No language adapter supports " + project.root())
                    .confidence(EvidenceConfidence.DETERMINISTIC)
                    .status(EvidenceStatus.INCONCLUSIVE)
                    .result("no adapter")
                    .build());
            return blockedReport(runId, project, requirements, config, workspace, state, ledger, phases, budget,
                    limitations, startedAt, evidence, "No language adapter supports this project.");
        }

        ExecutionSandbox sandbox = SandboxFactory.create(config, workspace.root(), runDirectory, hostRunner);
        limitations.add("Execution isolation: " + sandbox.isolationLevel() + " via " + sandbox.id() + ". "
                + sandbox.description());

        ProgressListener scoped = (phase, message) -> listener.onEvent(phase, message);

        // ---------------------------------------------------------------- inspect
        Instant inspectStart = Instant.now();
        ProjectInfo info;
        try {
            VerificationContext inspectContext = context(runId, project, emptyInfo(project), config, workspace,
                    artifacts, budget, requirements, LlmProvider.disabled("not initialised"), scoped, sandbox, state,
                    ledger, startedAt);
            info = adapter.get().inspect(project, inspectContext);
        } catch (RuntimeException e) {
            phases.add(new PhaseRecord("inspect", true, false, Duration.between(inspectStart, Instant.now()),
                    "inspection failed: " + e, List.of(), e.toString()));
            return failedReport(runId, project, requirements, config, workspace, state, ledger, phases, budget,
                    limitations, startedAt, "Project inspection failed: " + e);
        }
        Evidence inspection = ledger.add(ledger.newEvidence(EvidenceType.PROJECT_INSPECTION)
                .description("Detected " + info.describe())
                .confidence(EvidenceConfidence.DETERMINISTIC)
                .status(EvidenceStatus.SUPPORTS)
                .result(info.describe())
                .metadata("language", info.language().id())
                .metadata("buildSystem", info.buildSystem().id())
                .metadata("modules", info.modules().stream().map(m -> m.name()).toList())
                .metadata("testFrameworks", List.copyOf(info.testFrameworks()))
                .metadata("javaVersion", info.javaVersion().orElse("undeclared"))
                .build());
        phases.add(new PhaseRecord("inspect", true, true, Duration.between(inspectStart, Instant.now()),
                info.describe(), List.of(inspection.id()), null));
        listener.onEvent("inspect", info.describe());

        VerificationContext context = context(runId, project, info, config, workspace, artifacts, budget,
                requirements, llm(config), scoped, sandbox, state, ledger, startedAt);

        // ---------------------------------------------------------------- build
        if (adapter.get().capabilities().contains(Capability.BUILD) && withinBudget(budget)) {
            phases.add(runPhase("build", listener, () -> {
                BuildResult build = firstCompiler(context)
                        .map(c -> c.build(context))
                        .orElseGet(() -> adapter.get().build(context));
                state.build(build);
                List<Finding> diagnosticFindings = diagnosticsToFindings(build);
                state.addFindings(diagnosticFindings);
                Evidence evidence = ledger.add(ledger.newEvidence(EvidenceType.COMPILER)
                        .description("Build: " + build.describe())
                        .command(build.command())
                        .confidence(EvidenceConfidence.DETERMINISTIC)
                        .status(build.success() ? EvidenceStatus.SUPPORTS : EvidenceStatus.INCONCLUSIVE)
                        .result(build.describe())
                        .output(tail(build.output(), 8000))
                        .metadata("exitCode", build.exitCode())
                        .metadata("errors", build.errors().size())
                        .metadata("warnings", build.warnings().size())
                        .metadata("artifacts", build.artifacts().stream().map(Path::toString).toList())
                        .build());
                if (!build.attempted()) {
                    state.note("build not attempted: " + build.failureReason());
                }
                return new PhaseOutcome(build.success(), build.describe(), List.of(evidence.id()),
                        build.attempted() ? null : build.failureReason());
            }));
        } else {
            phases.add(new PhaseRecord("build", false, true, Duration.ZERO, "adapter declares no build capability",
                    List.of(), "skipped"));
        }

        boolean buildBlocked = !state.build().success();
        if (buildBlocked) {
            limitations.add("The project could not be built, so nothing could be verified: "
                    + state.build().describe() + ". A failed or impossible build is reported as BLOCKED, "
                    + "not as a code defect.");
        }

        // ---------------------------------------------------------------- existing tests
        if (!buildBlocked && config.tests().enabled()
                && adapter.get().capabilities().contains(Capability.TEST) && withinBudget(budget)) {
            phases.add(runPhase("tests", listener, () -> {
                Optional<VerificationTools.TestRunner> testRunner = firstTestRunner(context);
                TestResult tests = testRunner
                        .map(r -> r.runTests(context))
                        .orElseGet(() -> adapter.get().runTests(context));
                // Evidence must name the command that produced it. The build command is not that
                // command: it compiles without running tests, so recording it would describe an
                // invocation that cannot have yielded the result below.
                List<String> testCommand = testRunner.map(r -> r.testCommand(context)).orElseGet(List::of);
                state.existingTests(tests);
                // describeWithNote, not describe: the note is how the runner says that what it parsed is
                // not the whole story, and this record is what the report shows.
                Evidence evidence = ledger.add(ledger.newEvidence(EvidenceType.TEST)
                        .description("Existing tests: " + tests.describeWithNote())
                        .command(testCommand)
                        .confidence(EvidenceConfidence.DETERMINISTIC)
                        .status(!tests.executed() ? EvidenceStatus.INCONCLUSIVE
                                : (tests.anyFailure() ? EvidenceStatus.REFUTES : EvidenceStatus.SUPPORTS))
                        .result(tests.describeWithNote())
                        .output(tail(renderFailures(tests), 8000))
                        .metadata("reports", tests.reports().stream().map(Path::toString).toList())
                        .build());
                if (!tests.executed()) {
                    state.note("existing tests not executed: " + tests.note());
                }
                return new PhaseOutcome(tests.executed(), tests.describe(), List.of(evidence.id()),
                        tests.executed() ? null : tests.note());
            }));
        }

        // ---------------------------------------------------------------- static analysis
        if (!buildBlocked && config.staticAnalysis().enabled() && withinBudget(budget)) {
            phases.add(runPhase("static-analysis", listener, () -> {
                // Registered providers are the extension point, and every bundled adapter registers
                // one. Calling adapter.analyze() as well ran the whole pass twice - including a second
                // -Xlint:all compile - for findings that were then deduplicated away. The adapter level
                // entry point is kept as a fallback for an adapter that registers no provider.
                List<Finding> findings = new ArrayList<>();
                var analyzers = providers.staticAnalyzers(info);
                if (analyzers.isEmpty()) {
                    findings.addAll(adapter.get().analyze(context));
                } else {
                    for (var analyzer : analyzers) {
                        findings.addAll(analyzer.analyze(context));
                    }
                }
                // The adapter and the registered providers may report the same rule through different
                // paths; a finding is reported once, keyed by analyzer, rule, location and message.
                findings = deduplicateFindings(findings);
                state.addFindings(findings);
                // Record that the phase ran, not just what it found: a clean run and a run where nothing
                // could execute must not look the same to the strategies.
                state.staticAnalysisRan(true);
                Path report = artifacts.write("static-analysis/findings.json", renderFindings(findings));
                Evidence evidence = ledger.add(ledger.newEvidence(EvidenceType.STATIC_ANALYSIS)
                        .description("Static analysis: " + findings.size() + " finding(s)")
                        .source("static-analysis")
                        .confidence(EvidenceConfidence.DETERMINISTIC)
                        .status(findings.isEmpty() ? EvidenceStatus.SUPPORTS : EvidenceStatus.INCONCLUSIVE)
                        .result(summariseFindings(findings))
                        .metadata("bySeverity", summariseSeverity(findings))
                        .artifact(report)
                        .build());
                return new PhaseOutcome(true, summariseFindings(findings), List.of(evidence.id()), null);
            }));
        }

        if (!buildBlocked && config.security().enabled() && withinBudget(budget)) {
            phases.add(runPhase("security", listener, () -> {
                List<Finding> findings = new ArrayList<>();
                for (var analyzer : providers.securityAnalyzers(info)) {
                    findings.addAll(analyzer.analyze(context));
                }
                findings = deduplicateFindings(findings);
                state.addFindings(findings);
                Evidence evidence = ledger.add(ledger.newEvidence(EvidenceType.SECURITY_SCAN)
                        .description("Security analysis: " + findings.size() + " finding(s)")
                        .confidence(EvidenceConfidence.DETERMINISTIC)
                        .status(findings.isEmpty() ? EvidenceStatus.SUPPORTS : EvidenceStatus.INCONCLUSIVE)
                        .result(summariseFindings(findings))
                        .metadata("tools", providers.securityAnalyzers(info).stream().map(a -> a.id()).toList())
                        .build());
                return new PhaseOutcome(true, summariseFindings(findings), List.of(evidence.id()), null);
            }));
        }

        if (!buildBlocked && withinBudget(budget)) {
            phases.add(runPhase("dependencies", listener, () -> {
                List<VerificationTools.DependencyAnalyzer> analyzers = providers.dependencyAnalyzers(info);
                DependencyReport report = analyzers.isEmpty()
                        ? DependencyReport.notAnalysed("none", "no dependency analyzer registered")
                        : analyzers.getFirst().analyseDependencies(context);
                List<String> dependencyCommand = analyzers.isEmpty()
                        ? List.of() : analyzers.getFirst().dependencyCommand(context);
                DependencyReport scanned = report;
                List<Path> advisoryArtifacts = new ArrayList<>();
                if (report.analysed() && config.security().vulnerabilities().enabled()) {
                    OsvAdvisoryScanner.Scan scan = OsvAdvisoryScanner.scan(
                            config.security().vulnerabilities(), report.directDependencies(), artifacts);
                    scanned = report.withAdvisoryScan(scan.coverage(), scan.vulnerabilities());
                    advisoryArtifacts.addAll(scan.artifacts());
                    listener.onEvent("dependencies", "advisory scan: " + scan.coverage().note());
                }
                state.dependencies(scanned);
                Evidence evidence = ledger.add(ledger.newEvidence(EvidenceType.DEPENDENCY_SCAN)
                        .description("Dependency analysis: " + scanned.totalDependencies() + " dependencies; "
                                + (scanned.advisoryScan().performed()
                                        ? scanned.vulnerabilities().size() + " known vulnerabilities"
                                        : "vulnerabilities not checked"))
                        .command(dependencyCommand)
                        .confidence(EvidenceConfidence.DETERMINISTIC)
                        .status(EvidenceStatus.INCONCLUSIVE)
                        .result(scanned.analysed() ? scanned.totalDependencies() + " dependencies"
                                : "not analysed: " + scanned.note())
                        .output(scanned.advisoryScan().performed()
                                ? "advisory source " + scanned.advisoryScan().source() + " at "
                                        + scanned.advisoryScan().endpoint() + ": " + scanned.advisoryScan().note()
                                : "no advisory source was consulted: " + scanned.advisoryScan().note())
                        .metadata("vulnerabilities", scanned.vulnerabilities().size())
                        .metadata("vulnerabilitiesChecked", scanned.advisoryScan().performed())
                        .metadata("vulnerabilitySource", scanned.advisoryScan().source())
                        .artifacts(advisoryArtifacts)
                        .build());
                return new PhaseOutcome(scanned.analysed(), scanned.analysed()
                        ? scanned.totalDependencies() + " dependencies" : scanned.note(),
                        List.of(evidence.id()), scanned.analysed() ? null : scanned.note());
            }));
        }

        // ---------------------------------------------------------------- coverage
        if (!buildBlocked && adapter.get().capabilities().contains(Capability.COVERAGE)
                && withinBudget(budget)) {
            phases.add(runPhase("coverage", listener, () -> {
                List<VerificationTools.CoverageProvider> coverageProviders = providers.coverageProviders(info);
                CoverageReport coverage = coverageProviders.isEmpty()
                        ? CoverageReport.notMeasured("none", "no coverage provider registered")
                        : coverageProviders.getFirst().measure(context);
                state.coverage(coverage);
                Evidence evidence = ledger.add(ledger.newEvidence(EvidenceType.COVERAGE)
                        .description("Coverage: " + coverage.describe())
                        .confidence(EvidenceConfidence.DETERMINISTIC)
                        .status(coverage.measured() ? EvidenceStatus.SUPPORTS : EvidenceStatus.INCONCLUSIVE)
                        .result(coverage.describe())
                        .metadata("lineCoveragePercent", coverage.lineCoveragePercent())
                        .metadata("branchCoveragePercent", coverage.branchCoveragePercent())
                        .artifacts(coverage.reports())
                        .build());
                if (!coverage.measured()) {
                    state.note("coverage not measured: " + coverage.note());
                }
                return new PhaseOutcome(coverage.measured(), coverage.describe(), List.of(evidence.id()),
                        coverage.measured() ? null : coverage.note());
            }));
        }

        // ---------------------------------------------------------------- mutation
        if (!buildBlocked && config.mutation().enabled() && state.existingTests().executed()
                && withinBudget(budget)) {
            phases.add(runPhase("mutation", listener, () -> {
                List<VerificationTools.MutationTester> testers = providers.mutationTesters(info);
                MutationReport report = testers.isEmpty()
                        ? MutationReport.notMeasured("none", "no mutation tester registered")
                        : testers.getFirst().analyse(context);
                state.mutation(report);
                Evidence evidence = ledger.add(ledger.newEvidence(EvidenceType.MUTATION_TEST)
                        .description("Mutation testing: " + report.describe())
                        .confidence(EvidenceConfidence.DETERMINISTIC)
                        .status(!report.measured() ? EvidenceStatus.INCONCLUSIVE
                                : (report.survivors().isEmpty() ? EvidenceStatus.SUPPORTS : EvidenceStatus.INCONCLUSIVE))
                        .result(report.describe())
                        .metadata("scorePercent", report.scorePercent())
                        .metadata("survivors", report.survivors().stream().limit(50)
                                .map(SurvivingMutant::location).toList())
                        .artifacts(report.reports())
                        .build());
                if (!report.measured()) {
                    state.note("mutation testing did not run: " + report.note());
                }
                return new PhaseOutcome(report.measured(), report.describe(), List.of(evidence.id()),
                        report.measured() ? null : truncate(report.note(), 240));
            }));
        }

        // ---------------------------------------------------------------- plan obligations
        RequirementCodeMapper mapper = RequirementCodeMapper.build(info, requirements);
        if (!mapper.hasAnyMapping() && !requirements.isEmpty()) {
            limitations.add("No source file or test mentions any requirement id, and no requirement declares "
                    + "explicit paths or tests. Static findings and test results therefore cannot be attributed "
                    + "to individual requirements; only declared scenarios can produce requirement specific evidence.");
        }

        ObligationPlanner planner = new ObligationPlanner();
        List<Requirement> planned = new ArrayList<>();
        for (Requirement requirement : requirements.requirements()) {
            List<VerificationObligation> obligations =
                    new ArrayList<>(planner.plan(requirement, config.scenarios()));
            planned.add(requirement.withObligations(obligations));
            recordMutationWeaknesses(requirement, state.mutation(), mapper, info, limitations);
        }
        RequirementSet plannedSet = new RequirementSet(planned);

        // ---------------------------------------------------------------- requirement strategies
        List<VerificationStrategy> strategies = defaultStrategies(mapper);
        Map<String, List<ObligationOutcome>> outcomesByRequirement = new LinkedHashMap<>();
        Map<String, List<Counterexample>> counterexamplesByRequirement = new LinkedHashMap<>();
        Map<String, List<String>> notesByRequirement = new LinkedHashMap<>();

        for (Requirement requirement : plannedSet.requirements()) {
            List<ObligationOutcome> outcomes = new ArrayList<>();
            List<Counterexample> counterexamples = new ArrayList<>();
            List<String> notes = new ArrayList<>();
            for (VerificationStrategy strategy : strategies) {
                if (!strategy.supports(info)) {
                    continue;
                }
                try {
                    StrategyOutcome outcome = strategy.verify(context, requirement, requirement.obligations());
                    outcomes.addAll(outcome.obligations());
                    counterexamples.addAll(outcome.counterexamples());
                    notes.addAll(outcome.notes());
                    for (String note : outcome.notes()) {
                        listener.onEvent("verify", requirement.id() + ": " + note);
                    }
                    if (config.limits().failFastOnCounterexample() && !counterexamples.isEmpty()) {
                        notes.add("Further strategies were skipped for " + requirement.id()
                                + " because a counterexample was already found and fail-fast is enabled.");
                        break;
                    }
                } catch (RuntimeException e) {
                    notes.add("Strategy " + strategy.id() + " failed while verifying " + requirement.id()
                            + ": " + e);
                    state.note("strategy " + strategy.id() + " failed: " + e);
                }
            }
            // Defensive: an obligation with no outcome at all would let a report renderer invent a
            // default, so the absence of evidence is recorded explicitly as an unverified outcome.
            java.util.Set<String> recorded = new java.util.LinkedHashSet<>();
            outcomes.forEach(outcome -> recorded.add(outcome.obligationId()));
            for (VerificationObligation obligation : requirement.obligations()) {
                if (!recorded.contains(obligation.id())) {
                    outcomes.add(ObligationOutcome.unverified(obligation.id(),
                            "No verification strategy produced an outcome for this obligation."));
                }
            }
            outcomesByRequirement.put(requirement.id(), mergeOutcomes(outcomes));
            counterexamplesByRequirement.put(requirement.id(), counterexamples);
            notesByRequirement.put(requirement.id(), notes);
        }

        // ---------------------------------------------------------------- verdicts
        List<RequirementVerdict> verdicts = new ArrayList<>();
        for (Requirement requirement : plannedSet.requirements()) {
            List<Evidence> requirementEvidence = ledger.forRequirement(requirement.id());
            RequirementVerdict verdict = VerdictCalculator.compute(
                    requirement,
                    requirement.obligations(),
                    outcomesByRequirement.getOrDefault(requirement.id(), List.of()),
                    requirementEvidence,
                    counterexamplesByRequirement.getOrDefault(requirement.id(), List.of()),
                    buildBlocked,
                    buildBlocked ? state.build().describe() : null,
                    notesByRequirement.getOrDefault(requirement.id(), List.of()));
            verdicts.add(verdict);
            listener.onEvent("verdict", requirement.id() + " -> " + verdict.status());
        }

        if (budget.snapshot().exhausted()) {
            limitations.add("The verification budget was exhausted: " + budget.snapshot().exhaustedReason()
                    + ". Anything not yet verified stays unverified, and the run is reported as blocked so "
                    + "that a truncated run cannot look like a completed one.");
        }
        limitations.addAll(state.infrastructureNotes());

        // An exhausted budget means the pipeline did not finish what it set out to do; the README lists
        // that under BLOCKED, and BLOCKED is what makes a truncated run exit non-zero instead of looking
        // like a clean pass. A run that had already verified everything is unaffected: there was nothing
        // left to truncate.
        boolean budgetBlocked = budget.snapshot().exhausted()
                && verdicts.stream().anyMatch(v -> v.status() != RequirementStatus.VERIFIED);
        RunStatus runStatus = buildBlocked || budgetBlocked ? RunStatus.BLOCKED : RunStatus.COMPLETED;
        Instant finishedAt = Instant.now();

        VerificationRun run = new VerificationRun(runId.value(), project, runStatus, phases,
                environment(project, info, config, workspace, sandbox, state, requirements, startedAt, finishedAt),
                config.toBudget(), budget.snapshot(), workspace.root(), null, null, startedAt, finishedAt,
                limitations);

        return VerificationReport.builder(runId.value(), info, plannedSet)
                .verdicts(verdicts)
                .evidence(ledger.all())
                .counterexamples(counterexamplesByRequirement.values().stream().flatMap(List::stream).toList())
                .build(buildSummary(state.build()))
                .tests(state.existingTests())
                .coverage(state.coverage())
                .mutation(state.mutation())
                .findings(state.findings())
                .dependencies(state.dependencies())
                .run(run)
                .limitations(limitations)
                .build();
    }

    // ------------------------------------------------------------------ helpers

    private record PhaseOutcome(boolean success, String summary, List<String> evidenceIds, String note) {
    }

    /**
     * @return {@code true} when the run still has time for another phase.
     *
     * <p>{@code max_duration} is a deadline for the whole run, so a phase that would begin after it has
     * passed must not begin at all. Before this check the budget was consulted only once, after every
     * phase had already run, which made it a description of the past rather than a limit: a build, a
     * test run, a coverage run and a mutation run could each consume their own timeout and the run
     * would still claim to have finished.
     */
    private static boolean withinBudget(BudgetUsage.Tracker budget) {
        if (budget.timeExpired()) {
            budget.markExhausted("max_duration reached before a further verification phase could start");
            return false;
        }
        return true;
    }

    private PhaseRecord runPhase(String name, ProgressListener listener, java.util.function.Supplier<PhaseOutcome> body) {
        Instant start = Instant.now();
        listener.onEvent(name, "started");
        try {
            PhaseOutcome outcome = body.get();
            PhaseRecord record = new PhaseRecord(name, true, outcome.success(), Duration.between(start, Instant.now()),
                    outcome.summary(), outcome.evidenceIds(), outcome.note());
            listener.onEvent(name, outcome.summary());
            return record;
        } catch (RuntimeException e) {
            listener.onEvent(name, "failed: " + e);
            return new PhaseRecord(name, true, false, Duration.between(start, Instant.now()),
                    "phase failed: " + e.getMessage(), List.of(), e.toString());
        }
    }

    private List<VerificationStrategy> defaultStrategies(RequirementCodeMapper mapper) {
        return List.of(
                new ExistingTestsStrategy(mapper, providers),
                new StaticAnalysisStrategy(mapper),
                new DependencyAdvisoryStrategy(mapper),
                new GeneratedTestsStrategy(providers),
                new MutationStrategy(mapper),
                new LlmAdvisoryStrategy());
    }

    private Optional<dev.codewitness.core.spi.VerificationTools.Compiler> firstCompiler(VerificationContext context) {
        var compilers = providers.compilers(context.projectInfo());
        return compilers.isEmpty() ? Optional.empty() : Optional.of(compilers.getFirst());
    }

    private Optional<dev.codewitness.core.spi.VerificationTools.TestRunner> firstTestRunner(VerificationContext context) {
        var runners = providers.testRunners(context.projectInfo());
        return runners.isEmpty() ? Optional.empty() : Optional.of(runners.getFirst());
    }

    private LlmProvider llm(VerificationConfig config) {
        if (!config.llm().enabled()) {
            return LlmProvider.disabled("LLM assistance is disabled in the configuration");
        }
        return dev.codewitness.core.llm.LlmProviderFactory.create(config.llm());
    }

    private static ProjectInfo emptyInfo(ProjectRef project) {
        return ProjectInfo.builder(project).build();
    }

    private VerificationContext context(
            RunId runId,
            ProjectRef project,
            ProjectInfo info,
            VerificationConfig config,
            WorkspaceManager.Workspace workspace,
            ArtifactStore artifacts,
            BudgetUsage.Tracker budget,
            RequirementSet requirements,
            LlmProvider llm,
            ProgressListener listener,
            ExecutionSandbox sandbox,
            RunState state,
            EvidenceLedger ledger,
            Instant startedAt) {
        return VerificationContext.builder()
                .runId(runId.value())
                .projectRef(project)
                .projectInfo(info)
                .config(config)
                .workspace(workspace)
                .artifacts(artifacts)
                .budget(budget)
                .requirements(requirements)
                .llm(llm)
                .progress(listener)
                .sandbox(sandbox)
                .state(state)
                .evidenceLedger(ledger)
                .startedAt(startedAt)
                .build();
    }

    /**
     * Records surviving mutants as a limitation rather than as an unmet obligation.
     *
     * <p>A surviving mutant says the test suite cannot tell the mutated behaviour from the original. It
     * does not say the requirement is violated, and making it a blocking obligation would let test
     * strength silently overrule executed evidence. It is surfaced loudly in the report and in the
     * limitations list instead.
     */
    private static void recordMutationWeaknesses(
            Requirement requirement, MutationReport mutation, RequirementCodeMapper mapper,
            ProjectInfo project, List<String> limitations) {
        if (!mutation.measured() || mutation.survivors().isEmpty()) {
            return;
        }
        List<SurvivingMutant> relevant = new ArrayList<>();
        for (SurvivingMutant mutant : mutation.survivors()) {
            if (mutantPaths(mutant, project).stream()
                    .anyMatch(path -> mapper.requirementsForFile(path).contains(requirement.id()))) {
                relevant.add(mutant);
            }
        }
        if (!relevant.isEmpty()) {
            limitations.add("Test strength: " + relevant.size() + " mutant(s) in the code covered by "
                    + requirement.id() + " survived the test suite (for example "
                    + relevant.getFirst().location() + " -> " + relevant.getFirst().description()
                    + "). Nobody checked those behaviours: surviving mutants are not a violation of the "
                    + "requirement, but they are a reason not to trust unexecuted assumptions about it.");
        }
    }

    /**
     * Merges the outcomes several strategies produced for the same obligation.
     *
     * <p>Strategies run in sequence and each reports only what it observed, so an obligation can be
     * mentioned more than once: a static analyzer may refute it while the generated-test strategy has
     * nothing to run. Keeping the last report would let "not attempted" mask a real finding, so the
     * strongest observation wins. A violation is never overwritten, and neither is a discharge.
     */
    private static List<ObligationOutcome> mergeOutcomes(List<ObligationOutcome> outcomes) {
        java.util.LinkedHashMap<String, ObligationOutcome> strongest = new java.util.LinkedHashMap<>();
        for (ObligationOutcome outcome : outcomes) {
            ObligationOutcome existing = strongest.get(outcome.obligationId());
            if (existing == null || strength(outcome.status()) > strength(existing.status())) {
                strongest.put(outcome.obligationId(), outcome);
            }
        }
        return List.copyOf(strongest.values());
    }

    /** @return how much an outcome tells us about its obligation; higher wins a merge. */
    private static int strength(RequirementStatus status) {
        return switch (status) {
            case FAILED -> 4;
            case VERIFIED -> 3;
            case PARTIALLY_VERIFIED -> 2;
            case BLOCKED -> 1;
            case UNVERIFIED -> 0;
        };
    }

    private static List<Finding> deduplicateFindings(List<Finding> findings) {
        java.util.LinkedHashMap<String, Finding> unique = new java.util.LinkedHashMap<>();
        for (Finding finding : findings) {
            unique.putIfAbsent(finding.fingerprint(), finding);
        }
        return List.copyOf(unique.values());
    }

    @SuppressWarnings("unused")
    private List<VerificationObligation> mutationObligations(
            Requirement requirement, MutationReport mutation, RequirementCodeMapper mapper,
            ProjectInfo project, ObligationPlanner planner) {
        if (!mutation.measured() || mutation.survivors().isEmpty()) {
            return List.of();
        }
        List<SurvivingMutant> relevant = new ArrayList<>();
        for (SurvivingMutant mutant : mutation.survivors()) {
            if (mutantPaths(mutant, project).stream()
                    .anyMatch(path -> mapper.requirementsForFile(path).contains(requirement.id()))) {
                relevant.add(mutant);
            }
        }
        if (relevant.isEmpty()) {
            return List.of();
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("survivors", relevant.size());
        parameters.put("examples", relevant.stream().limit(5).map(SurvivingMutant::location).toList());
        return List.of(new VerificationObligation(
                planner.nextId(),
                requirement.id(),
                relevant.size() + " mutant(s) in the code this requirement covers survived the test suite, "
                        + "so the tests do not distinguish the mutated behaviour from the original",
                ObligationKind.INVARIANT,
                ObligationOrigin.ANALYZER_DERIVED,
                null,
                parameters,
                false));
    }

    /**
     * @return the candidate paths a mutant's location may correspond to, for requirement mapping.
     *
     * <p>A Java mutation tool reports a class name; a Python one reports a file path. Both are turned
     * into the same shape the requirement mapper uses, driven by the adapter's declared source
     * extensions, so the engine does not need to know which language produced the mutant.
     */
    private static List<String> mutantPaths(SurvivingMutant mutant, ProjectInfo project) {
        if (mutant.className() == null) {
            return List.of();
        }
        String name = mutant.className();
        List<String> paths = new ArrayList<>();
        paths.add(name);
        if (project.isSourceFileName(name)) {
            return List.copyOf(paths);
        }
        String slashed = name.replace('.', '/');
        paths.add(slashed);
        for (String extension : project.sourceExtensions()) {
            paths.add(slashed + "." + extension);
        }
        return List.copyOf(paths);
    }

    private static List<Finding> diagnosticsToFindings(BuildResult build) {
        List<Finding> findings = new ArrayList<>();
        for (var diagnostic : build.diagnostics()) {
            Severity severity = switch (diagnostic.severity()) {
                case ERROR -> Severity.CRITICAL;
                case WARNING -> Severity.LOW;
                default -> Severity.INFO;
            };
            FindingCategory category = diagnostic.severity() == DiagnosticSeverity.ERROR
                    ? FindingCategory.BUG : FindingCategory.MAINTAINABILITY;
            findings.add(new Finding("compiler", diagnostic.code(), severity, category,
                    diagnostic.message(), diagnostic.file(), diagnostic.line(), null, Map.of()));
        }
        return findings;
    }

    private static String renderFailures(TestResult tests) {
        StringBuilder sb = new StringBuilder();
        for (var failure : tests.failures().stream().limit(20).toList()) {
            sb.append(failure.displayName()).append(": ").append(failure.failureType()).append(" - ")
                    .append(failure.failureMessage()).append('\n');
        }
        return sb.toString();
    }

    private static String renderFindings(List<Finding> findings) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < findings.size(); i++) {
            Finding finding = findings.get(i);
            sb.append("  {\"analyzer\":\"").append(escape(finding.analyzer()))
                    .append("\",\"rule\":\"").append(escape(finding.ruleId()))
                    .append("\",\"severity\":\"").append(finding.severity())
                    .append("\",\"category\":\"").append(finding.category())
                    .append("\",\"file\":\"").append(escape(finding.file()))
                    .append("\",\"line\":").append(finding.line() == null ? "null" : finding.line())
                    .append(",\"message\":\"").append(escape(finding.message())).append("\"}");
            sb.append(i == findings.size() - 1 ? "\n" : ",\n");
        }
        return sb.append("]\n").toString();
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }

    private static String summariseFindings(List<Finding> findings) {
        Map<Severity, Long> bySeverity = new java.util.EnumMap<>(Severity.class);
        for (Finding finding : findings) {
            bySeverity.merge(finding.severity(), 1L, Long::sum);
        }
        if (bySeverity.isEmpty()) {
            return "no findings";
        }
        return bySeverity.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getValue() + " " + e.getKey().name().toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static Map<String, Object> summariseSeverity(List<Finding> findings) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Severity severity : Severity.values()) {
            long count = findings.stream().filter(f -> f.severity() == severity).count();
            map.put(severity.name().toLowerCase(java.util.Locale.ROOT), count);
        }
        return map;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private static String tail(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(text.length() - max);
    }

    private static VerificationReport.BuildSummary buildSummary(BuildResult build) {
        return new VerificationReport.BuildSummary(build.attempted(), build.success(), build.command(),
                build.duration().toMillis(), build.errors().size(), build.warnings().size(),
                build.failureReason() == null ? build.describe() : build.failureReason());
    }

    private EnvironmentSnapshot environment(
            ProjectRef project,
            ProjectInfo info,
            VerificationConfig config,
            WorkspaceManager.Workspace workspace,
            ExecutionSandbox sandbox,
            RunState state,
            RequirementSet requirements,
            Instant startedAt,
            Instant finishedAt) {
        return new EnvironmentSnapshot(
                VERSION,
                System.getProperty("java.version"),
                System.getProperty("java.vendor"),
                System.getProperty("os.name") + " " + System.getProperty("os.version") + " ("
                        + System.getProperty("os.arch") + ")",
                info.buildSystem().id(),
                info.attributes().getOrDefault("buildToolVersion", "unknown"),
                gitRevision(workspace.root()),
                requirements.contentHash(),
                config.fingerprint(),
                config.llm().enabled() ? String.valueOf(config.llm().model()) : "none",
                sandbox.isolationLevel().name(),
                sandbox.description(),
                project.root().toString(),
                startedAt,
                finishedAt);
    }

    /** Reads the git revision of the checkout, if it is one. Never fails a run. */
    private String gitRevision(Path root) {
        ProcessResult result = hostRunner.run(ProcessSpec.builder(java.util.List.of("git", "rev-parse", "HEAD"), root)
                .timeout(Duration.ofSeconds(10))
                .build());
        if (result.success() && !result.stdout().isBlank()) {
            return result.stdout().trim();
        }
        return "unknown (not a git checkout or git unavailable)";
    }

    private VerificationReport blockedReport(
            RunId runId,
            ProjectRef project,
            RequirementSet requirements,
            VerificationConfig config,
            WorkspaceManager.Workspace workspace,
            RunState state,
            EvidenceLedger ledger,
            List<PhaseRecord> phases,
            BudgetUsage.Tracker budget,
            List<String> limitations,
            Instant startedAt,
            Evidence evidence,
            String reason) {

        List<RequirementVerdict> verdicts = new ArrayList<>();
        for (Requirement requirement : requirements.requirements()) {
            verdicts.add(new RequirementVerdict(requirement.id(), RequirementStatus.BLOCKED, reason,
                    List.of(evidence.id()), List.of(), List.of(), List.of(), false));
        }
        Instant finishedAt = Instant.now();
        VerificationRun run = new VerificationRun(runId.value(), project, RunStatus.BLOCKED, phases,
                new EnvironmentSnapshot(VERSION, System.getProperty("java.version"), System.getProperty("java.vendor"),
                        System.getProperty("os.name"), "unknown", "unknown", "unknown",
                        requirements.contentHash(), config.fingerprint(), "none", "none", "no sandbox created",
                        project.root().toString(), startedAt, finishedAt),
                config.toBudget(), budget.snapshot(), workspace.root(), null, null, startedAt, finishedAt, limitations);
        return VerificationReport.builder(runId.value(), emptyInfo(project), requirements)
                .verdicts(verdicts)
                .evidence(ledger.all())
                .run(run)
                .limitations(limitations)
                .build();
    }

    private VerificationReport failedReport(
            RunId runId,
            ProjectRef project,
            RequirementSet requirements,
            VerificationConfig config,
            WorkspaceManager.Workspace workspace,
            RunState state,
            EvidenceLedger ledger,
            List<PhaseRecord> phases,
            BudgetUsage.Tracker budget,
            List<String> limitations,
            Instant startedAt,
            String reason) {

        List<RequirementVerdict> verdicts = new ArrayList<>();
        for (Requirement requirement : requirements.requirements()) {
            verdicts.add(new RequirementVerdict(requirement.id(), RequirementStatus.BLOCKED, reason,
                    List.of(), List.of(), List.of(), List.of(), false));
        }
        Instant finishedAt = Instant.now();
        VerificationRun run = new VerificationRun(runId.value(), project, RunStatus.FAILED, phases,
                new EnvironmentSnapshot(VERSION, System.getProperty("java.version"), System.getProperty("java.vendor"),
                        System.getProperty("os.name"), "unknown", "unknown", "unknown",
                        requirements.contentHash(), config.fingerprint(), "none", "none", "n/a",
                        project.root().toString(), startedAt, finishedAt),
                config.toBudget(), budget.snapshot(), workspace.root(), null, null, startedAt, finishedAt, limitations);
        return VerificationReport.builder(runId.value(), emptyInfo(project), requirements)
                .verdicts(verdicts)
                .evidence(ledger.all())
                .run(run)
                .limitations(limitations)
                .build();
    }
}
