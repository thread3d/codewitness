package dev.codewitness.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.codewitness.core.config.ConfigLoader;
import dev.codewitness.core.config.DurationParser;
import dev.codewitness.core.config.VerificationConfig;
import dev.codewitness.core.diagnostics.SelfIssueCategory;
import dev.codewitness.core.diagnostics.SelfIssueJournal;
import dev.codewitness.core.diagnostics.SelfIssueRecord;
import dev.codewitness.core.diagnostics.SelfIssueSeverity;
import dev.codewitness.core.diagnostics.SelfIssueSummary;
import dev.codewitness.core.engine.VerificationEngine;
import dev.codewitness.core.exec.ExecutionSandbox;
import dev.codewitness.core.exec.LocalProcessSandbox;
import dev.codewitness.core.io.ArtifactStore;
import dev.codewitness.core.io.WorkspaceManager;
import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.RunId;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.requirements.RequirementParser;
import dev.codewitness.core.requirements.RequirementSet;
import dev.codewitness.core.result.Budget;
import dev.codewitness.core.result.BudgetUsage;
import dev.codewitness.core.result.VerificationReport;
import dev.codewitness.core.spi.LanguageAdapter;
import dev.codewitness.core.spi.LanguageDetectorRegistry;
import dev.codewitness.core.spi.ProgressListener;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.report.HtmlReportWriter;
import dev.codewitness.report.JsonReportWriter;
import dev.codewitness.storage.SqliteVerificationStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The tools {@code codewitness mcp} advertises and the code that executes them.
 *
 * <p>Every tool runs the same engine the command line runs. The difference is the return shape: an MCP
 * tool returns text for the model plus, where useful, structured fields for a programmatic client.
 * Progress is written to standard error because standard output is the protocol stream.
 *
 * <p>A verification result is not an MCP error. Exit code {@code 1} ("a requirement failed") and
 * {@code 2} ("the run was blocked") come back as ordinary tool results with the exit code in the text
 * and in {@code structuredContent}. Only bad input and internal faults are {@code isError}.
 */
final class McpTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The MCP result for a tool call. */
    ObjectNode call(String name, JsonNode arguments) {
        return switch (name) {
            case "version" -> version();
            case "inspect" -> inspect(arguments);
            case "test" -> test(arguments);
            case "verify" -> verify(arguments);
            case "report" -> report(arguments);
            case "issues" -> issues(arguments);
            case "reproduce" -> reproduce(arguments);
            default -> error("Unknown tool \"" + name + "\". Available tools: " + toolNames());
        };
    }

    /** @return the {@code tools/list} payload. */
    ArrayNode list() {
        ArrayNode tools = MAPPER.createArrayNode();
        tools.add(tool("version",
                "Report the CodeWitness version, Java runtime and MCP protocol revision.",
                schema()));
        tools.add(tool("inspect",
                "Detect a project's language, build system, modules, sources, tests and capabilities "
                        + "without executing the project.",
                schema(properties(
                        "project", string("Project directory to inspect."),
                        "config", string("Optional codewitness.yaml path.")),
                        "project")));
        tools.add(tool("test",
                "Compile a project and run its existing test suite only. This is the fast path when full "
                        + "verification would take too long.",
                schema(properties(
                        "project", string("Project directory."),
                        "config", string("Optional codewitness.yaml path."),
                        "inPlace", bool("Run in the project directory instead of copying it.")),
                        "project")));
        tools.add(tool("verify",
                "Verify a project against requirements. Returns the verdicts, counterexamples, report "
                        + "paths and an exit code: 0 = nothing failed, 1 = a requirement failed, 2 = the "
                        + "run was blocked, 3 = the tool could not run. Full verification can take minutes.",
                schema(properties(
                        "project", string("Project directory."),
                        "requirements", string("Requirements file (Markdown or YAML). Defaults to "
                                + "<project>/requirements.md when present."),
                        "config", string("Optional codewitness.yaml path."),
                        "output", string("Directory for run data and reports; defaults to "
                                + "<project>/.codewitness."),
                        "maxDuration", string("Overall budget, for example 10m or 30m."),
                        "maxGeneratedTests", integer("Cap on generated tests."),
                        "maxLlmCalls", integer("Cap on language model calls."),
                        "inPlace", bool("Verify the project directory directly instead of copying it."),
                        "noTests", bool("Skip the existing test suite."),
                        "noMutation", bool("Skip mutation testing."),
                        "noGeneratedTests", bool("Skip generated tests."),
                        "noStaticAnalysis", bool("Skip static analysis."),
                        "noSecurity", bool("Skip security analysis."),
                        "llm", bool("Enable advisory language model assistance."),
                        "failOnUnverified", bool("Return exit 1 when any requirement is not VERIFIED.")),
                        "project")));
        tools.add(tool("report",
                "Read a stored verification run, or list recent runs when no run id is supplied.",
                schema(properties(
                        "project", string("Project directory."),
                        "runId", string("Run id, for example CW-2026-10-06-7F31A. Omit to list runs."),
                        "json", bool("Return the canonical JSON report instead of a summary."),
                        "limit", integer("Number of recent runs to list; default 20."),
                        "output", string("Run data directory; defaults to <project>/.codewitness.")),
                        "project")));
        tools.add(tool("issues",
                "List faults CodeWitness recorded in itself, or mark recorded issues resolved.",
                schema(properties(
                        "project", string("Project whose self-issue journal to read."),
                        "all", bool("Include resolved issues."),
                        "json", bool("Return the journal as JSON."),
                        "resolve", arrayOfStrings("Issue id(s) to mark resolved, for example "
                                + "CWI-1A2B3C4D."),
                        "note", string("Note to store with a resolution.")),
                        "project")));
        tools.add(tool("reproduce",
                "Show the command(s) that replay a counterexample recorded in a stored run.",
                schema(properties(
                        "project", string("Project directory."),
                        "runId", string("Run id that produced the counterexample."),
                        "counterexample", string("Optional counterexample id, for example CE-001."),
                        "output", string("Run data directory; defaults to <project>/.codewitness.")),
                        "project", "runId")));
        return tools;
    }

    private ObjectNode version() {
        return text("""
                codewitness %s
                java        %s (%s)
                mcp         %s
                os          %s %s (%s)

                CodeWitness is an evidence engine: it reports what it observed and refuses to mark a
                requirement verified when the evidence does not support it.
                """.formatted(
                VerificationEngine.VERSION,
                System.getProperty("java.version"),
                System.getProperty("java.vendor"),
                McpServer.PROTOCOL_VERSION,
                System.getProperty("os.name"),
                System.getProperty("os.version"),
                System.getProperty("os.arch")));
    }

    private ObjectNode inspect(JsonNode arguments) {
        ProjectRef project = projectRef(arguments);
        SelfIssues.install(project.root());
        ConfigLoader.Loaded loaded = CliSupport.loadConfig(project.root(), optionalPath(arguments, "config"));
        VerificationConfig config = loaded.config();
        CliSupport.Wiring wiring = CliSupport.wiring();

        List<LanguageAdapter> adapters = wiring.languages().selectAll(project, config.language().adapter());
        if (adapters.isEmpty()) {
            LanguageDetectorRegistry detectors = new LanguageDetectorRegistry().discover();
            List<String> detected = detectors.detect(project.root(), Set.of()).stream()
                    .map(LanguageDetectorRegistry.Detection::displayName)
                    .distinct()
                    .toList();
            throw new IllegalArgumentException("No language adapter supports " + project.root()
                    + ". Registered adapters: " + wiring.languages().ids()
                    + (detected.isEmpty() ? "" : ". Detected languages: " + detected));
        }

        Path runDirectory = project.root().resolve(".codewitness/inspect-" + RunId.generate().value());
        ArtifactStore artifacts = new ArtifactStore(runDirectory);
        WorkspaceManager.Workspace workspace = WorkspaceManager.prepare(project, runDirectory,
                WorkspaceManager.Mode.COPY);
        ExecutionSandbox sandbox = new LocalProcessSandbox(List.of(workspace.root(), runDirectory));
        VerificationContext context = context(project, config, workspace, artifacts, sandbox,
                ProjectInfo.builder(project).build(), "inspect");

        StringBuilder out = new StringBuilder("CODEWITNESS inspect\n");
        out.append("project     ").append(project.name()).append(" (").append(project.root()).append(")\n");
        out.append("adapters    ").append(adapters.stream().map(LanguageAdapter::id).toList()).append('\n');
        List<ProjectInfo> infos = new ArrayList<>();
        for (LanguageAdapter adapter : adapters) {
            ProjectInfo info = adapter.inspect(project, context);
            infos.add(info);
            out.append('\n');
            out.append("adapter     ").append(adapter.id()).append('\n');
            out.append("language    ").append(info.language()).append('\n');
            out.append("build       ").append(info.buildSystem()).append('\n');
            out.append("modules     ").append(info.modules().size()).append('\n');
            out.append("tests       ").append(info.testFrameworks().isEmpty()
                    ? "none detected" : String.join(", ", info.testFrameworks())).append('\n');
            out.append("capabilities ").append(adapter.capabilities()).append('\n');
        }
        Set<String> verifiedLanguages = infos.stream()
                .map(info -> info.language().id())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<String> uncovered = new LanguageDetectorRegistry().discover()
                .detect(workspace.root(), infos.stream().map(ProjectInfo::language)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)))
                .stream()
                .map(detection -> detection.displayName() + " (" + detection.evidence() + ")")
                .toList();
        out.append("\nunverified  ").append(uncovered.isEmpty() ? "none" : uncovered).append('\n');
        deleteRecursively(runDirectory);

        ObjectNode structured = MAPPER.createObjectNode();
        structured.put("project", project.root().toString());
        ArrayNode languages = structured.putArray("languages");
        infos.forEach(info -> languages.add(info.language().id()));
        structured.put("adapters", String.join(",", adapters.stream().map(LanguageAdapter::id).toList()));
        structured.put("verifiedLanguages", String.join(",", verifiedLanguages));
        return result(out.toString(), false, structured);
    }

    private ObjectNode test(JsonNode arguments) {
        ProjectRef project = projectRef(arguments);
        SelfIssues.install(project.root());
        ConfigLoader.Loaded loaded = CliSupport.loadConfig(project.root(), optionalPath(arguments, "config"));
        VerificationConfig config = loaded.config();
        CliSupport.Wiring wiring = CliSupport.wiring();
        LanguageAdapter adapter = wiring.languages().select(project, config.language().adapter())
                .orElseThrow(() -> new IllegalArgumentException("No language adapter supports "
                        + project.root() + ". Registered adapters: " + wiring.languages().ids()));

        Path base = CliSupport.runBase(project.root(), optionalPath(arguments, "output"), config);
        Path runDirectory = base.resolve("runs").resolve(RunId.generate().value());
        ArtifactStore artifacts = new ArtifactStore(runDirectory);
        WorkspaceManager.Workspace workspace = WorkspaceManager.prepare(project, runDirectory,
                bool(arguments, "inPlace") ? WorkspaceManager.Mode.IN_PLACE : WorkspaceManager.Mode.COPY);
        ExecutionSandbox sandbox = new LocalProcessSandbox(List.of(workspace.root(), runDirectory));

        VerificationContext context = context(project, config, workspace, artifacts, sandbox,
                ProjectInfo.builder(project).build(), "test");
        ProjectInfo info = adapter.inspect(project, context);
        context = context(project, config, workspace, artifacts, sandbox, info, "test");

        StringBuilder out = new StringBuilder("CODEWITNESS test\n");
        out.append("project     ").append(info.describe()).append('\n');
        BuildResult build = adapter.build(context);
        out.append("build       ").append(build.describe()).append('\n');
        if (!build.success()) {
            out.append(firstLines(build.output(), 20)).append('\n');
            out.append("\nThe build failed, so no tests ran. CodeWitness reports this as BLOCKED, not as a\n")
                    .append("code defect.\n");
            ObjectNode structured = MAPPER.createObjectNode();
            structured.put("exitCode", 2);
            structured.put("buildSuccess", false);
            return result(out.toString(), false, structured);
        }
        TestResult tests = adapter.runTests(context);
        out.append('\n').append(tests.describeWithNote()).append('\n');
        for (var failure : tests.failures()) {
            out.append("  FAILED ").append(failure.displayName()).append(": ")
                    .append(oneLine(failure.failureMessage())).append('\n');
        }
        out.append("\nReports stored in ").append(runDirectory).append('\n');
        int exitCode = tests.anyFailure() ? 1 : 0;
        ObjectNode structured = MAPPER.createObjectNode();
        structured.put("exitCode", exitCode);
        structured.put("buildSuccess", true);
        structured.put("runDirectory", runDirectory.toString());
        return result(out.toString(), false, structured);
    }

    private ObjectNode verify(JsonNode arguments) {
        ProjectRef project = projectRef(arguments);
        SelfIssues.install(project.root());
        SelfIssueJournal journal = SelfIssues.journal();
        Set<String> issuesBefore = journal.openIssueIds();

        ConfigLoader.Loaded loaded = CliSupport.loadConfig(project.root(), optionalPath(arguments, "config"));
        VerificationConfig config = applyOverrides(loaded.config(), arguments);
        RequirementSet requirements = requirements(project.root(), arguments);
        Path base = CliSupport.runBase(project.root(), optionalPath(arguments, "output"), config);
        String command = "codewitness mcp verify " + project.root();
        VerificationEngine engine = CliSupport.engine(
                issue -> journal.record(issue.withCommand(command)));

        Instant startedAt = Instant.now();
        VerificationReport report;
        try {
            report = engine.verify(project, requirements, config, progress(), base,
                    bool(arguments, "inPlace")
                            ? WorkspaceManager.Mode.IN_PLACE : WorkspaceManager.Mode.COPY);
        } catch (RuntimeException e) {
            journal.record(SelfIssueCategory.INTERNAL_ERROR, SelfIssueSeverity.CRITICAL,
                    "Verification pipeline failed: " + e, e,
                    Map.of(SelfIssueRecord.PHASE, "verify",
                            SelfIssueRecord.PROJECT, project.root().toString(),
                            SelfIssueRecord.COMMAND, command,
                            SelfIssueRecord.VERSION, VerificationEngine.VERSION));
            throw new McpToolFailure("Verification failed before it could produce a report: " + e, e, true);
        }

        Path runDirectory = base.resolve("runs").resolve(report.runId());
        JsonReportWriter json = new JsonReportWriter();
        HtmlReportWriter html = new HtmlReportWriter();
        String jsonText = json.render(report);
        Path jsonPath = null;
        Path htmlPath = null;
        StringBuilder warnings = new StringBuilder();
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
            journal.record(SelfIssueCategory.REPORT_FAILURE, SelfIssueSeverity.MEDIUM,
                    "Report artefacts could not be written completely: " + e, e,
                    Map.of(SelfIssueRecord.PHASE, "report",
                            SelfIssueRecord.RUN_ID, report.runId(),
                            SelfIssueRecord.PROJECT, project.root().toString(),
                            SelfIssueRecord.COMMAND, command,
                            SelfIssueRecord.VERSION, VerificationEngine.VERSION));
            warnings.append("warning: report could not be written completely: ").append(e).append('\n');
        }

        int exitCode = VerifyCommand.exitCode(
                report.verdicts().stream().map(verdict -> verdict.status()).toList(),
                report.run().status(), bool(arguments, "failOnUnverified"));
        Set<String> newIssues = new LinkedHashSet<>(journal.openIssueIds());
        newIssues.removeAll(issuesBefore);

        StringBuilder out = new StringBuilder();
        out.append("CodeWitness verify\n");
        out.append("project     ").append(project.name()).append(" (").append(project.root()).append(")\n");
        out.append("run         ").append(report.runId()).append('\n');
        out.append("overall     ").append(report.overallStatus()).append('\n');
        out.append("exit        ").append(exitCode).append('\n');
        out.append("requirements ").append(requirements.size()).append('\n');
        out.append("elapsed     ").append(CliSupport.duration(Duration.between(startedAt, Instant.now())))
                .append('\n');
        if (jsonPath != null) {
            out.append("report.json ").append(jsonPath).append('\n');
        }
        if (htmlPath != null) {
            out.append("report.html ").append(htmlPath).append('\n');
        }
        if (!newIssues.isEmpty()) {
            out.append("self-issues ").append(newIssues).append(" (see ").append(journal.markdownPath())
                    .append(")\n");
        }
        if (warnings.length() > 0) {
            out.append('\n').append(warnings);
        }
        out.append('\n').append(ConsoleReporter.render(report));
        out.append("\nJSON report: ").append(runDirectory.resolve("report.json")).append('\n');

        ObjectNode structured = MAPPER.createObjectNode();
        structured.put("exitCode", exitCode);
        structured.put("runId", report.runId());
        structured.put("status", report.overallStatus().name());
        structured.put("runStatus", report.run().status().name());
        structured.put("requirements", requirements.size());
        if (jsonPath != null) {
            structured.put("reportJson", jsonPath.toString());
        }
        if (htmlPath != null) {
            structured.put("reportHtml", htmlPath.toString());
        }
        if (!newIssues.isEmpty()) {
            ArrayNode issueIds = structured.putArray("selfIssues");
            newIssues.forEach(issueIds::add);
            structured.put("selfIssueJournal", String.valueOf(journal.markdownPath()));
        }
        return result(out.toString(), false, structured);
    }

    private ObjectNode report(JsonNode arguments) {
        ProjectRef project = projectRef(arguments);
        SelfIssues.install(project.root());
        ConfigLoader.Loaded loaded = CliSupport.loadConfig(project.root(), optionalPath(arguments, "config"));
        Path base = CliSupport.runBase(project.root(), optionalPath(arguments, "output"), loaded.config());
        Path database = base.resolve("codewitness.db");
        String runId = optionalText(arguments, "runId");

        if (runId == null || runId.isBlank()) {
            if (!Files.isRegularFile(database)) {
                return text("No verification history found at " + database);
            }
            int limit = integerValue(arguments, "limit", 20);
            try (SqliteVerificationStore store = new SqliteVerificationStore(database)) {
                var runs = store.listRuns(Math.max(1, Math.min(limit, 1000)));
                if (runs.isEmpty()) {
                    return text("No runs recorded in " + database);
                }
                StringBuilder out = new StringBuilder();
                out.append(String.format(Locale.ROOT, "%-26s %-22s %-20s %-20s %s%n",
                        "RUN ID", "PROJECT", "RUN STATUS", "REQUIREMENTS", "STARTED"));
                for (var run : runs) {
                    out.append(String.format(Locale.ROOT, "%-26s %-22s %-20s %-20s %s%n",
                            run.runId(), run.projectName(), run.status(), run.overallStatus(),
                            run.startedAt()));
                }
                return text(out.toString());
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("cannot read " + database + ": " + e.getMessage(), e);
            }
        }

        Path runDirectory = base.resolve("runs").resolve(runId);
        String json = null;
        Path jsonFile = runDirectory.resolve("report.json");
        if (Files.isRegularFile(jsonFile)) {
            try {
                json = Files.readString(jsonFile, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + jsonFile, e);
            }
        }
        if (json == null && Files.isRegularFile(database)) {
            try (SqliteVerificationStore store = new SqliteVerificationStore(database)) {
                json = store.findReportJson(runId).orElse(null);
            }
        }
        if (json == null) {
            throw new IllegalArgumentException("no stored report for run " + runId + " (looked in "
                    + runDirectory + " and " + database + ")");
        }
        if (bool(arguments, "json")) {
            return text(json);
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            StringBuilder out = new StringBuilder();
            out.append("Stored report ").append(runDirectory.resolve("report.json")).append('\n');
            out.append("runId        ").append(node.path("runId").asText(runId)).append('\n');
            out.append("status       ").append(node.path("status").asText("unknown")).append('\n');
            JsonNode verdicts = node.path("requirements");
            if (verdicts.isArray()) {
                out.append("requirements ").append(verdicts.size()).append('\n');
                for (JsonNode verdict : verdicts) {
                    out.append("  ").append(verdict.path("id").asText("?"))
                            .append("  ").append(verdict.path("status").asText("?")).append('\n');
                }
            }
            out.append('\n');
            out.append("HTML: ").append(runDirectory.resolve("report.html")).append('\n');
            out.append("JSON: ").append(runDirectory.resolve("report.json")).append('\n');
            ObjectNode structured = MAPPER.createObjectNode();
            structured.put("runId", node.path("runId").asText(runId));
            structured.put("status", node.path("status").asText("unknown"));
            return result(out.toString(), false, structured);
        } catch (IOException e) {
            throw new IllegalArgumentException("stored report is not valid JSON: " + e.getMessage(), e);
        }
    }

    private ObjectNode issues(JsonNode arguments) {
        ProjectRef project = projectRef(arguments);
        SelfIssues.install(project.root());
        SelfIssueJournal journal = SelfIssueJournal.open(
                SelfIssueJournal.defaultDirectory(project.root()));
        boolean includeResolved = bool(arguments, "all");
        boolean json = bool(arguments, "json");
        String note = optionalText(arguments, "note");

        List<String> ids = stringList(arguments.path("resolve"));
        List<String> problems = new ArrayList<>();
        for (String id : ids) {
            if (!journal.resolve(id, note)) {
                problems.add("no recorded self-issue with id " + id);
            }
        }
        if (json) {
            ObjectNode result = text(journal.renderJson(includeResolved));
            if (!problems.isEmpty()) {
                result.put("isError", true);
            }
            return result;
        }

        StringBuilder out = new StringBuilder();
        out.append("CodeWitness self-issue log\n");
        out.append("  journal    ").append(journal.markdownPath()).append('\n');
        out.append("  records    ").append(journal.jsonlPath()).append('\n');
        out.append("  open       ").append(journal.openIssueCount()).append('\n');
        out.append("  resolved   ").append(journal.resolvedIssueCount()).append('\n');
        out.append('\n');
        List<SelfIssueSummary> summaries = journal.summaries(includeResolved);
        if (summaries.isEmpty()) {
            out.append(includeResolved
                    ? "No self-issues have been recorded here."
                    : "No open self-issues. Use all=true to include resolved ones.").append('\n');
        }
        for (SelfIssueSummary issue : summaries) {
            out.append(String.format(Locale.ROOT, "%-14s %-8s %-17s %2dx  last %s%s%n",
                    issue.id(), issue.severity(), issue.category(), issue.occurrences(),
                    issue.lastSeen(), issue.resolved() ? "  [resolved]" : ""));
            out.append("  ").append(oneLine(issue.message())).append('\n');
            if (issue.source() != null) {
                out.append("  where   ").append(issue.source()).append('\n');
            }
            if (issue.command() != null) {
                out.append("  command ").append(issue.command()).append('\n');
            }
            out.append('\n');
        }
        for (String problem : problems) {
            out.append("error: ").append(problem).append('\n');
        }
        out.append("Full digest: ").append(journal.markdownPath()).append('\n');
        ObjectNode structured = MAPPER.createObjectNode();
        structured.put("journal", String.valueOf(journal.markdownPath()));
        structured.put("open", journal.openIssueCount());
        structured.put("resolved", journal.resolvedIssueCount());
        return result(out.toString(), !problems.isEmpty(), structured);
    }

    private ObjectNode reproduce(JsonNode arguments) {
        ProjectRef project = projectRef(arguments);
        SelfIssues.install(project.root());
        ConfigLoader.Loaded loaded = CliSupport.loadConfig(project.root(), optionalPath(arguments, "config"));
        Path base = CliSupport.runBase(project.root(), optionalPath(arguments, "output"), loaded.config());
        String runId = requiredText(arguments, "runId");
        String counterexample = optionalText(arguments, "counterexample");
        Path runDirectory = base.resolve("runs").resolve(runId);
        String json = null;
        if (Files.isRegularFile(runDirectory.resolve("report.json"))) {
            try {
                json = Files.readString(runDirectory.resolve("report.json"), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + runDirectory.resolve("report.json"), e);
            }
        }
        if (json == null && Files.isRegularFile(base.resolve("codewitness.db"))) {
            try (SqliteVerificationStore store = new SqliteVerificationStore(
                    base.resolve("codewitness.db"))) {
                json = store.findReportJson(runId).orElse(null);
            }
        }
        if (json == null) {
            throw new IllegalArgumentException("no stored report for run " + runId);
        }
        List<String> commands = ReproduceCommand.extractReproductionCommands(json, counterexample);
        StringBuilder out = new StringBuilder();
        if (commands.isEmpty()) {
            out.append(counterexample == null
                    ? "This run recorded no counterexamples, so there is nothing to reproduce."
                    : "No counterexample " + counterexample + " was recorded in run " + runId + ".");
            out.append('\n');
        } else {
            out.append("Reproduction command(s) for ").append(runId)
                    .append(counterexample == null ? "" : " / " + counterexample).append(":\n");
            for (String command : commands) {
                out.append("  ").append(command).append('\n');
            }
            out.append("\nRun them from the project directory.\n");
        }
        ObjectNode structured = MAPPER.createObjectNode();
        structured.put("runId", runId);
        if (counterexample != null) {
            structured.put("counterexample", counterexample);
        }
        ArrayNode array = structured.putArray("commands");
        commands.forEach(array::add);
        return result(out.toString(), false, structured);
    }

    private static VerificationContext context(
            ProjectRef project,
            VerificationConfig config,
            WorkspaceManager.Workspace workspace,
            ArtifactStore artifacts,
            ExecutionSandbox sandbox,
            ProjectInfo info,
            String runId) {
        return VerificationContext.builder()
                .runId(runId)
                .projectRef(project)
                .projectInfo(info)
                .config(config)
                .workspace(workspace)
                .artifacts(artifacts)
                .budget(new BudgetUsage.Tracker(config.toBudget()))
                .requirements(RequirementSet.empty())
                .sandbox(sandbox)
                .evidenceLedger(new dev.codewitness.core.evidence.EvidenceLedger(runId))
                .progress(progress())
                .build();
    }

    private static ProgressListener progress() {
        return (phase, message) -> System.err.println("[codewitness mcp] " + phase + ": " + message);
    }

    private static RequirementSet requirements(Path projectRoot, JsonNode arguments) {
        Path file = optionalPath(arguments, "requirements");
        if (file != null) {
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("requirements file not found: " + file);
            }
            return RequirementParser.parseFile(file);
        }
        Path conventional = projectRoot.resolve("requirements.md");
        return Files.isRegularFile(conventional)
                ? RequirementParser.parseFile(conventional)
                : RequirementSet.empty();
    }

    private static VerificationConfig applyOverrides(VerificationConfig config, JsonNode arguments) {
        VerificationConfig result = config;
        if (arguments.hasNonNull("maxDuration") || arguments.hasNonNull("maxGeneratedTests")
                || arguments.hasNonNull("maxLlmCalls")) {
            Budget budget = config.toBudget();
            String maxDuration = optionalText(arguments, "maxDuration");
            if (maxDuration != null) {
                budget = budget.withMaxDuration(DurationParser.parse(maxDuration, Duration.ofMinutes(15)));
            }
            Integer maxGeneratedTests = optionalInteger(arguments, "maxGeneratedTests");
            if (maxGeneratedTests != null) {
                budget = budget.withMaxGeneratedTests(maxGeneratedTests);
            }
            Integer maxLlmCalls = optionalInteger(arguments, "maxLlmCalls");
            if (maxLlmCalls != null) {
                budget = budget.withMaxLlmCalls(maxLlmCalls);
            }
            result = result.withBudget(budget);
        }
        VerificationConfig.Toggle tests = bool(arguments, "noTests")
                ? VerificationConfig.Toggle.off() : result.tests();
        VerificationConfig.Mutation mutation = bool(arguments, "noMutation")
                ? new VerificationConfig.Mutation(false, result.mutation().tool(),
                        result.mutation().timeout(), result.mutation().targetClasses())
                : result.mutation();
        VerificationConfig.PropertyTesting propertyTesting = bool(arguments, "noGeneratedTests")
                ? new VerificationConfig.PropertyTesting(false, result.propertyTesting().maxScenarios(),
                        result.propertyTesting().concurrentScenarios())
                : result.propertyTesting();
        VerificationConfig.StaticAnalysis staticAnalysis = bool(arguments, "noStaticAnalysis")
                ? new VerificationConfig.StaticAnalysis(false, result.staticAnalysis().analyzers(),
                        result.staticAnalysis().lintAll())
                : result.staticAnalysis();
        VerificationConfig.Security security = bool(arguments, "noSecurity")
                ? new VerificationConfig.Security(false) : result.security();
        VerificationConfig.Llm llm = bool(arguments, "llm")
                ? new VerificationConfig.Llm(true, result.llm().provider(), result.llm().model(),
                        result.llm().baseUrl(), result.llm().apiKeyEnvironmentVariable(),
                        Math.max(1, result.llm().maxCalls()), result.llm().temperature(),
                        result.llm().timeout())
                : result.llm();
        return new VerificationConfig(result.project(), result.language(), result.limits(), tests,
                mutation, propertyTesting, staticAnalysis, security, llm, result.report(),
                result.sandbox(), result.scenarios(), result.database(), result.findings());
    }

    private static ProjectRef projectRef(JsonNode arguments) {
        String value = requiredText(arguments, "project");
        Path root = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("not a directory: " + root);
        }
        return ProjectRef.of(root);
    }

    private static String requiredText(JsonNode arguments, String key) {
        String value = optionalText(arguments, key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required argument \"" + key + "\"");
        }
        return value;
    }

    private static String optionalText(JsonNode arguments, String key) {
        JsonNode value = arguments.get(key);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Path optionalPath(JsonNode arguments, String key) {
        String value = optionalText(arguments, key);
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    private static boolean bool(JsonNode arguments, String key) {
        JsonNode value = arguments.get(key);
        return value != null && !value.isNull() && value.asBoolean(false);
    }

    private static Integer optionalInteger(JsonNode arguments, String key) {
        JsonNode value = arguments.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            throw new IllegalArgumentException("\"" + key + "\" must be an integer");
        }
        return value.asInt();
    }

    private static int integerValue(JsonNode arguments, String key, int fallback) {
        Integer value = optionalInteger(arguments, key);
        return value == null ? fallback : value;
    }

    private static List<String> stringList(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return List.of();
        }
        if (value.isTextual()) {
            List<String> values = new ArrayList<>();
            for (String part : value.asText().split(",")) {
                if (!part.isBlank()) {
                    values.add(part.trim());
                }
            }
            return values;
        }
        if (value.isArray()) {
            List<String> values = new ArrayList<>();
            value.forEach(item -> {
                if (item.isTextual() && !item.asText().isBlank()) {
                    values.add(item.asText().trim());
                }
            });
            return values;
        }
        throw new IllegalArgumentException("\"resolve\" must be a string or an array of strings");
    }

    private static ObjectNode schema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", MAPPER.createObjectNode());
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode schema(ObjectNode properties, String... required) {
        ObjectNode schema = schema();
        schema.set("properties", properties);
        if (required.length > 0) {
            ArrayNode requiredNode = schema.putArray("required");
            for (String name : required) {
                requiredNode.add(name);
            }
        }
        return schema;
    }

    private static ObjectNode properties(Object... pairs) {
        ObjectNode properties = MAPPER.createObjectNode();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            properties.set((String) pairs[i], (JsonNode) pairs[i + 1]);
        }
        return properties;
    }

    private static ObjectNode string(String description) {
        return property("string", description);
    }

    private static ObjectNode bool(String description) {
        return property("boolean", description);
    }

    private static ObjectNode integer(String description) {
        return property("integer", description);
    }

    private static ObjectNode arrayOfStrings(String description) {
        ObjectNode property = property("array", description);
        property.putObject("items").put("type", "string");
        return property;
    }

    private static ObjectNode property(String type, String description) {
        ObjectNode property = MAPPER.createObjectNode();
        property.put("type", type);
        property.put("description", description);
        return property;
    }

    private static ObjectNode tool(String name, String description, ObjectNode inputSchema) {
        ObjectNode tool = MAPPER.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        tool.set("inputSchema", inputSchema);
        return tool;
    }

    private static String toolNames() {
        return "version, inspect, test, verify, report, issues, reproduce";
    }

    static ObjectNode text(String text) {
        return result(text, false, null);
    }

    static ObjectNode error(String text) {
        return result(text, true, null);
    }

    private static ObjectNode result(String text, boolean isError, ObjectNode structured) {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode item = content.addObject();
        item.put("type", "text");
        item.put("text", text == null ? "" : text);
        result.put("isError", isError);
        if (structured != null) {
            result.set("structuredContent", structured);
        }
        return result;
    }

    private static String firstLines(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.lines().limit(limit).reduce("", (left, right) -> left + right + "\n");
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static void deleteRecursively(Path directory) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var walk = Files.walk(directory)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (Exception _) {
            // inspection already produced its answer; failing to tidy up must not fail the call
        }
    }

    /** An internal tool failure that the server should report without recording it a second time. */
    static final class McpToolFailure extends RuntimeException {
        private final boolean recorded;

        McpToolFailure(String message, Throwable cause, boolean recorded) {
            super(message, cause);
            this.recorded = recorded;
        }

        boolean recorded() {
            return recorded;
        }
    }
}
