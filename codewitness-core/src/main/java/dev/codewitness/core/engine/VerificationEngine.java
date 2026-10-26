package dev.codewitness.core.engine;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.codewitness.core.config.Scenario;
import dev.codewitness.core.config.VerificationConfig;
import dev.codewitness.core.diagnostics.SelfIssueCategory;
import dev.codewitness.core.diagnostics.SelfIssueRecord;
import dev.codewitness.core.diagnostics.SelfIssueRecorder;
import dev.codewitness.core.diagnostics.SelfIssueSeverity;
import dev.codewitness.core.engine.strategy.DefaultStrategies;
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
import dev.codewitness.core.model.AdvisoryScan;
import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.Capability;
import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.DependencyVulnerability;
import dev.codewitness.core.model.DiagnosticSeverity;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.PackageCoordinate;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.RunId;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.model.SurvivingMutant;
import dev.codewitness.core.model.TestCaseResult;
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
import dev.codewitness.core.spi.DatabaseFixture;
import dev.codewitness.core.spi.LanguageAdapter;
import dev.codewitness.core.spi.LanguageAdapterRegistry;
import dev.codewitness.core.spi.LanguageDetectorRegistry;
import dev.codewitness.core.spi.PreparedDatabase;
import dev.codewitness.core.spi.ProgressListener;
import dev.codewitness.core.spi.RequirementCodeMap;
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
import java.util.Set;

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
    public static final String VERSION = "0.1.12";

    /** Serialises the findings artifact; a hand-built JSON string cannot be trusted with tool output. */
    private static final com.fasterxml.jackson.databind.ObjectMapper FINDINGS_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final LanguageAdapterRegistry languages;
    private final VerificationProviderRegistry providers;
    private final LanguageDetectorRegistry detectors;
    private final ProcessRunner hostRunner;
    private final SelfIssueRecorder selfIssues;

    public VerificationEngine(LanguageAdapterRegistry languages, VerificationProviderRegistry providers) {
        this(languages, providers, LanguageDetectorRegistry.of(), new dev.codewitness.core.exec
                .LocalProcessSandbox(List.of(Path.of("."))).runner());
    }

    public VerificationEngine(
            LanguageAdapterRegistry languages, VerificationProviderRegistry providers, ProcessRunner hostRunner) {
        this(languages, providers, LanguageDetectorRegistry.of(), hostRunner);
    }

    /**
     * @param detectors recognition for languages no adapter can verify. Without them a run still verifies
     *     every language it has an adapter for; it just cannot say anything about the ones it does not.
     */
    public VerificationEngine(
            LanguageAdapterRegistry languages,
            VerificationProviderRegistry providers,
            LanguageDetectorRegistry detectors,
            ProcessRunner hostRunner) {
        this(languages, providers, detectors, hostRunner, SelfIssueRecorder.NOOP);
    }

    /**
     * @param selfIssues sink for faults the engine observes in itself. A phase or strategy that throws is
     *     recorded here as well as turned into an honest report outcome, so the defect can be fixed later
     *     instead of being rediscovered by every user.
     */
    public VerificationEngine(
            LanguageAdapterRegistry languages,
            VerificationProviderRegistry providers,
            LanguageDetectorRegistry detectors,
            ProcessRunner hostRunner,
            SelfIssueRecorder selfIssues) {
        this.languages = languages;
        this.providers = providers;
        this.detectors = detectors;
        this.hostRunner = hostRunner;
        this.selfIssues = selfIssues == null ? SelfIssueRecorder.NOOP : selfIssues;
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
        // A link that points outside the project is not copied into the workspace, so a project that needs
        // one is verified against a tree that does not have it. That has to be stated, because it changes
        // what the verdicts are about; and it is the only place the user is told why the copy differs.
        if (!workspace.notCopied().isEmpty()) {
            limitations.add(workspace.notCopied().size() + " symbolic link(s) in the project were not copied "
                    + "into the isolated workspace, so the tree the run verified does not contain them: "
                    + workspace.notCopied().stream().limit(5).map(Path::toString)
                            .collect(java.util.stream.Collectors.joining(", "))
                    + (workspace.notCopied().size() > 5 ? ", and " + (workspace.notCopied().size() - 5) + " more" : "")
                    + ". A link is not followed because following one would copy data from outside the project "
                    + "into a run directory that is kept on disk.");
        }

        List<LanguageAdapter> adapters = languages.selectAll(project, config.language().adapter());
        if (adapters.isEmpty()) {
            limitations.add("No language adapter supports this project. Registered adapters: " + languages.ids());
            // With no adapter at all the run says nothing about any language, so naming the ones it can see
            // is the most useful thing it can still do.
            List<LanguageDetectorRegistry.Detection> detected = detectors.detect(workspace.root(), Set.of());
            limitations.addAll(UncoveredLanguages.limitations(detected, List.of()));
            // A language nobody can verify is a limitation of the tool, not a finding about the code. It is
            // recorded as a capability gap so that the next request to make CodeWitness more useful has
            // the concrete languages to start from.
            String detectedNames = detected.stream()
                    .map(LanguageDetectorRegistry.Detection::displayName)
                    .distinct()
                    .collect(java.util.stream.Collectors.joining(", "));
            selfIssues.record(SelfIssueCategory.CAPABILITY_GAP, SelfIssueSeverity.LOW,
                    "No language adapter supports this project (detected: "
                            + (detectedNames.isEmpty() ? "unknown" : detectedNames)
                            + "; registered adapters: " + languages.ids() + ")",
                    null,
                    Map.of(SelfIssueRecord.RUN_ID, runId.value(),
                            SelfIssueRecord.PROJECT, project.root().toString(),
                            SelfIssueRecord.PHASE, "inspect",
                            SelfIssueRecord.VERSION, VERSION));
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

        // The database is prepared before the sandbox so that its connection details are part of the
        // environment every process of the run starts with, and it is prepared before any phase so that a
        // test runner or a generated test can use it. A fixture that cannot prepare one reports why, and
        // the run says the database was unavailable instead of proceeding as though it had been used.
        PreparedDatabase database = prepareDatabase(config, workspace.root(), runDirectory, artifacts, listener,
                runId, project);
        ExecutionSandbox sandbox = SandboxFactory.create(config, workspace.root(), runDirectory, hostRunner,
                database.environment());
        limitations.add("Execution isolation: " + sandbox.isolationLevel() + " via " + sandbox.id() + ". "
                + sandbox.description());
        limitations.addAll(recordDatabase(database, config, ledger, listener));

        ProgressListener scoped = (phase, message) -> listener.onEvent(phase, message);

        // ---------------------------------------------------------------- inspect
        Instant inspectStart = Instant.now();
        List<Inspection> inspections;
        try {
            inspections = inspectAll(adapters, runId, project, config, workspace, artifacts, budget, requirements,
                    scoped, sandbox, state, ledger, startedAt, database);
        } catch (RuntimeException e) {
            phases.add(new PhaseRecord("inspect", true, false, Duration.between(inspectStart, Instant.now()),
                    "inspection failed: " + e, List.of(), e.toString()));
            recordIssue(SelfIssueCategory.INTERNAL_ERROR, SelfIssueSeverity.CRITICAL,
                    "Project inspection failed: " + e, e, runId, project,
                    Map.of(SelfIssueRecord.PHASE, "inspect"));
            return failedReport(runId, project, requirements, config, workspace, state, ledger, phases, budget,
                    limitations, startedAt, "Project inspection failed: " + e);
        }
        if (inspections.size() > 1) {
            limitations.add("This project is polyglot: " + inspections.size() + " language adapters claimed "
                    + "part of it (" + inspections.stream().map(i -> i.adapter().id()).toList()
                    + "). Each language's own phases were run, but the report's build, test, coverage and "
                    + "mutation summaries are aggregated across them, so read them together with the "
                    + "per-language evidence rather than as one project's numbers.");
        }
        // Running every adapter that claims the project still leaves any language nothing can verify. Saying
        // so is the difference between an incomplete report and a misleading one.
        List<LanguageDetectorRegistry.Detection> uncovered = detectors.detect(workspace.root(),
                inspections.stream().map(inspection -> inspection.info().language())
                        .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));
        limitations.addAll(UncoveredLanguages.limitations(uncovered,
                inspections.stream().map(inspection -> inspection.info().language().id()).toList()));
        // One description of the whole tree, for mapping requirements to files and for the report. Without
        // it the languages other than the first would be invisible to both.
        ProjectInfo info = ProjectInfo.union(project,
                inspections.stream().map(Inspection::info).toList());
        List<String> inspectionEvidence = new ArrayList<>();
        for (Inspection inspection : inspections) {
            ProjectInfo languageInfo = inspection.info();
            Evidence evidence = ledger.add(ledger.newEvidence(EvidenceType.PROJECT_INSPECTION)
                    .description("Detected " + languageInfo.describe())
                    .confidence(EvidenceConfidence.DETERMINISTIC)
                    .status(EvidenceStatus.SUPPORTS)
                    .result(languageInfo.describe())
                    .metadata("adapter", inspection.adapter().id())
                    .metadata("language", languageInfo.language().id())
                    .metadata("buildSystem", languageInfo.buildSystem().id())
                    .metadata("modules", languageInfo.modules().stream().map(m -> m.name()).toList())
                    .metadata("testFrameworks", List.copyOf(languageInfo.testFrameworks()))
                    .metadata("javaVersion", languageInfo.javaVersion().orElse("undeclared"))
                    .build());
            inspectionEvidence.add(evidence.id());
            listener.onEvent("inspect", languageInfo.describe());
        }
        String inspected = inspections.stream().map(i -> i.info().describe())
                .collect(java.util.stream.Collectors.joining("; "));
        phases.add(new PhaseRecord("inspect", true, true, Duration.between(inspectStart, Instant.now()),
                inspected, List.copyOf(inspectionEvidence), null));

        LlmProvider llm = llm(config);

        // ---------------------------------------------------------------- build
        // Every language that claims part of the project gets its own build. One language failing to
        // compile must not stop the other one from being verified: the requirements that live in the
        // language that built are still verifiable, and reporting them BLOCKED because a sibling language
        // did not compile would be as wrong as reporting the other side silently verified.
        Map<String, BuildResult> builds = new LinkedHashMap<>();
        // Per language, in addition to the merged result the strategies read. Mutation testing needs a
        // suite to mutate against, and a language whose suite did not run is not one to spend mutant budget
        // on even though a sibling language's suite did run.
        Map<String, TestResult> testsByAdapter = new LinkedHashMap<>();
        List<Inspection> builders = capable(inspections, Capability.BUILD);
        if (builders.isEmpty()) {
            phases.add(new PhaseRecord("build", false, true, Duration.ZERO,
                    "no selected language adapter declares a build capability", List.of(), "skipped"));
        } else if (!withinBudget(budget)) {
            phases.add(new PhaseRecord("build", false, true, Duration.ZERO,
                    "the run budget was already exhausted, so no build was attempted", List.of(), "skipped"));
        } else {
            phases.add(runPhase("build", runId, project, listener, () -> {
                List<String> evidenceIds = new ArrayList<>();
                List<BuildResult> results = new ArrayList<>();
                for (Inspection inspection : builders) {
                    VerificationContext languageContext = contextFor(inspection, runId, project, config, workspace,
                            artifacts, budget, requirements, llm, scoped, sandbox, state, ledger, startedAt,
                            RequirementCodeMap.empty(), database);
                    BuildResult build = firstCompiler(languageContext)
                            .map(compiler -> compiler.build(languageContext))
                            .orElseGet(() -> inspection.adapter().build(languageContext));
                    builds.put(inspection.adapter().id(), build);
                    results.add(build);
                    state.addFindings(diagnosticsToFindings(build));
                    Evidence evidence = ledger.add(ledger.newEvidence(EvidenceType.COMPILER)
                            .description("Build (" + inspection.adapter().id() + "): " + build.describe())
                            .command(build.command())
                            .confidence(EvidenceConfidence.DETERMINISTIC)
                            .status(build.success() ? EvidenceStatus.SUPPORTS : EvidenceStatus.INCONCLUSIVE)
                            .result(build.describe())
                            .output(tail(build.output(), 8000))
                            .metadata("adapter", inspection.adapter().id())
                            .metadata("exitCode", build.exitCode())
                            .metadata("errors", build.errors().size())
                            .metadata("warnings", build.warnings().size())
                            .metadata("artifacts", build.artifacts().stream().map(Path::toString).toList())
                            .build());
                    evidenceIds.add(evidence.id());
                    if (!build.attempted()) {
                        state.note("build not attempted for " + inspection.adapter().id() + ": "
                                + build.failureReason());
                    }
                }
                BuildResult merged = mergeBuilds(results);
                state.build(merged);
                return new PhaseOutcome(merged.success(), merged.describe(), List.copyOf(evidenceIds),
                        merged.success() ? null : merged.failureReason());
            }));
        }

        boolean buildBlocked = !state.build().success();
        if (buildBlocked) {
            List<String> failed = builds.entrySet().stream().filter(entry -> !entry.getValue().success())
                    .map(Map.Entry::getKey).toList();
            limitations.add((inspections.size() > 1
                    ? "Not every language in this project could be built: "
                    : "The project could not be built, so nothing could be verified: ")
                    + state.build().describe() + ". A failed or impossible build is reported as BLOCKED, "
                    + "not as a code defect."
                    + (failed.isEmpty() ? "" : " The language(s) whose build failed: " + failed + ".")
                    + (inspections.size() > 1
                            ? " Requirements written in a language that did build were still verified." : ""));
        }

        // ---------------------------------------------------------------- existing tests
        if (config.tests().enabled() && withinBudget(budget)) {
            List<Inspection> testable = capable(inspections, Capability.TEST).stream()
                    .filter(inspection -> builtSuccessfully(builds.get(inspection.adapter().id())))
                    .toList();
            phases.add(runPhase("tests", runId, project, listener, () -> {
                List<String> evidenceIds = new ArrayList<>();
                List<TestResult> results = new ArrayList<>();
                for (Inspection inspection : testable) {
                    // Recorded per language as well as merged: mutation testing needs a suite to mutate
                    // against, and a language whose suite did not run is not one to spend mutant budget on.

                    VerificationContext languageContext = contextFor(inspection, runId, project, config, workspace,
                            artifacts, budget, requirements, llm, scoped, sandbox, state, ledger, startedAt,
                            RequirementCodeMap.empty(), database);
                    Optional<VerificationTools.TestRunner> testRunner = firstTestRunner(languageContext);
                    TestResult tests = testRunner
                            .map(runner -> runner.runTests(languageContext))
                            .orElseGet(() -> inspection.adapter().runTests(languageContext));
                    // Evidence must name the command that produced it. The build command is not that
                    // command: it compiles without running tests, so recording it would describe an
                    // invocation that cannot have yielded the result below.
                    List<String> testCommand = testRunner.map(r -> r.testCommand(languageContext)).orElseGet(List::of);
                    results.add(tests);
                    testsByAdapter.put(inspection.adapter().id(), tests);
                    // describeWithNote, not describe: the note is how the runner says that what it parsed is
                    // not the whole story, and this record is what the report shows.
                    Evidence evidence = ledger.add(ledger.newEvidence(EvidenceType.TEST)
                            .description("Existing tests (" + inspection.adapter().id() + "): "
                                    + tests.describeWithNote())
                            .command(testCommand)
                            .confidence(EvidenceConfidence.DETERMINISTIC)
                            .status(!tests.executed() ? EvidenceStatus.INCONCLUSIVE
                                    : (tests.anyFailure() ? EvidenceStatus.REFUTES : EvidenceStatus.SUPPORTS))
                            .result(tests.describeWithNote())
                            .output(tail(renderFailures(tests), 8000))
                            .metadata("adapter", inspection.adapter().id())
                            .metadata("reports", tests.reports().stream().map(Path::toString).toList())
                            .build());
                    evidenceIds.add(evidence.id());
                    if (!tests.executed()) {
                        state.note("existing tests not executed for " + inspection.adapter().id() + ": "
                                + tests.note());
                    }
                }
                TestResult merged = mergeTests(results);
                state.existingTests(merged);
                return new PhaseOutcome(merged.executed(), merged.describe(), List.copyOf(evidenceIds),
                        merged.executed() ? null : merged.note());
            }));
        }

        // ---------------------------------------------------------------- static analysis
        if (config.staticAnalysis().enabled() && withinBudget(budget)) {
            List<Inspection> analysable = analysable(inspections, builds);
            phases.add(runPhase("static-analysis", runId, project, listener, () -> {
                List<String> evidenceIds = new ArrayList<>();
                List<Finding> findings = new ArrayList<>();
                for (Inspection inspection : analysable) {
                    VerificationContext languageContext = contextFor(inspection, runId, project, config, workspace,
                            artifacts, budget, requirements, llm, scoped, sandbox, state, ledger, startedAt,
                            RequirementCodeMap.empty(), database);
                    // Registered providers are the extension point, and every bundled adapter registers
                    // one. Calling adapter.analyze() as well ran the whole pass twice - including a second
                    // -Xlint:all compile - for findings that were then deduplicated away. The adapter level
                    // entry point is kept as a fallback for an adapter that registers no provider.
                    List<Finding> found = new ArrayList<>();
                    var analyzers = providers.staticAnalyzers(inspection.info());
                    if (analyzers.isEmpty()) {
                        found.addAll(inspection.adapter().analyze(languageContext));
                    } else {
                        for (var analyzer : analyzers) {
                            found.addAll(analyzer.analyze(languageContext));
                        }
                    }
                    // The adapter and the registered providers may report the same rule through different
                    // paths; a finding is reported once, keyed by analyzer, rule, location and message.
                    found = deduplicateFindings(found);
                    findings.addAll(found);
                    state.addFindings(found);
                    Path report = artifacts.write("static-analysis/" + inspection.adapter().id() + "-findings.json",
                            renderFindings(found));
                    Evidence evidence = ledger.add(ledger.newEvidence(EvidenceType.STATIC_ANALYSIS)
                            .description("Static analysis (" + inspection.adapter().id() + "): "
                                    + found.size() + " finding(s)")
                            .source("static-analysis")
                            .confidence(EvidenceConfidence.DETERMINISTIC)
                            .status(found.isEmpty() ? EvidenceStatus.SUPPORTS : EvidenceStatus.INCONCLUSIVE)
                            .result(summariseFindings(found))
                            .metadata("adapter", inspection.adapter().id())
                            .metadata("bySeverity", summariseSeverity(found))
                            .artifact(report)
                            .build());
                    evidenceIds.add(evidence.id());
                }
                // Record that the phase ran, not just what it found: a clean run and a run where nothing
                // could execute must not look the same to the strategies.
                state.staticAnalysisRan(!analysable.isEmpty());
                return new PhaseOutcome(!analysable.isEmpty(), summariseFindings(findings),
                        List.copyOf(evidenceIds), null);
            }));
        }

        if (config.security().enabled() && withinBudget(budget)) {
            List<Inspection> analysable = analysable(inspections, builds);
            phases.add(runPhase("security", runId, project, listener, () -> {
                List<String> evidenceIds = new ArrayList<>();
                List<Finding> findings = new ArrayList<>();
                for (Inspection inspection : analysable) {
                    VerificationContext languageContext = contextFor(inspection, runId, project, config, workspace,
                            artifacts, budget, requirements, llm, scoped, sandbox, state, ledger, startedAt,
                            RequirementCodeMap.empty(), database);
                    var analyzers = providers.securityAnalyzers(inspection.info());
                    List<Finding> found = new ArrayList<>();
                    for (var analyzer : analyzers) {
                        found.addAll(analyzer.analyze(languageContext));
                    }
                    found = deduplicateFindings(found);
                    findings.addAll(found);
                    state.addFindings(found);
                    Evidence evidence = ledger.add(ledger.newEvidence(EvidenceType.SECURITY_SCAN)
                            .description("Security analysis (" + inspection.adapter().id() + "): "
                                    + found.size() + " finding(s)")
                            .confidence(EvidenceConfidence.DETERMINISTIC)
                            .status(found.isEmpty() ? EvidenceStatus.SUPPORTS : EvidenceStatus.INCONCLUSIVE)
                            .result(summariseFindings(found))
                            .metadata("adapter", inspection.adapter().id())
                            .metadata("tools", analyzers.stream().map(a -> a.id()).toList())
                            .build());
                    evidenceIds.add(evidence.id());
                }
                return new PhaseOutcome(!analysable.isEmpty(), summariseFindings(findings),
                        List.copyOf(evidenceIds), null);
            }));
        }

        if (withinBudget(budget)) {
            phases.add(runPhase("dependencies", runId, project, listener, () -> {
                List<String> evidenceIds = new ArrayList<>();
                List<LanguageInventory> inventories = new ArrayList<>();
                for (Inspection inspection : analysable(inspections, builds)) {
                    VerificationContext languageContext = contextFor(inspection, runId, project, config, workspace,
                            artifacts, budget, requirements, llm, scoped, sandbox, state, ledger, startedAt,
                            RequirementCodeMap.empty(), database);
                    List<VerificationTools.DependencyAnalyzer> analyzers =
                            providers.dependencyAnalyzers(inspection.info());
                    DependencyReport report = analyzers.isEmpty()
                            ? DependencyReport.notAnalysed("none", "no dependency analyzer registered")
                            : analyzers.getFirst().analyseDependencies(languageContext);
                    List<String> dependencyCommand = analyzers.isEmpty()
                            ? List.of() : analyzers.getFirst().dependencyCommand(languageContext);
                    List<Path> advisoryArtifacts = new ArrayList<>();
                    if (report.analysed() && config.security().vulnerabilities().enabled()) {
                        OsvAdvisoryScanner.Scan scan = OsvAdvisoryScanner.scan(
                                config.security().vulnerabilities(), report.coordinates(), artifacts);
                        report = report.withAdvisoryScan(scan.coverage(), scan.vulnerabilities());
                        advisoryArtifacts.addAll(scan.artifacts());
                        listener.onEvent("dependencies", inspection.adapter().id()
                                + " advisory scan: " + scan.coverage().note());
                    }
                    inventories.add(new LanguageInventory(inspection, report));
                    Evidence evidence = ledger.add(ledger.newEvidence(EvidenceType.DEPENDENCY_SCAN)
                            .description("Dependency analysis (" + inspection.adapter().id() + "): "
                                    + report.totalDependencies() + " dependencies; "
                                    + (report.advisoryScan().performed()
                                            ? report.vulnerabilities().size() + " known vulnerabilities"
                                            : "vulnerabilities not checked"))
                            .command(dependencyCommand)
                            .confidence(EvidenceConfidence.DETERMINISTIC)
                            .status(EvidenceStatus.INCONCLUSIVE)
                            .result(report.analysed() ? report.totalDependencies() + " dependencies"
                                    : "not analysed: " + report.note())
                            .output(report.advisoryScan().performed()
                                    ? "advisory source " + report.advisoryScan().source() + " at "
                                            + report.advisoryScan().endpoint() + ": "
                                            + report.advisoryScan().note()
                                    : "no advisory source was consulted: " + report.advisoryScan().note())
                            .metadata("adapter", inspection.adapter().id())
                            .metadata("vulnerabilities", report.vulnerabilities().size())
                            .metadata("vulnerabilitiesChecked", report.advisoryScan().performed())
                            .metadata("vulnerabilitySource", report.advisoryScan().source())
                            .artifacts(advisoryArtifacts)
                            .build());
                    evidenceIds.add(evidence.id());
                }
                DependencyReport merged = mergeDependencies(inventories);
                if (!merged.analysed() && inventories.isEmpty()) {
                    // An empty selection is ambiguous: no adapter declares a dependency analyzer, or no
                    // language's build succeeded. Reporting the second as the first claims a configuration
                    // fact that is false, so name the real reason when there is one.
                    String reason = noBuildSucceeded(builds);
                    if (reason != null) {
                        merged = DependencyReport.notAnalysed("none",
                                reason + ", so no dependency inventory was produced");
                    }
                }
                state.dependencies(merged);
                return new PhaseOutcome(merged.analysed(),
                        merged.analysed() ? merged.totalDependencies() + " dependencies" : merged.note(),
                        List.copyOf(evidenceIds), merged.analysed() ? null : merged.note());
            }));
        }

        // ---------------------------------------------------------------- coverage
        if (withinBudget(budget)) {
            List<Inspection> coverable = capable(inspections, Capability.COVERAGE).stream()
                    .filter(inspection -> builtSuccessfully(builds.get(inspection.adapter().id())))
                    .toList();
            phases.add(runPhase("coverage", runId, project, listener, () -> {
                List<String> evidenceIds = new ArrayList<>();
                List<CoverageReport> results = new ArrayList<>();
                for (Inspection inspection : coverable) {
                    VerificationContext languageContext = contextFor(inspection, runId, project, config, workspace,
                            artifacts, budget, requirements, llm, scoped, sandbox, state, ledger, startedAt,
                            RequirementCodeMap.empty(), database);
                    List<VerificationTools.CoverageProvider> coverageProviders =
                            providers.coverageProviders(inspection.info());
                    CoverageReport coverage = coverageProviders.isEmpty()
                            ? CoverageReport.notMeasured("none", "no coverage provider registered for "
                                    + inspection.adapter().id())
                            : coverageProviders.getFirst().measure(languageContext);
                    results.add(coverage);
                    Evidence evidence = ledger.add(ledger.newEvidence(EvidenceType.COVERAGE)
                            .description("Coverage (" + inspection.adapter().id() + "): " + coverage.describe())
                            .confidence(EvidenceConfidence.DETERMINISTIC)
                            .status(coverage.measured() ? EvidenceStatus.SUPPORTS : EvidenceStatus.INCONCLUSIVE)
                            .result(coverage.describe())
                            .metadata("adapter", inspection.adapter().id())
                            .metadata("lineCoveragePercent", coverage.lineCoveragePercent())
                            .metadata("branchCoveragePercent", coverage.branchCoveragePercent())
                            .artifacts(coverage.reports())
                            .build());
                    evidenceIds.add(evidence.id());
                    if (!coverage.measured()) {
                        state.note("coverage not measured for " + inspection.adapter().id() + ": "
                                + coverage.note());
                    }
                }
                CoverageReport merged = mergeCoverage(results);
                if (!merged.measured() && results.isEmpty()) {
                    String reason = noBuildSucceeded(builds);
                    if (reason != null) {
                        merged = CoverageReport.notMeasured("none", reason + ", so coverage was not measured");
                    }
                }
                state.coverage(merged);
                return new PhaseOutcome(merged.measured(), merged.describe(), List.copyOf(evidenceIds),
                        merged.measured() ? null : merged.note());
            }));
        }

        // ---------------------------------------------------------------- mutation
        if (config.mutation().enabled() && state.existingTests().executed() && withinBudget(budget)) {
            List<Inspection> mutable = capable(inspections, Capability.MUTATION).stream()
                    .filter(inspection -> builtSuccessfully(builds.get(inspection.adapter().id())))
                    .filter(inspection -> {
                        TestResult tests = testsByAdapter.get(inspection.adapter().id());
                        return tests != null && tests.executed();
                    })
                    .toList();
            phases.add(runPhase("mutation", runId, project, listener, () -> {
                List<String> evidenceIds = new ArrayList<>();
                List<MutationReport> results = new ArrayList<>();
                for (Inspection inspection : mutable) {
                    VerificationContext languageContext = contextFor(inspection, runId, project, config, workspace,
                            artifacts, budget, requirements, llm, scoped, sandbox, state, ledger, startedAt,
                            RequirementCodeMap.empty(), database);
                    List<VerificationTools.MutationTester> testers = providers.mutationTesters(inspection.info());
                    MutationReport report = testers.isEmpty()
                            ? MutationReport.notMeasured("none", "no mutation tester registered for "
                                    + inspection.adapter().id())
                            : testers.getFirst().analyse(languageContext);
                    results.add(report);
                    Evidence evidence = ledger.add(ledger.newEvidence(EvidenceType.MUTATION_TEST)
                            .description("Mutation testing (" + inspection.adapter().id() + "): "
                                    + report.describe())
                            .confidence(EvidenceConfidence.DETERMINISTIC)
                            .status(!report.measured() ? EvidenceStatus.INCONCLUSIVE
                                    : (report.survivors().isEmpty() ? EvidenceStatus.SUPPORTS
                                            : EvidenceStatus.INCONCLUSIVE))
                            .result(report.describe())
                            .metadata("adapter", inspection.adapter().id())
                            .metadata("scorePercent", report.scorePercent())
                            .metadata("survivors", report.survivors().stream().limit(50)
                                    .map(SurvivingMutant::location).toList())
                            .artifacts(report.reports())
                            .build());
                    evidenceIds.add(evidence.id());
                    if (!report.measured()) {
                        state.note("mutation testing did not run for " + inspection.adapter().id() + ": "
                                + report.note());
                    }
                }
                MutationReport merged = mergeMutation(results);
                state.mutation(merged);
                return new PhaseOutcome(merged.measured(), merged.describe(), List.copyOf(evidenceIds),
                        merged.measured() ? null : truncate(merged.note(), 240));
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
        recordDeclarationProblems(requirements, config, limitations, listener);

        // ---------------------------------------------------------------- requirement strategies
        // Strategies are providers, exactly like compilers and test runners: the composition root
        // registers the ones it wants and the engine runs whatever it finds, in registration order. A bare
        // engine with an empty registry - a unit test, an embedder that wired only tools - runs the built-in
        // set rather than silently running none, because a run with no strategies would report every
        // requirement UNVERIFIED for a reason that is about our wiring, not about the code. A registry that
        // has strategies is honoured as it stands, even when every one of them declines this project.
        List<VerificationStrategy> strategies = providers.hasStrategies()
                ? providers.strategies(info)
                : DefaultStrategies.create(providers).stream()
                        .filter(strategy -> strategy.supports(info))
                        .toList();
        Map<String, List<ObligationOutcome>> outcomesByRequirement = new LinkedHashMap<>();
        Map<String, List<Counterexample>> counterexamplesByRequirement = new LinkedHashMap<>();
        Map<String, List<String>> notesByRequirement = new LinkedHashMap<>();

        for (Requirement requirement : plannedSet.requirements()) {
            List<ObligationOutcome> outcomes = new ArrayList<>();
            List<Counterexample> counterexamples = new ArrayList<>();
            List<String> notes = new ArrayList<>();
            // The strategies for a requirement run against the language its code is written in, not
            // against the union of every language in the tree. A test runner, a generator or a replay
            // command is language specific, and handing a Python requirement Java's runner would produce a
            // recipe for a command that cannot run.
            Inspection owner = ownerOf(requirement, mapper, inspections);
            Inspection running = owner != null ? owner : inspections.getFirst();
            VerificationContext requirementContext = contextFor(running, runId, project, config, workspace,
                    artifacts, budget, requirements, llm, scoped, sandbox, state, ledger, startedAt, mapper,
                    database);
            if (inspections.size() > 1 && owner == null) {
                notes.add("This requirement could not be attributed to any language in the project, so its "
                        + "strategies ran against " + running.adapter().id()
                        + ", the first adapter that claimed the project.");
            }
            for (VerificationStrategy strategy : strategies) {
                if (!strategy.supports(requirementContext.projectInfo())) {
                    continue;
                }
                try {
                    StrategyOutcome outcome =
                            strategy.verify(requirementContext, requirement, requirement.obligations());
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
                    recordIssue(SelfIssueCategory.STRATEGY_FAILURE, SelfIssueSeverity.HIGH,
                            "Strategy \"" + strategy.id() + "\" failed while verifying "
                                    + requirement.id() + ": " + e,
                            e, runId, project,
                            Map.of(SelfIssueRecord.PHASE, "requirements",
                                    SelfIssueRecord.STRATEGY, strategy.id(),
                                    SelfIssueRecord.REQUIREMENT, requirement.id(),
                                    SelfIssueRecord.ADAPTER, running.adapter().id()));
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
            // Blocking is per language. A requirement written in a language that compiled is not blocked
            // by a sibling language that did not: the two are different code, and one failing to build says
            // nothing about the other.
            Blocked blocked = blockedFor(requirement, mapper, inspections, builds, state.build());
            RequirementVerdict verdict = VerdictCalculator.compute(
                    requirement,
                    requirement.obligations(),
                    outcomesByRequirement.getOrDefault(requirement.id(), List.of()),
                    requirementEvidence,
                    counterexamplesByRequirement.getOrDefault(requirement.id(), List.of()),
                    blocked.blocked(),
                    blocked.blocked() ? blocked.reason() : null,
                    notesByRequirement.getOrDefault(requirement.id(), List.of()));
            verdicts.add(verdict);
            listener.onEvent("verdict", requirement.id() + " -> " + verdict.status());
        }

        // Suppression happens once, here, after every phase has contributed: findings are advisory, so a
        // suppressed one changes no verdict, and filtering at the point of collection would have to be
        // repeated in every phase. What it must not do is disappear silently - the count, the rules and the
        // reasons are all recorded below, because a reader has to be able to see that a rule was silenced and
        // judge whether that was right.
        List<Finding> suppressed = new ArrayList<>();
        FindingBaseline baseline = baselineFor(config.findings().baseline(), limitations);
        List<Finding> kept = suppressFindings(state.findings(), config, suppressed, baseline);
        if (!suppressed.isEmpty()) {
            Map<String, Long> byRule = new java.util.TreeMap<>();
            for (Finding finding : suppressed) {
                byRule.merge(finding.ruleId(), 1L, Long::sum);
            }
            StringBuilder reasons = new StringBuilder();
            for (VerificationConfig.Findings.Suppression entry : config.findings().suppress()) {
                long count = suppressed.stream()
                        .filter(finding -> entry.matches(finding.ruleId(), finding.file()))
                        .count();
                if (count > 0) {
                    reasons.append(reasons.isEmpty() ? "" : "; ").append(entry.rule())
                            .append(" (").append(count).append(")");
                    if (entry.reason() != null && !entry.reason().isBlank()) {
                        reasons.append(": ").append(entry.reason().trim());
                    }
                    if (entry.path() != null && !entry.path().isBlank()) {
                        reasons.append(" [only in ").append(entry.path()).append(']');
                    }
                }
            }
            limitations.add(suppressed.size() + " finding(s) were suppressed by configuration: "
                    + reasons + ". The rules are still installed; suppression is a statement about these "
                    + "findings in this project, not a claim that the rule is wrong.");
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
        // A failed build makes the run BLOCKED because it stopped requirements from being verified - so the
        // question is whether it actually stopped any. In a polyglot project one language can fail to build
        // while the requirements written in another were fully evaluated; calling that run truncated would
        // be as wrong as calling it clean. A run with no requirements at all is still BLOCKED, because then
        // there is nothing to show for the build failure.
        boolean buildStoppedSomething = buildBlocked
                && (verdicts.isEmpty()
                        || verdicts.stream().anyMatch(v -> v.status() == RequirementStatus.BLOCKED));
        RunStatus runStatus = buildStoppedSomething || budgetBlocked ? RunStatus.BLOCKED : RunStatus.COMPLETED;
        Instant finishedAt = Instant.now();

        VerificationRun run = new VerificationRun(runId.value(), project, runStatus, phases,
                environment(project, info, config, workspace, sandbox, state, requirements, startedAt, finishedAt),
                config.toBudget(), budget.snapshot(), workspace.root(), null, null, startedAt, finishedAt,
                limitations);

        return VerificationReport.builder(runId.value(), info, plannedSet)
                .verdicts(verdicts)
                .languages(languageSummaries(inspections, builds, uncovered))
                .evidence(ledger.all())
                .counterexamples(counterexamplesByRequirement.values().stream().flatMap(List::stream).toList())
                .build(buildSummary(state.build()))
                .tests(state.existingTests())
                .coverage(state.coverage())
                .mutation(state.mutation())
                .findings(kept)
                .dependencies(state.dependencies())
                .run(run)
                .limitations(limitations)
                .build();
    }

    // ------------------------------------------------------------------ helpers

    private record PhaseOutcome(boolean success, String summary, List<String> evidenceIds, String note) {
    }

    /**
     * One language adapter and the description of the part of the project it claimed.
     *
     * <p>Every phase that runs before the requirement strategies runs once per inspection, against that
     * inspection's own {@link ProjectInfo}. That is what keeps a polyglot run from handing a Python
     * analyzer a Java project, or vice versa.
     */
    private record Inspection(LanguageAdapter adapter, ProjectInfo info) {
    }

    /** Whether a requirement is blocked because its own language could not be built, and why. */
    private record Blocked(boolean blocked, String reason) {
    }

    /** One language's dependency inventory, paired with the inspection that produced it. */
    private record LanguageInventory(Inspection inspection, DependencyReport report) {
    }

    /**
     * Inspects the project once per adapter that claimed it.
     *
     * <p>Inspection is where an adapter decides what it can see, so it needs the context of a project it
     * recognises; a detection exception is an infrastructure failure of that adapter and stops the run,
     * because continuing would mean reporting a project whose languages could not be established.
     */
    private List<Inspection> inspectAll(
            List<LanguageAdapter> adapters,
            RunId runId,
            ProjectRef project,
            VerificationConfig config,
            WorkspaceManager.Workspace workspace,
            ArtifactStore artifacts,
            BudgetUsage.Tracker budget,
            RequirementSet requirements,
            ProgressListener listener,
            ExecutionSandbox sandbox,
            RunState state,
            EvidenceLedger ledger,
            Instant startedAt,
            PreparedDatabase database) {

        List<Inspection> inspections = new ArrayList<>();
        for (LanguageAdapter adapter : adapters) {
            VerificationContext inspectContext = context(runId, project, emptyInfo(project), config, workspace,
                    artifacts, budget, requirements, LlmProvider.disabled("not initialised"), listener, sandbox,
                    state, ledger, startedAt, RequirementCodeMap.empty(), database);
            inspections.add(new Inspection(adapter, adapter.inspect(project, inspectContext)));
        }
        return inspections;
    }

    /**
     * @return one report entry per language the run accounted for: every language an adapter verified, then
     *     every language a detector recognised and nothing could verify. Both kinds are in one list so that a
     *     reader cannot see the verified set without also seeing what was left out of it.
     */
    private static List<VerificationReport.LanguageSummary> languageSummaries(
            List<Inspection> inspections,
            Map<String, BuildResult> builds,
            List<LanguageDetectorRegistry.Detection> uncovered) {
        List<VerificationReport.LanguageSummary> summaries = new ArrayList<>();
        for (Inspection inspection : inspections) {
            summaries.add(new VerificationReport.LanguageSummary(
                    inspection.adapter().id(),
                    inspection.info().language(),
                    inspection.info().buildSystem(),
                    inspection.info().modules().size(),
                    builds.containsKey(inspection.adapter().id()),
                    builtSuccessfully(builds.get(inspection.adapter().id())),
                    ""));
        }
        for (LanguageDetectorRegistry.Detection detection : uncovered) {
            summaries.add(VerificationReport.LanguageSummary.recognisedOnly(
                    detection.language(), detection.evidence()));
        }
        return List.copyOf(summaries);
    }

    /** @return the inspections whose adapter declares the given capability, in selection order. */
    private static List<Inspection> capable(List<Inspection> inspections, Capability capability) {
        return inspections.stream().filter(i -> i.adapter().capabilities().contains(capability)).toList();
    }

    /**
     * @return the inspections a build-dependent analysis phase may run against: those that declare
     *     analysis capability and whose own build succeeded.
     *
     * <p>When nothing declares a build at all the phase still runs, because an adapter without a build
     * step (a purely interpreted language, say) has no build to wait for; when builds were attempted, a
     * language whose build failed is left out - analysing code that does not compile would report
     * findings about a state the project is not in.
     */
    private static List<Inspection> analysable(List<Inspection> inspections, Map<String, BuildResult> builds) {
        if (builds.isEmpty()) {
            return List.copyOf(inspections);
        }
        return inspections.stream()
                .filter(i -> builtSuccessfully(builds.get(i.adapter().id())))
                .toList();
    }

    private static boolean builtSuccessfully(BuildResult build) {
        return build != null && build.success();
    }

    /**
     * @return the reason a build-dependent phase had nothing to analyse because no language's build
     *     succeeded, or {@code null} when a build was never attempted or at least one succeeded.
     *
     * <p>The dependency and coverage phases select languages by a successful build, so an empty
     * selection is ambiguous. "No analyzer is registered" is a configuration answer; a failed build is
     * an infrastructure answer, and the two must stay distinguishable - a report that names the wrong
     * one sends a reader looking for an adapter that is in fact there.
     */
    private static String noBuildSucceeded(Map<String, BuildResult> builds) {
        if (builds.isEmpty() || builds.values().stream().anyMatch(VerificationEngine::builtSuccessfully)) {
            return null;
        }
        return "no language's build succeeded";
    }

    /** @return a context bound to one language's project description but to the run's shared state. */
    private VerificationContext contextFor(
            Inspection inspection,
            RunId runId,
            ProjectRef project,
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
            Instant startedAt,
            RequirementCodeMap codeMap,
            PreparedDatabase database) {
        return context(runId, project, inspection.info(), config, workspace, artifacts, budget, requirements, llm,
                listener, sandbox, state, ledger, startedAt, codeMap, database);
    }

    /**
     * @return the inspection that should own a requirement's strategies: the one whose language the
     *     requirement's code is written in, or {@code null} when no mapped file identifies one.
     */
    private static Inspection ownerOf(Requirement requirement, RequirementCodeMapper mapper,
            List<Inspection> inspections) {
        for (String file : mapper.filesFor(requirement.id())) {
            String extension = extensionOf(file);
            if (extension.isEmpty()) {
                continue;
            }
            for (Inspection inspection : inspections) {
                if (inspection.info().sourceExtensions().contains(extension)) {
                    return inspection;
                }
            }
        }
        return null;
    }

    /** @return the lower-case extension of a path, without the dot, or the empty string. */
    private static String extensionOf(String path) {
        if (path == null) {
            return "";
        }
        String name = path.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Decides whether a requirement is blocked by a failed build.
     *
     * <p>The rule is deliberately conservative in one direction only: a requirement is blocked when the
     * build of a language its code is written in did not succeed. A requirement that cannot be attributed
     * to any language - no file mentions it, or none of the languages claims its extension - falls back to
     * the whole-run build outcome, because there is nothing better to go on. What it never does is let a
     * language that built be blocked by a sibling that did not.
     */
    private Blocked blockedFor(Requirement requirement, RequirementCodeMapper mapper,
            List<Inspection> inspections, Map<String, BuildResult> builds, BuildResult mergedBuild) {
        Set<String> languages = new java.util.LinkedHashSet<>();
        for (String file : mapper.filesFor(requirement.id())) {
            String extension = extensionOf(file);
            for (Inspection inspection : inspections) {
                if (inspection.info().sourceExtensions().contains(extension)) {
                    languages.add(inspection.adapter().id());
                }
            }
        }
        if (languages.isEmpty()) {
            // Nothing identifies which language this requirement's code is written in, so the honest
            // fallback is the whole run's build outcome rather than a guess.
            return new Blocked(!mergedBuild.success(), mergedBuild.success() ? null : mergedBuild.describe());
        }
        List<String> failed = languages.stream().filter(language -> !builtSuccessfully(builds.get(language))).toList();
        if (failed.isEmpty()) {
            return new Blocked(false, null);
        }
        return new Blocked(true, "the " + String.join(", ", failed) + " code this requirement covers could not "
                + "be built, so nothing about it could be established");
    }

    /**
     * @return one result standing for every language's build.
     *
     * <p>It succeeds only when every build that ran succeeded, so the run-level summary can never claim a
     * project built when one of its languages did not. The failing language's command, exit code and
     * output are the ones kept, because they are the ones a reader needs; every language's own record
     * stays in the evidence ledger, named by adapter.
     */
    private static BuildResult mergeBuilds(List<BuildResult> results) {
        if (results.isEmpty()) {
            return BuildResult.notAttempted("no build was attempted");
        }
        if (results.size() == 1) {
            return results.getFirst();
        }
        boolean success = results.stream().allMatch(BuildResult::success);
        BuildResult reported = results.stream().filter(result -> !result.success()).findFirst()
                .orElse(results.getFirst());
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        List<Path> artifacts = new ArrayList<>();
        StringBuilder output = new StringBuilder();
        Duration duration = Duration.ZERO;
        for (BuildResult result : results) {
            diagnostics.addAll(result.diagnostics());
            artifacts.addAll(result.artifacts());
            if (!result.output().isBlank()) {
                output.append(result.output()).append('\n');
            }
            duration = duration.plus(result.duration());
        }
        return BuildResult.builder()
                .attempted(true)
                .success(success)
                .exitCode(reported.exitCode())
                .duration(duration)
                .command(reported.command())
                .output(output.toString())
                .diagnostics(diagnostics)
                .artifacts(artifacts)
                .timedOut(results.stream().anyMatch(BuildResult::timedOut))
                .failureReason(success ? null
                        : "one or more languages failed to build; see the per-language build evidence")
                .build();
    }

    /**
     * @return one result standing for every language's test suite.
     *
     * <p>Counters are summed and every language's cases are kept, because each one is evidence about its
     * own requirement. {@code executed} stays true when any suite ran, so a language whose tests did run
     * is not reported as untested because a sibling's did not.
     */
    private static TestResult mergeTests(List<TestResult> results) {
        if (results.isEmpty()) {
            return TestResult.notExecuted("no language adapter declares a test capability, or no build "
                    + "succeeded, so no test suite was run");
        }
        if (results.size() == 1) {
            return results.getFirst();
        }
        List<TestCaseResult> cases = new ArrayList<>();
        List<Path> reports = new ArrayList<>();
        Duration duration = Duration.ZERO;
        int total = 0;
        int passed = 0;
        int failed = 0;
        int errors = 0;
        int skipped = 0;
        boolean executed = false;
        List<String> notes = new ArrayList<>();
        for (TestResult result : results) {
            cases.addAll(result.cases());
            reports.addAll(result.reports());
            duration = duration.plus(result.duration());
            total += result.total();
            passed += result.passed();
            failed += result.failed();
            errors += result.errors();
            skipped += result.skipped();
            executed |= result.executed();
            if (result.note() != null && !result.note().isBlank()) {
                notes.add(result.note());
            }
        }
        return new TestResult(executed, String.join("; ", notes), total, passed, failed, errors, skipped,
                duration, cases, reports);
    }

    /**
     * @return one result standing for every language's coverage.
     *
     * <p>Counters are summed rather than averaged: an average of percentages would let a language with
     * three instrumented lines outweigh one with three thousand. A language whose coverage could not be
     * measured leaves the aggregate measured when another's could be, and says so in the note.
     */
    private static CoverageReport mergeCoverage(List<CoverageReport> results) {
        if (results.isEmpty()) {
            return CoverageReport.notMeasured("none", "no coverage provider was registered for any language");
        }
        if (results.size() == 1) {
            return results.getFirst();
        }
        int lineCovered = 0;
        int lineTotal = 0;
        int branchCovered = 0;
        int branchTotal = 0;
        boolean measured = false;
        List<Path> reports = new ArrayList<>();
        Map<String, Double> perClass = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        for (CoverageReport result : results) {
            lineCovered += result.lineCovered();
            lineTotal += result.lineTotal();
            branchCovered += result.branchCovered();
            branchTotal += result.branchTotal();
            measured |= result.measured();
            reports.addAll(result.reports());
            perClass.putAll(result.perClassLineCoverage());
            if (!result.note().isBlank()) {
                notes.add(result.note());
            }
        }
        return new CoverageReport(measured, "aggregate", String.join("; ", notes), lineCovered, lineTotal,
                branchCovered, branchTotal, perClass, reports);
    }

    /**
     * @return one result standing for every language's mutation run.
     *
     * <p>Counters are summed so the score is a kill ratio over every mutant the run generated, which is
     * the only aggregation that stays meaningful across languages; survivors from every language are kept
     * because each one is a weakness in the tests covering that language's code.
     */
    private static MutationReport mergeMutation(List<MutationReport> results) {
        if (results.isEmpty()) {
            return MutationReport.notMeasured("none", "no mutation tester was registered for any language");
        }
        if (results.size() == 1) {
            return results.getFirst();
        }
        int generated = 0;
        int killed = 0;
        int survived = 0;
        int noCoverage = 0;
        int timedOut = 0;
        boolean measured = false;
        List<SurvivingMutant> survivors = new ArrayList<>();
        List<Path> reports = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (MutationReport result : results) {
            generated += result.generated();
            killed += result.killed();
            survived += result.survived();
            noCoverage += result.noCoverage();
            timedOut += result.timedOut();
            measured |= result.measured();
            survivors.addAll(result.survivors());
            reports.addAll(result.reports());
            if (!result.note().isBlank()) {
                notes.add(result.note());
            }
        }
        return new MutationReport(measured, "aggregate", String.join("; ", notes), generated, killed, survived,
                noCoverage, timedOut, survivors, reports);
    }

    /**
     * @return one inventory standing for every language's dependencies.
     *
     * <p>Coordinates are the union, because the same package can legitimately appear in two ecosystems and
     * each is a different package. The per-module breakdown is keyed by
     * {@link DependencyReport#moduleKey}, so a dependency resolved by one language's module is never
     * attributed to a requirement written in another's.
     */
    private static DependencyReport mergeDependencies(List<LanguageInventory> inventories) {
        if (inventories.isEmpty()) {
            return DependencyReport.notAnalysed("none", "no dependency analyzer was registered for any language");
        }
        List<PackageCoordinate> coordinates = new ArrayList<>();
        Map<String, List<String>> byModule = new LinkedHashMap<>();
        List<DependencyVulnerability> vulnerabilities = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<String> tools = new ArrayList<>();
        boolean analysed = false;
        boolean scanned = false;
        for (LanguageInventory inventory : inventories) {
            DependencyReport report = inventory.report();
            LanguageId language = inventory.inspection().info().language();
            analysed |= report.analysed();
            scanned |= report.advisoryScan().performed();
            coordinates.addAll(report.coordinates());
            vulnerabilities.addAll(report.vulnerabilities());
            tools.add(report.tool());
            if (report.analysed()) {
                notes.add(report.tool());
            }
            report.dependenciesByModule().forEach((module, dependencies) ->
                    byModule.merge(DependencyReport.moduleKey(language, module),
                            List.copyOf(dependencies), (first, second) -> {
                                List<String> merged = new ArrayList<>(first);
                                merged.addAll(second);
                                return List.copyOf(merged);
                            }));
        }
        List<PackageCoordinate> distinct = PackageCoordinate.distinct(coordinates);
        List<DependencyVulnerability> distinctFindings = new ArrayList<>(new java.util.LinkedHashSet<>(vulnerabilities));
        AdvisoryScan scan = scanned
                ? AdvisoryScan.performed("osv", null, null,
                        "advisory scans ran once per language; see the per-language dependency evidence for "
                                + "what each one covered")
                : AdvisoryScan.notPerformed("no language's inventory carried a resolved coordinate, so no "
                        + "advisory source was consulted");
        // One inventory per language, so the merged note names the languages rather than concatenating
        // what each of them said: five paragraphs of the same explanatory sentence is not a report, and
        // the per-language wording is already in the evidence ledger where it can be read one at a time.
        String note = "inventory only: the resolved dependencies of " + inventories.size()
                + " language(s), read from " + String.join(", ", new java.util.LinkedHashSet<>(notes))
                + "; what each of those inventories covers is recorded in its own dependency evidence";
        return new DependencyReport(analysed, String.join("+", new java.util.LinkedHashSet<>(tools)),
                note, distinct.size(), distinct.stream().map(PackageCoordinate::display).toList(),
                byMap(byModule), distinct, distinctFindings, scan);
    }

    private static Map<String, List<String>> byMap(Map<String, List<String>> input) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        input.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Map.copyOf(copy);
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

    private PhaseRecord runPhase(
            String name,
            RunId runId,
            ProjectRef project,
            ProgressListener listener,
            java.util.function.Supplier<PhaseOutcome> body) {
        Instant start = Instant.now();
        listener.onEvent(name, "started");
        try {
            PhaseOutcome outcome = body.get();
            PhaseRecord record = new PhaseRecord(name, true, outcome.success(), Duration.between(start, Instant.now()),
                    outcome.summary(), outcome.evidenceIds(), outcome.note());
            listener.onEvent(name, outcome.summary());
            return record;
        } catch (RuntimeException e) {
            // A phase that throws is a fault in the phase implementation, not a finding about the project.
            // It still becomes an honest record below, but it also belongs in the self-issue journal: the
            // next run should not have to rediscover it.
            recordIssue(SelfIssueCategory.PHASE_FAILURE, SelfIssueSeverity.HIGH,
                    "Phase \"" + name + "\" failed: " + e, e, runId, project,
                    Map.of(SelfIssueRecord.PHASE, name));
            listener.onEvent(name, "failed: " + e);
            return new PhaseRecord(name, true, false, Duration.between(start, Instant.now()),
                    "phase failed: " + e.getMessage(), List.of(), e.toString());
        }
    }

    /**
     * Records a fault the engine found in itself.
     *
     * <p>The common context (run, project, version) is filled here so that every engine-originated
     * issue can be traced to the run that exposed it without each call site repeating it.
     */
    private void recordIssue(
            SelfIssueCategory category,
            SelfIssueSeverity severity,
            String message,
            Throwable error,
            RunId runId,
            ProjectRef project,
            Map<String, String> context) {
        Map<String, String> values = new LinkedHashMap<>(context == null ? Map.of() : context);
        values.putIfAbsent(SelfIssueRecord.RUN_ID, runId.value());
        values.putIfAbsent(SelfIssueRecord.PROJECT, project.root().toString());
        values.putIfAbsent(SelfIssueRecord.VERSION, VERSION);
        selfIssues.record(category, severity, message, error, values);
    }

    /**
     * Prepares the run's database, if the configuration asked for one.
     *
     * <p>The fixture is chosen by the registry, exactly as a compiler or a test runner is: the engine
     * names no database engine. A fixture that cannot prepare a database - a missing schema, a URL that
     * nothing answers, an engine nobody implements - comes back as an honest "not prepared" with the
     * reason, which becomes a limitation rather than a crash or a silent pass.
     */
    private PreparedDatabase prepareDatabase(
            VerificationConfig config,
            Path projectRoot,
            Path runDirectory,
            ArtifactStore artifacts,
            ProgressListener listener,
            RunId runId,
            ProjectRef project) {
        if (!config.database().enabled()) {
            return PreparedDatabase.none("no database was requested (set database.enabled to prepare one)");
        }
        DatabaseFixture.Request request =
                new DatabaseFixture.Request(config, projectRoot, runDirectory, artifacts);
        for (DatabaseFixture fixture : providers.databaseFixtures()) {
            if (!fixture.supports(request)) {
                continue;
            }
            listener.onEvent("database", "preparing the database with " + fixture.id());
            try {
                PreparedDatabase prepared = fixture.prepare(request);
                return prepared == null
                        ? PreparedDatabase.notPrepared(fixture.id(), "the fixture returned no result")
                        : prepared;
            } catch (RuntimeException e) {
                // A throwing fixture is a bug in the fixture, but a run must not die on it: the database is
                // reported as unavailable and everything that needed one stays unverified.
                recordIssue(SelfIssueCategory.ADAPTER_FAILURE, SelfIssueSeverity.HIGH,
                        "Database fixture \"" + fixture.id() + "\" failed: " + e, e, runId, project,
                        Map.of(SelfIssueRecord.PHASE, "database",
                                SelfIssueRecord.ADAPTER, fixture.id()));
                return PreparedDatabase.notPrepared(fixture.id(),
                        "the fixture failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        return PreparedDatabase.notPrepared(config.database().engine(),
                "no registered database fixture claims engine \"" + config.database().engine()
                        + "\"; registered fixtures are " + providers.databaseFixtures().stream()
                                .map(DatabaseFixture::id).toList());
    }

    /**
     * Records what the database fixture produced.
     *
     * <p>A prepared database is evidence about the experiment, not about the code, and it is recorded so
     * that a counterexample naming a table can be traced to the schema that was actually loaded. A
     * requested database that could not be prepared is a limitation, because every requirement that needed
     * one is then unverified for a reason the reader can act on.
     */
    private List<String> recordDatabase(
            PreparedDatabase database,
            VerificationConfig config,
            EvidenceLedger ledger,
            ProgressListener listener) {
        List<String> limitations = new ArrayList<>();
        if (database.prepared()) {
            Evidence evidence = ledger.add(ledger.newEvidence(EvidenceType.DATABASE_FIXTURE)
                    .description("Prepared a database for the run: " + database.description())
                    .source(database.id())
                    .confidence(EvidenceConfidence.DETERMINISTIC)
                    .status(EvidenceStatus.SUPPORTS)
                    .result(database.description())
                    .metadata("jdbcUrl", String.valueOf(database.jdbcUrl()))
                    .metadata("environment", database.environment().keySet().stream().sorted().toList())
                    .build());
            listener.onEvent("database", database.description());
            if (!database.environment().isEmpty()) {
                limitations.add("A database was prepared for this run (" + database.description()
                        + ") and exported to every process as "
                        + String.join(", ", database.environment().keySet())
                        + "; the run directory keeps it so a counterexample against it can be replayed.");
            }
            return limitations;
        }
        if (!config.database().enabled()) {
            return limitations;
        }
        Evidence evidence = ledger.add(ledger.newEvidence(EvidenceType.DATABASE_FIXTURE)
                .description("A database was requested but not prepared")
                .source(database.id())
                .confidence(EvidenceConfidence.DETERMINISTIC)
                .status(EvidenceStatus.INCONCLUSIVE)
                .result(String.valueOf(database.note()))
                .build());
        limitations.add("A database was requested but none is available: " + database.limitation()
                + " Requirements that need one stay UNVERIFIED rather than being reported as verified.");
        listener.onEvent("database", "not prepared: " + database.note());
        return limitations;
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
            Instant startedAt,
            RequirementCodeMap codeMap,
            PreparedDatabase database) {
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
                .codeMap(codeMap)
                .database(database)
                .evidenceLedger(ledger)
                .startedAt(startedAt)
                .build();
    }

    /**
     * Records declarations that cannot take effect, because silence about them is indistinguishable from
     * a project that declared nothing.
     *
     * <p>Two mistakes are cheap to make and were previously invisible. A requirements document with a
     * repeated heading produces two requirements that share an id, and every verdict, table and index keyed
     * by that id then has to guess which one is meant. A scenario whose {@code requirement} names an id that
     * is not declared is never visited - the planner matches scenarios by walking the requirements - so a
     * typo silently disables the only executable evidence for that requirement, and the run reports
     * {@code UNVERIFIED} as though no scenario had been written at all.
     *
     * <p>Neither is fatal: a repeated id is still verifiable and an orphan scenario simply does nothing. Both
     * are stated, in the report and on the console, because a verification tool must not let a declaration
     * quietly fail to apply.
     *
     * @param requirements the declarations the run is verifying
     * @param config      run configuration, holding the declared scenarios
     * @param limitations receives one entry per problem found
     * @param listener    receives the same problems as live progress events
     */
    private static void recordDeclarationProblems(
            RequirementSet requirements,
            VerificationConfig config,
            List<String> limitations,
            ProgressListener listener) {
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (Requirement requirement : requirements.requirements()) {
            occurrences.merge(requirement.id(), 1, Integer::sum);
        }
        List<String> duplicates = occurrences.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();
        if (!duplicates.isEmpty()) {
            String message = "Some requirement ids are declared more than once: " + String.join(", ", duplicates)
                    + ". Each occurrence is verified separately, and a report keyed by id can only show one "
                    + "verdict per id - the most severe - so give each requirement its own id to remove the "
                    + "ambiguity.";
            limitations.add(message);
            listener.onEvent("plan", message);
        }
        Set<String> declared = Set.copyOf(occurrences.keySet());
        List<String> orphans = config.scenarios().stream()
                .map(Scenario::requirement)
                .filter(id -> id != null && !declared.contains(id))
                .distinct()
                .toList();
        if (!orphans.isEmpty()) {
            String message = "Scenario(s) name requirement id(s) that the requirements document does not "
                    + "declare: " + String.join(", ", orphans) + ". Those scenarios did not run, so the "
                    + "requirements they were written for have no executable evidence. Check the ids in ";
            limitations.add(message + "codewitness.yaml against the requirements file.");
            listener.onEvent("plan", message + "the configuration.");
        }
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

    /**
     * Drops the findings a suppression entry covers, collecting them for the record.
     *
     * @param findings   findings to filter
     * @param config     run configuration holding the suppression entries
     * @param suppressed receives every finding that was dropped
     * @return the findings that survive, in their original order
     */
    private static List<Finding> suppressFindings(
            List<Finding> findings,
            VerificationConfig config,
            List<Finding> suppressed,
            FindingBaseline baseline) {
        if (config.findings().suppress().isEmpty() && baseline.size() == 0) {
            return findings;
        }
        List<Finding> kept = new ArrayList<>();
        for (Finding finding : findings) {
            // A baseline entry is not a suppression: it says "this was here before", which is exactly what a
            // reviewer of a change wants to see filtered, and it is disclosed below with the same honesty.
            boolean known = baseline.contains(finding);
            boolean drop = known || config.findings().suppress().stream()
                    .anyMatch(entry -> entry.matches(finding.ruleId(), finding.file()));
            if (drop) {
                suppressed.add(finding);
            } else {
                kept.add(finding);
            }
        }
        return List.copyOf(kept);
    }

    /**
     * Loads the baseline a run was configured with, reporting rather than swallowing a bad file.
     *
     * @param baseline path from the configuration, may be {@code null}
     * @param limitations receives the outcome: what the baseline covered, or why it could not be read
     * @return the baseline, empty when none was configured or it could not be read
     */
    private static FindingBaseline baselineFor(Path baseline, List<String> limitations) {
        if (baseline == null) {
            return FindingBaseline.empty();
        }
        try {
            FindingBaseline loaded = FindingBaseline.load(baseline);
            limitations.add("A finding baseline was applied from " + baseline + ", which accounts for "
                    + loaded.size() + " finding(s). Findings already present there are not reported again; "
                    + "they are neither fixed nor waived, so the run says nothing about them.");
            return loaded;
        } catch (IllegalArgumentException e) {
            // A baseline that cannot be read must not silently become "no baseline": that would report every
            // known finding as new. It also must not stop the run, so the miss is stated and the run continues
            // with everything reported.
            limitations.add("The configured finding baseline could not be used, so every finding is reported: "
                    + e.getMessage());
            return FindingBaseline.empty();
        }
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

    /**
     * Renders the static-analysis findings artifact.
     *
     * <p>This used to build JSON by hand, escaping only backslash, quote, newline and carriage return. A tab,
     * a form feed or any other control character in a compiler or analyzer message - both produced by code
     * CodeWitness does not control - then made the artifact invalid JSON, which is worse than losing it,
     * because a consumer reads a parse error as a corrupt run. Jackson cannot get a string wrong, and the
     * report module already serialises its own JSON the same way.
     *
     * @param findings findings to render
     * @return JSON array of findings, pretty printed
     */
    private static String renderFindings(List<Finding> findings) {
        ArrayNode array = FINDINGS_MAPPER.createArrayNode();
        for (Finding finding : findings) {
            ObjectNode node = array.addObject();
            node.put("analyzer", finding.analyzer());
            node.put("rule", finding.ruleId());
            node.put("severity", finding.severity() == null ? null : finding.severity().name());
            node.put("category", finding.category() == null ? null : finding.category().name());
            node.put("file", finding.file());
            node.put("line", finding.line());
            node.put("message", finding.message());
        }
        try {
            return FINDINGS_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(array)
                    + System.lineSeparator();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("failed to render the static analysis findings artifact", e);
        }
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
