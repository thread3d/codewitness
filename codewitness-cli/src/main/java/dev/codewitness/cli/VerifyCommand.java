package dev.codewitness.cli;

import dev.codewitness.core.config.ConfigLoader;
import dev.codewitness.core.config.VerificationConfig;
import dev.codewitness.core.diagnostics.SelfIssueCategory;
import dev.codewitness.core.diagnostics.SelfIssueJournal;
import dev.codewitness.core.diagnostics.SelfIssueRecord;
import dev.codewitness.core.diagnostics.SelfIssueSeverity;
import dev.codewitness.core.engine.VerificationEngine;
import dev.codewitness.core.io.WorkspaceManager;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.requirements.RequirementParser;
import dev.codewitness.core.requirements.RequirementSet;
import dev.codewitness.core.requirements.RequirementStatus;
import dev.codewitness.core.result.Budget;
import dev.codewitness.core.result.VerificationReport;
import dev.codewitness.report.HtmlReportWriter;
import dev.codewitness.report.JsonReportWriter;
import dev.codewitness.storage.SqliteVerificationStore;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * {@code codewitness verify <project>} - the main command.
 *
 * <p>Exit codes are part of the interface: {@code 0} when nothing failed, {@code 1} when a
 * requirement has a counterexample, {@code 2} when verification was blocked, {@code 3} on an internal
 * error. A build failure therefore exits {@code 2}, never {@code 1}: a blocked run is not a verdict
 * about the code.
 */
@Command(name = "verify", description = "Verify a project against its requirements and produce a report.")
public final class VerifyCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<project>", description = "Project directory to verify.")
    private String project;

    @Option(names = "--requirements", paramLabel = "<file>",
            description = "Requirements file (Markdown or YAML).")
    private Path requirementsFile;

    @Option(names = "--config", paramLabel = "<file>", description = "Configuration file (default"
            + " <project>/codewitness.yaml).")
    private Path configFile;

    @Option(names = "--output", paramLabel = "<dir>", description = "Directory for run data and reports.")
    private Path outputDirectory;

    @Option(names = "--max-duration", paramLabel = "<duration>",
            description = "Overall budget, for example 10m.")
    private String maxDuration;

    @Option(names = "--max-generated-tests", paramLabel = "<n>", description = "Cap on generated tests.")
    private Integer maxGeneratedTests;

    @Option(names = "--max-llm-calls", paramLabel = "<n>", description = "Cap on language model calls.")
    private Integer maxLlmCalls;

    @Option(names = "--in-place", description = "Verify the project directory directly instead of copying it.")
    private boolean inPlace;

    @Option(names = "--no-tests", description = "Skip the existing test suite.")
    private boolean noTests;

    @Option(names = "--no-mutation", description = "Skip mutation testing.")
    private boolean noMutation;

    @Option(names = "--no-generated-tests", description = "Skip generation and execution of additional tests.")
    private boolean noGeneratedTests;

    @Option(names = "--no-static-analysis", description = "Skip static analysis.")
    private boolean noStaticAnalysis;

    @Option(names = "--no-security", description = "Skip security analysis.")
    private boolean noSecurity;

    @Option(names = "--llm", description = "Enable advisory language model assistance (off by default).")
    private boolean enableLlm;

    @Option(names = "--json", description = "Print the canonical JSON report to standard out.")
    private boolean printJson;

    @Option(names = "--quiet", description = "Only print the final summary.")
    private boolean quiet;

    @Option(names = "--fail-on-unverified",
            description = "Exit 1 when any requirement is not VERIFIED. Without this, exit 0 means only "
                    + "that nothing failed, which is not the same as everything passing.")
    private boolean failOnUnverified;

    @Override
    public Integer call() {
        ProjectRef projectRef = CliSupport.projectRef(project);
        SelfIssues.install(projectRef.root());
        SelfIssueJournal journal = SelfIssues.journal();
        Set<String> issuesBefore = journal.openIssueIds();
        ConfigLoader.Loaded loaded = CliSupport.loadConfig(projectRef.root(), configFile);
        VerificationConfig config = loaded.config();

        RequirementSet requirements = RequirementSet.empty();
        if (requirementsFile != null) {
            if (!Files.isRegularFile(requirementsFile)) {
                System.err.println("requirements file not found: " + requirementsFile);
                return 3;
            }
            requirements = RequirementParser.parseFile(requirementsFile);
            System.out.println("Loaded " + requirements.size() + " requirement(s) from " + requirementsFile);
        } else {
            Path conventional = projectRef.root().resolve("requirements.md");
            if (Files.isRegularFile(conventional)) {
                requirements = RequirementParser.parseFile(conventional);
                System.out.println("Loaded " + requirements.size()
                        + " requirement(s) from " + conventional + " (conventional location)");
            } else {
                System.out.println("No requirements file supplied: CodeWitness will run the deterministic "
                        + "verification phases and report that nothing could be mapped to a requirement.");
            }
        }

        config = applyOverrides(config);
        for (String warning : loaded.warnings()) {
            System.err.println("configuration warning: " + warning);
        }

        VerificationEngine engine = CliSupport.engine(
                issue -> journal.record(issue.withCommand(SelfIssues.command())));
        Path base = CliSupport.runBase(projectRef.root(), outputDirectory, config);
        if (!quiet) {
            System.out.println("CodeWitness " + VerificationEngine.VERSION + " verifying " + projectRef.name());
        }

        VerificationReport report;
        try {
            report = engine.verify(projectRef, requirements, config, CliSupport.consoleProgress(quiet),
                    base, inPlace ? WorkspaceManager.Mode.IN_PLACE : WorkspaceManager.Mode.COPY);
        } catch (RuntimeException e) {
            // The pipeline normally converts a phase failure into a report. Reaching this catch means the
            // failure escaped the pipeline itself, which is exactly the kind of defect that should not be
            // lost with the terminal.
            journal.record(SelfIssueCategory.INTERNAL_ERROR, SelfIssueSeverity.CRITICAL,
                    "Verification pipeline failed: " + e, e,
                    Map.of(SelfIssueRecord.PHASE, "verify",
                            SelfIssueRecord.PROJECT, projectRef.root().toString(),
                            SelfIssueRecord.COMMAND, SelfIssues.command(),
                            SelfIssueRecord.VERSION, VerificationEngine.VERSION));
            System.err.println("verification failed: " + e);
            System.err.println("codewitness: self-issue log: " + journal.markdownPath());
            return 3;
        }

        JsonReportWriter json = new JsonReportWriter();
        HtmlReportWriter html = new HtmlReportWriter();
        Path runDirectory = base.resolve("runs").resolve(report.runId());
        Path jsonPath = null;
        Path htmlPath = null;
        String jsonText = json.render(report);
        try {
            if (config.report().json()) {
                jsonPath = json.write(report, runDirectory.resolve("report.json"));
            }
            if (config.report().html()) {
                htmlPath = html.write(report, runDirectory.resolve("report.html"));
            }
            try (SqliteVerificationStore store = new SqliteVerificationStore(
                    base.resolve("codewitness.db"))) {
                store.save(report, jsonText);
            }
        } catch (RuntimeException e) {
            journal.record(SelfIssueCategory.REPORT_FAILURE, SelfIssueSeverity.HIGH,
                    "Report artefacts could not be written completely: " + e, e,
                    Map.of(SelfIssueRecord.PHASE, "report",
                            SelfIssueRecord.RUN_ID, report.runId(),
                            SelfIssueRecord.PROJECT, projectRef.root().toString(),
                            SelfIssueRecord.COMMAND, SelfIssues.command(),
                            SelfIssueRecord.VERSION, VerificationEngine.VERSION));
            // A verification whose result cannot be read back is not evidence, so this is a command failure
            // and not a warning. It used to be a single yellow line while the exit code was computed from the
            // verdicts alone, which meant a full disk or an unreadable index lost the run and still exited 0.
            System.err.println("codewitness: the run completed but its result could not be stored: " + e);
            System.err.println("codewitness: the verdicts above were observed, but nothing was recorded, so "
                    + "`codewitness report` will not show this run.");
            System.err.println("codewitness: self-issue log: " + journal.markdownPath());
            System.out.println();
            System.out.println(ConsoleReporter.render(report));
            return 3;
        }

        if (printJson) {
            System.out.println(jsonText);
        } else {
            System.out.println();
            System.out.println(ConsoleReporter.render(report));
        }
        if (jsonPath != null) {
            System.out.println("JSON report: " + jsonPath);
        }
        if (htmlPath != null) {
            System.out.println("HTML report: " + htmlPath);
        }

        // Only new issues are announced. Repeating the whole backlog on every run would train the reader
        // to ignore the line that matters; an issue that is already open is still in `codewitness issues`.
        Set<String> newIssues = new LinkedHashSet<>(journal.openIssueIds());
        newIssues.removeAll(issuesBefore);
        if (!newIssues.isEmpty()) {
            System.err.println();
            System.err.println("codewitness: recorded " + newIssues.size() + " new self-issue(s): "
                    + String.join(", ", newIssues));
            System.err.println("codewitness: review with: codewitness issues --project "
                    + projectRef.root());
        }

        return exitCode(report.verdicts().stream().map(v -> v.status()).toList(), report.run().status(),
                failOnUnverified);
    }

    private VerificationConfig applyOverrides(VerificationConfig config) {
        VerificationConfig result = config;
        if (maxDuration != null || maxGeneratedTests != null || maxLlmCalls != null) {
            Budget budget = config.toBudget();
            if (maxDuration != null) {
                budget = budget.withMaxDuration(dev.codewitness.core.config.DurationParser.parse(maxDuration,
                        Duration.ofMinutes(15)));
            }
            if (maxGeneratedTests != null) {
                budget = budget.withMaxGeneratedTests(maxGeneratedTests);
            }
            if (maxLlmCalls != null) {
                budget = budget.withMaxLlmCalls(maxLlmCalls);
            }
            result = result.withBudget(budget);
        }
        VerificationConfig.Limits limits = result.limits();
        VerificationConfig.Toggle tests = noTests ? VerificationConfig.Toggle.off() : result.tests();
        VerificationConfig.Mutation mutation = noMutation
                ? new VerificationConfig.Mutation(false, result.mutation().tool(), result.mutation().timeout(),
                        result.mutation().targetClasses())
                : result.mutation();
        VerificationConfig.PropertyTesting propertyTesting = noGeneratedTests
                ? new VerificationConfig.PropertyTesting(false, result.propertyTesting().maxScenarios(),
                        result.propertyTesting().concurrentScenarios())
                : result.propertyTesting();
        VerificationConfig.StaticAnalysis staticAnalysis = noStaticAnalysis
                ? new VerificationConfig.StaticAnalysis(false, result.staticAnalysis().analyzers(),
                        result.staticAnalysis().lintAll())
                : result.staticAnalysis();
        VerificationConfig.Security security = noSecurity
                ? new VerificationConfig.Security(false) : result.security();
        VerificationConfig.Llm llm = enableLlm
                ? new VerificationConfig.Llm(true, result.llm().provider(), result.llm().model(),
                        result.llm().baseUrl(), result.llm().apiKeyEnvironmentVariable(),
                        Math.max(1, result.llm().maxCalls()), result.llm().temperature(), result.llm().timeout())
                : result.llm();
        return new VerificationConfig(result.project(), result.language(), limits, tests, mutation,
                propertyTesting, staticAnalysis, security, llm, result.report(), result.sandbox(),
                result.scenarios(), result.database());
    }

    /**
     * Maps a finished run onto the documented exit code.
     *
     * <p>Exit codes are part of the interface, so the decision is a pure function of the verdicts and
     * the run status, and is tested directly rather than only through a full verification.
     *
     * @param statuses         one status per requirement
     * @param runStatus        overall run status, or {@code null} when unknown
     * @param failOnUnverified {@code true} to treat "not verified" as a failure
     * @return {@code 1} on a counterexample (or, with the flag, on anything unverified), {@code 2} when
     *     verification was blocked, otherwise {@code 0}
     */
    static int exitCode(
            List<RequirementStatus> statuses,
            dev.codewitness.core.result.RunStatus runStatus,
            boolean failOnUnverified) {

        if (statuses.contains(RequirementStatus.FAILED)) {
            return 1;
        }
        if (statuses.contains(RequirementStatus.BLOCKED)
                || runStatus == dev.codewitness.core.result.RunStatus.BLOCKED) {
            return 2;
        }
        // By default exit 0 means "nothing failed", which is not the same as "everything passed": a run
        // in which nothing could be verified is a legitimate result and still exits 0. Callers who need
        // a gate on evidence rather than on failure ask for it explicitly.
        if (failOnUnverified
                && (statuses.isEmpty() || statuses.stream().anyMatch(status -> status != RequirementStatus.VERIFIED))) {
            return 1;
        }
        return 0;
    }
}
