package dev.codewitness.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.codewitness.core.evidence.Evidence;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.AdvisoryScan;
import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.DependencyVulnerability;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.ModuleInfo;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.PackageCoordinate;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.model.SurvivingMutant;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.RequirementStatus;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.result.Budget;
import dev.codewitness.core.result.BudgetUsage;
import dev.codewitness.core.result.Counterexample;
import dev.codewitness.core.result.EnvironmentSnapshot;
import dev.codewitness.core.result.ObligationOutcome;
import dev.codewitness.core.result.PhaseRecord;
import dev.codewitness.core.result.RequirementVerdict;
import dev.codewitness.core.result.VerificationReport;
import dev.codewitness.core.result.VerificationRun;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Writes the canonical, machine-readable CodeWitness report as JSON.
 *
 * <p>The produced document is the authoritative representation of a verification run: every human
 * oriented view (HTML, CLI output) is derived from the same data. Field order is insertion order and
 * is part of the contract - consumers may diff two reports textually.
 *
 * <p>The writer never invents data. When a section could not be measured the corresponding
 * {@code measured}/{@code executed}/{@code analysed} flag is {@code false}, the {@code note} explains
 * why, and the numeric values stay at zero so that a truncated run cannot be mistaken for a
 * completed one.
 *
 * <p>This class is stateless apart from its {@link ObjectMapper} and safe to reuse.
 */
public final class JsonReportWriter {

    private final ObjectMapper mapper;

    /** Creates a writer with a private, pretty-printing {@link ObjectMapper}. */
    public JsonReportWriter() {
        this(new ObjectMapper());
    }

    /**
     * Creates a writer that uses the supplied mapper.
     *
     * @param mapper mapper to serialise with; {@code INDENT_OUTPUT} is enabled on a copy-safe basis
     *               by the caller, this constructor enables it on the supplied instance
     */
    public JsonReportWriter(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Renders the canonical JSON report.
     *
     * @param report report to render
     * @return pretty printed JSON document, never {@code null}
     */
    public String render(VerificationReport report) {
        Objects.requireNonNull(report, "report");
        return toJson(reportNode(report));
    }

    /**
     * Renders the report and writes it to {@code target} as UTF-8, creating parent directories.
     *
     * @param report report to render
     * @param target destination file
     * @return {@code target}, for chaining
     * @throws UncheckedIOException when the file cannot be written
     */
    public Path write(VerificationReport report, Path target) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(target, "target");
        try {
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, render(report), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write JSON report to " + target, e);
        }
        return target;
    }

    /**
     * Renders the compact summary used by console output.
     *
     * <p>The result is a single line JSON object containing the run id, the overall status and the
     * requirement count per status. It is deliberately not pretty printed.
     *
     * @param report report to summarise
     * @return compact JSON object, never {@code null}
     */
    public static String renderSummary(VerificationReport report) {
        Objects.requireNonNull(report, "report");
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode summary = mapper.createObjectNode();
        summary.put("runId", report.runId());
        summary.put("status", report.overallStatus().name());
        Map<RequirementStatus, Long> counts = report.verdictCounts();
        summary.put("verified", count(counts, RequirementStatus.VERIFIED));
        summary.put("partiallyVerified", count(counts, RequirementStatus.PARTIALLY_VERIFIED));
        summary.put("failed", count(counts, RequirementStatus.FAILED));
        summary.put("unverified", count(counts, RequirementStatus.UNVERIFIED));
        summary.put("blocked", count(counts, RequirementStatus.BLOCKED));
        try {
            return mapper.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(summary);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to render report summary", e);
        }
    }

    private static long count(Map<RequirementStatus, Long> counts, RequirementStatus status) {
        return counts.getOrDefault(status, 0L);
    }

    private String toJson(JsonNode node) {
        try {
            return mapper.writer().writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to render JSON report", e);
        }
    }

    // ------------------------------------------------------------------ document

    private ObjectNode reportNode(VerificationReport report) {
        ObjectNode root = mapper.createObjectNode();
        root.put("runId", report.runId());
        root.set("project", projectNode(report));
        root.put("status", report.overallStatus().name());
        // The pipeline's own outcome, kept separate from the verdicts: a run can be BLOCKED while every
        // requirement it reached is VERIFIED, and a machine consumer has to be able to tell them apart.
        root.put("runStatus", report.run() != null && report.run().status() != null
                ? report.run().status().name() : null);
        root.put("statusExplanation", statusExplanation(report));
        root.put("generatedAt", generatedAt(report).toString());
        root.set("build", buildNode(report));
        root.set("requirements", requirementsNode(report));
        root.set("counterexamples", counterexamplesNode(report.counterexamples()));
        root.set("evidence", evidenceNode(report.evidence()));
        root.set("tests", testsNode(report.tests()));
        root.set("mutation", mutationNode(report.mutation()));
        root.set("coverage", coverageNode(report));
        root.set("findings", findingsNode(report.findings()));
        root.set("security", securityNode(report.findings()));
        root.set("dependencies", dependenciesNode(report.dependencies()));
        root.set("environment", environmentNode(report.run()));
        root.set("phases", phasesNode(report.run()));
        root.set("budget", budgetNode(report.run()));
        root.set("limitations", strings(report.limitations()));
        return root;
    }

    private String statusExplanation(VerificationReport report) {
        String explanation = report.describe();
        VerificationRun run = report.run();
        if (run != null && run.status() != null) {
            explanation = explanation + " (run status: " + run.status().name() + ")";
        }
        return explanation;
    }

    private static Instant generatedAt(VerificationReport report) {
        VerificationRun run = report.run();
        if (run != null) {
            EnvironmentSnapshot environment = run.environment();
            if (environment != null && environment.finishedAt() != null) {
                return environment.finishedAt();
            }
            if (run.finishedAt() != null) {
                return run.finishedAt();
            }
            if (run.startedAt() != null) {
                return run.startedAt();
            }
        }
        return Instant.now();
    }

    /**
     * Renders the build phase, which the JSON report otherwise only exposes as an evidence tail.
     *
     * <p>{@link VerificationReport#build()} carries the command, the exit status and the compiler's error
     * and warning counts; a report without them cannot distinguish a clean compile from one that emitted
     * thousands of warnings, and cannot show why a run was {@code BLOCKED}.
     *
     * @param report report being rendered
     * @return build node, never {@code null}
     */
    private ObjectNode buildNode(VerificationReport report) {
        ObjectNode node = mapper.createObjectNode();
        VerificationReport.BuildSummary build = report.build();
        if (build == null) {
            node.put("attempted", false);
            node.put("success", false);
            node.put("note", "no build summary was recorded");
            node.set("command", mapper.createArrayNode());
            return node;
        }
        node.put("attempted", build.attempted());
        node.put("success", build.success());
        node.set("command", strings(build.command()));
        node.put("durationMillis", build.durationMillis());
        node.put("errors", build.errorCount());
        node.put("warnings", build.warningCount());
        node.put("note", build.note());
        return node;
    }

    private ObjectNode projectNode(VerificationReport report) {
        ProjectInfo project = report.project();
        ObjectNode node = mapper.createObjectNode();
        if (project == null) {
            node.putNull("name");
            node.putNull("path");
            node.putNull("identifier");
            node.putNull("language");
            node.putNull("buildSystem");
            node.set("modules", mapper.createArrayNode());
            node.putNull("javaVersion");
            node.set("languages", mapper.createArrayNode());
            return node;
        }
        node.put("name", project.project().name());
        node.put("path", project.project().root().toString());
        node.put("identifier", project.project().identifier());
        node.put("language", project.language().toString());
        node.put("buildSystem", project.buildSystem().toString());
        ArrayNode modules = mapper.createArrayNode();
        for (ModuleInfo module : project.modules()) {
            modules.add(module.name());
        }
        node.set("modules", modules);
        node.put("javaVersion", project.javaVersion().orElse(null));
        // Which languages were actually verified. A report about a polyglot project cannot be read without
        // it: "language": "polyglot" says the verdicts span more than one language but not which ones.
        ArrayNode languages = mapper.createArrayNode();
        for (VerificationReport.LanguageSummary language : report.languages()) {
            ObjectNode entry = mapper.createObjectNode();
            entry.put("language", language.language().id());
            // A language the run only recognised must be distinguishable from one it verified, or "this
            // project's languages" would read as a list of things that were checked.
            entry.put("verified", language.verified());
            entry.put("adapter", language.adapter());
            entry.put("evidence", language.evidence());
            entry.put("buildSystem", language.buildSystem().id());
            entry.put("modules", language.modules());
            entry.put("buildAttempted", language.buildAttempted());
            entry.put("buildSucceeded", language.buildSucceeded());
            languages.add(entry);
        }
        node.set("languages", languages);
        return node;
    }

    // ------------------------------------------------------------------ requirements

    private ArrayNode requirementsNode(VerificationReport report) {
        Map<String, RequirementVerdict> verdictsById = new LinkedHashMap<>();
        for (RequirementVerdict verdict : report.verdicts()) {
            RequirementVerdict previous = verdictsById.get(verdict.requirementId());
            // Two verdicts for one requirement id used to collapse by last-write-wins, which could drop a
            // FAILED verdict behind a later VERIFIED one. The worse verdict survives: a report may not
            // quietly discard the least favourable thing it knows.
            verdictsById.put(verdict.requirementId(),
                    previous == null || StatusSeverity.moreSevere(previous, verdict) == verdict
                            ? verdict : previous);
        }
        ArrayNode array = mapper.createArrayNode();
        for (Requirement requirement : report.requirements().requirements()) {
            array.add(requirementNode(requirement, verdictsById.remove(requirement.id())));
        }
        // Verdicts without a matching requirement would otherwise be silently dropped; they are
        // reported with the verdict id as their title so a report never loses information.
        for (RequirementVerdict verdict : verdictsById.values()) {
            array.add(requirementNode(null, verdict));
        }
        return array;
    }

    private ObjectNode requirementNode(Requirement requirement, RequirementVerdict verdict) {
        ObjectNode node = mapper.createObjectNode();
        String id = requirement != null ? requirement.id() : verdict.requirementId();
        node.put("id", id);
        node.put("title", requirement != null ? requirement.title() : id);
        node.put("description", requirement != null ? requirement.description() : "");
        RequirementStatus status = verdict != null ? verdict.status() : RequirementStatus.UNVERIFIED;
        node.put("status", status != null ? status.name() : RequirementStatus.UNVERIFIED.name());
        node.put("rationale", verdict != null && verdict.rationale() != null
                ? verdict.rationale() : "no verdict recorded for this requirement");
        node.set("evidence", strings(verdict != null ? verdict.evidenceIds() : List.of()));
        node.set("obligations", obligationsNode(requirement, verdict));
        node.set("unknownAspects", strings(verdict != null ? verdict.unknownAspects() : List.of()));
        node.set("counterexamples", strings(verdict != null ? verdict.counterexampleIds() : List.of()));
        return node;
    }

    private ArrayNode obligationsNode(Requirement requirement, RequirementVerdict verdict) {
        Map<String, ObligationOutcome> outcomesById = new LinkedHashMap<>();
        if (verdict != null) {
            for (ObligationOutcome outcome : verdict.obligations()) {
                outcomesById.put(outcome.obligationId(), outcome);
            }
        }
        ArrayNode array = mapper.createArrayNode();
        if (requirement == null) {
            for (ObligationOutcome outcome : outcomesById.values()) {
                array.add(obligationNode(outcome.obligationId(), null, outcome.status(), outcome.rationale(),
                        outcome.evidenceIds(), outcome.attempts()));
            }
            return array;
        }
        for (VerificationObligation obligation : requirement.obligations()) {
            ObligationOutcome outcome = outcomesById.get(obligation.id());
            array.add(obligationNode(
                    obligation.id(),
                    obligation,
                    outcome != null ? outcome.status() : RequirementStatus.UNVERIFIED,
                    outcome != null ? outcome.rationale() : "not attempted",
                    outcome != null ? outcome.evidenceIds() : List.of(),
                    outcome != null ? outcome.attempts() : 0));
        }
        return array;
    }

    private ObjectNode obligationNode(String id, VerificationObligation obligation, RequirementStatus status,
                                      String rationale, List<String> evidenceIds, int attempts) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", id);
        node.put("description", obligation != null ? obligation.description() : "");
        node.put("kind", obligation != null && obligation.kind() != null ? obligation.kind().name() : null);
        node.put("origin", obligation != null && obligation.origin() != null ? obligation.origin().name() : null);
        node.put("status", status != null ? status.name() : RequirementStatus.UNVERIFIED.name());
        node.put("rationale", rationale);
        node.set("evidence", strings(evidenceIds));
        node.put("attempts", attempts);
        return node;
    }

    // ------------------------------------------------------------------ counterexamples

    private ArrayNode counterexamplesNode(List<Counterexample> counterexamples) {
        ArrayNode array = mapper.createArrayNode();
        for (Counterexample counterexample : counterexamples) {
            ObjectNode node = mapper.createObjectNode();
            node.put("id", counterexample.id());
            node.put("requirementId", counterexample.requirementId());
            node.put("obligationId", counterexample.obligationId());
            node.put("summary", counterexample.summary());
            node.set("inputs", valueNode(counterexample.inputs()));
            node.put("expected", counterexample.expected());
            node.put("actual", counterexample.actual());
            node.put("invariantViolated", counterexample.invariantViolated());
            node.put("observedBy", counterexample.observedBy());
            node.put("evidenceId", counterexample.evidenceId());
            if (counterexample.seed() == null) {
                node.putNull("seed");
            } else {
                node.put("seed", counterexample.seed());
            }
            node.set("reproduction", reproductionNode(counterexample.reproduction()));
            node.put("discoveredBy", counterexample.discoveredBy() != null
                    ? counterexample.discoveredBy().name() : null);
            array.add(node);
        }
        return array;
    }

    private ObjectNode reproductionNode(Counterexample.ReproductionRecipe recipe) {
        ObjectNode node = mapper.createObjectNode();
        if (recipe == null) {
            node.putNull("description");
            node.set("command", mapper.createArrayNode());
            node.putNull("testArtifact");
            node.set("properties", mapper.createObjectNode());
            return node;
        }
        node.put("description", recipe.description());
        node.set("command", strings(recipe.command()));
        node.put("testArtifact", recipe.testArtifact() == null ? null : recipe.testArtifact().toString());
        node.set("properties", valueNode(recipe.properties()));
        return node;
    }

    // ------------------------------------------------------------------ evidence

    private ArrayNode evidenceNode(List<Evidence> evidence) {
        ArrayNode array = mapper.createArrayNode();
        for (Evidence item : evidence) {
            ObjectNode node = mapper.createObjectNode();
            node.put("id", item.id());
            node.put("type", item.type() != null ? item.type().name() : null);
            node.put("status", item.status() != null ? item.status().name() : null);
            node.put("confidence", item.confidence() != null ? item.confidence().name() : null);
            node.put("description", item.description());
            node.put("source", item.source());
            node.put("timestamp", item.timestamp() != null ? item.timestamp().toString() : null);
            node.set("command", strings(item.command()));
            node.set("inputs", valueNode(item.inputs()));
            node.put("output", item.output());
            node.put("result", item.result());
            node.set("metadata", valueNode(item.metadata()));
            node.set("artifacts", strings(item.artifacts().stream().map(Path::toString).toList()));
            node.set("requirements", strings(item.requirementIds()));
            node.set("obligations", strings(item.obligationIds()));
            node.put("generatedByLlm", item.generatedByLlm());
            node.put("executedIndependently", item.executedIndependently());
            node.put("reproducible", item.reproducible());
            array.add(node);
        }
        return array;
    }

    // ------------------------------------------------------------------ tests

    private ObjectNode testsNode(TestResult tests) {
        ObjectNode node = mapper.createObjectNode();
        node.put("executed", tests.executed());
        node.put("note", tests.note());
        node.put("total", tests.total());
        node.put("passed", tests.passed());
        node.put("failed", tests.failed());
        node.put("errors", tests.errors());
        node.put("skipped", tests.skipped());
        node.put("durationMillis", tests.duration().toMillis());
        ArrayNode failures = mapper.createArrayNode();
        for (TestCaseResult failure : tests.failures()) {
            ObjectNode entry = mapper.createObjectNode();
            entry.put("suite", failure.suite());
            entry.put("name", failure.name());
            entry.put("failureType", failure.failureType());
            entry.put("failureMessage", failure.failureMessage());
            failures.add(entry);
        }
        node.set("failures", failures);
        return node;
    }

    // ------------------------------------------------------------------ mutation / coverage

    private ObjectNode mutationNode(MutationReport mutation) {
        ObjectNode node = mapper.createObjectNode();
        node.put("measured", mutation.measured());
        node.put("tool", mutation.tool());
        node.put("note", mutation.note());
        node.put("generated", mutation.generated());
        node.put("killed", mutation.killed());
        node.put("survived", mutation.survived());
        node.put("noCoverage", mutation.noCoverage());
        node.put("timedOut", mutation.timedOut());
        node.put("scorePercent", mutation.scorePercent());
        ArrayNode survivors = mapper.createArrayNode();
        for (SurvivingMutant mutant : mutation.survivors()) {
            ObjectNode entry = mapper.createObjectNode();
            entry.put("id", mutant.id());
            entry.put("status", mutant.status() != null ? mutant.status().name() : null);
            entry.put("mutator", mutant.mutator());
            entry.put("className", mutant.className());
            entry.put("method", mutant.method());
            entry.put("line", mutant.line());
            entry.put("description", mutant.description());
            survivors.add(entry);
        }
        node.set("survivors", survivors);
        return node;
    }

    private ObjectNode coverageNode(VerificationReport report) {
        CoverageReport coverage = report.coverage();
        ObjectNode node = mapper.createObjectNode();
        node.put("measured", coverage.measured());
        node.put("tool", coverage.tool());
        node.put("note", coverage.note());
        node.put("lineCoveragePercent", coverage.lineCoveragePercent());
        node.put("branchCoveragePercent", coverage.branchCoveragePercent());
        node.put("lineCovered", coverage.lineCovered());
        node.put("lineTotal", coverage.lineTotal());
        node.put("branchCovered", coverage.branchCovered());
        node.put("branchTotal", coverage.branchTotal());
        // Per-file coverage is computed and stored by several adapters; without this field the work only
        // ever reached SQLite. It is emitted only when there is something to emit, and the keys are
        // sorted so two reports of the same run diff textually like the rest of the document.
        if (!coverage.perClassLineCoverage().isEmpty()) {
            node.set("perClassLineCoverage",
                    valueNode(perFileCoverage(report, coverage.perClassLineCoverage())));
        }
        return node;
    }

    /**
     * @return the per-file coverage map keyed by the path the report should display: sorted, and
     *     relative to the project root when the recorded key is an absolute path below it
     */
    private static Map<String, Double> perFileCoverage(VerificationReport report, Map<String, Double> perClass) {
        Map<String, Double> sorted = new TreeMap<>();
        for (Map.Entry<String, Double> entry : perClass.entrySet()) {
            String key = displayPath(report, entry.getKey());
            if (sorted.containsKey(key)) {
                // Never drop an entry to a relative-path collision: keep the key exactly as recorded.
                key = entry.getKey();
            }
            sorted.put(key, entry.getValue());
        }
        return sorted;
    }

    /**
     * Renders a path relative to the project root, for keys that are absolute sandbox paths.
     *
     * <p>This is a pure string operation on a path the report already holds: no symlink resolution, no
     * existence check and no guessing, so it can only shorten a path that is genuinely below the root.
     * Anything else is returned unchanged rather than invented.
     */
    private static String displayPath(VerificationReport report, String path) {
        if (path == null || path.isBlank() || report.project() == null) {
            return path;
        }
        try {
            Path candidate = Path.of(path);
            Path root = report.project().project().root();
            if (root == null) {
                return path;
            }
            Path absoluteRoot = root.toAbsolutePath().normalize();
            if (candidate.isAbsolute() && candidate.normalize().startsWith(absoluteRoot)) {
                return absoluteRoot.relativize(candidate.normalize()).toString().replace('\\', '/');
            }
        } catch (RuntimeException _) {
            // not a usable path: keep the original text
        }
        return path;
    }

    // ------------------------------------------------------------------ findings / security

    private ArrayNode findingsNode(List<Finding> findings) {
        ArrayNode array = mapper.createArrayNode();
        for (Finding finding : findings) {
            ObjectNode node = mapper.createObjectNode();
            node.put("severity", finding.severity().name());
            node.put("category", finding.category().name());
            node.put("file", finding.file());
            if (finding.line() == null) {
                node.putNull("line");
            } else {
                node.put("line", finding.line());
            }
            node.put("message", finding.message());
            // The fingerprint makes a report usable as the next run's baseline: identity is what a
            // comparison needs, and computing it at read time from five fields is how the two sides drift.
            node.put("fingerprint", finding.fingerprint());
            node.put("analyzer", finding.analyzer());
            node.put("rule", finding.ruleId());
            array.add(node);
        }
        return array;
    }

    private ObjectNode securityNode(List<Finding> findings) {
        Map<Severity, Integer> counts = new LinkedHashMap<>();
        for (Severity severity : Severity.values()) {
            counts.put(severity, 0);
        }
        for (Finding finding : findings) {
            if (finding.category() == FindingCategory.SECURITY) {
                counts.merge(finding.severity(), 1, Integer::sum);
            }
        }
        ObjectNode node = mapper.createObjectNode();
        int total = 0;
        for (Severity severity : Severity.values()) {
            int value = counts.get(severity);
            total += value;
            node.put(severity.name().toLowerCase(java.util.Locale.ROOT), value);
        }
        node.put("total", total);
        return node;
    }

    private ObjectNode dependenciesNode(DependencyReport dependencies) {
        ObjectNode node = mapper.createObjectNode();
        node.put("analysed", dependencies.analysed());
        node.put("tool", dependencies.tool());
        node.put("note", dependencies.note());
        node.put("totalDependencies", dependencies.totalDependencies());
        node.put("vulnerabilities", dependencies.vulnerabilities().size());
        // "0 vulnerabilities" only means something once the report also says whether anything was looked
        // for. Without these fields a consumer cannot tell a clean scan from no scan at all.
        AdvisoryScan scan = dependencies.advisoryScan();
        node.put("vulnerabilitiesChecked", scan.performed());
        node.put("vulnerabilitySource", scan.source());
        node.put("vulnerabilityEndpoint", scan.endpoint());
        node.put("vulnerabilitiesCheckedAt", scan.performedAt() == null ? null : scan.performedAt().toString());
        node.put("vulnerabilityScanNote", scan.note());
        // The inventory as typed advisory coordinates. This is what the scan asked about, and it is the
        // only place a consumer can see which ecosystem each dependency belongs to: a package name is
        // unique within one ecosystem, not across them.
        ArrayNode coordinates = mapper.createArrayNode();
        for (PackageCoordinate coordinate : dependencies.coordinates()) {
            ObjectNode entry = mapper.createObjectNode();
            entry.put("ecosystem", coordinate.ecosystem());
            entry.put("name", coordinate.name());
            entry.put("version", coordinate.version());
            entry.put("resolved", coordinate.resolved());
            coordinates.add(entry);
        }
        node.set("coordinates", coordinates);
        ArrayNode findings = mapper.createArrayNode();
        for (DependencyVulnerability vulnerability : dependencies.vulnerabilities()) {
            ObjectNode entry = mapper.createObjectNode();
            entry.put("dependency", vulnerability.dependency());
            entry.put("version", vulnerability.version());
            entry.put("vulnerabilityId", vulnerability.vulnerabilityId());
            entry.put("severity", vulnerability.severity().name());
            entry.put("description", vulnerability.description());
            entry.put("source", vulnerability.source());
            entry.put("ecosystem", vulnerability.ecosystem());
            findings.add(entry);
        }
        node.set("vulnerabilityFindings", findings);
        return node;
    }

    // ------------------------------------------------------------------ environment / phases / budget

    private ObjectNode environmentNode(VerificationRun run) {
        EnvironmentSnapshot environment = run != null ? run.environment() : null;
        ObjectNode node = mapper.createObjectNode();
        node.put("codewitnessVersion", value(environment != null ? environment.codewitnessVersion() : null));
        node.put("javaVersion", value(environment != null ? environment.javaVersion() : null));
        node.put("javaVendor", value(environment != null ? environment.javaVendor() : null));
        node.put("operatingSystem", value(environment != null ? environment.operatingSystem() : null));
        node.put("buildTool", value(environment != null ? environment.buildTool() : null));
        node.put("buildToolVersion", value(environment != null ? environment.buildToolVersion() : null));
        node.put("sourceRevision", value(environment != null ? environment.sourceRevision() : null));
        node.put("requirementsHash", value(environment != null ? environment.requirementsHash() : null));
        node.put("configurationHash", value(environment != null ? environment.configurationHash() : null));
        node.put("llmModel", value(environment != null ? environment.llmModel() : null));
        node.put("isolationLevel", value(environment != null ? environment.isolationLevel() : null));
        node.put("sandboxDescription", value(environment != null ? environment.sandboxDescription() : null));
        Instant startedAt = environment != null && environment.startedAt() != null
                ? environment.startedAt()
                : (run != null ? run.startedAt() : null);
        Instant finishedAt = environment != null && environment.finishedAt() != null
                ? environment.finishedAt()
                : (run != null ? run.finishedAt() : null);
        node.put("startedAt", startedAt != null ? startedAt.toString() : null);
        node.put("finishedAt", finishedAt != null ? finishedAt.toString() : null);
        return node;
    }

    private static String value(String input) {
        return input == null || input.isBlank() ? "unknown" : input;
    }

    private ArrayNode phasesNode(VerificationRun run) {
        ArrayNode array = mapper.createArrayNode();
        if (run == null) {
            return array;
        }
        for (PhaseRecord phase : run.phases()) {
            ObjectNode node = mapper.createObjectNode();
            node.put("phase", phase.phase());
            node.put("executed", phase.executed());
            node.put("success", phase.success());
            node.put("durationMillis", phase.duration() != null ? phase.duration().toMillis() : 0L);
            node.put("summary", phase.summary());
            node.set("evidence", strings(phase.evidenceIds()));
            node.put("note", phase.note());
            array.add(node);
        }
        return array;
    }

    private ObjectNode budgetNode(VerificationRun run) {
        Budget budget = run != null ? run.budget() : null;
        BudgetUsage usage = run != null ? run.usage() : null;
        ObjectNode node = mapper.createObjectNode();
        node.put("maxDurationSeconds", seconds(budget != null ? budget.maxDuration() : null));
        node.put("maxTestGenerationIterations", budget != null ? budget.maxTestGenerationIterations() : 0);
        node.put("maxGeneratedTests", budget != null ? budget.maxGeneratedTests() : 0);
        node.put("maxMutationTimeSeconds", seconds(budget != null ? budget.maxMutationTime() : null));
        node.put("maxLlmCalls", budget != null ? budget.maxLlmCalls() : 0);
        node.put("elapsedSeconds", elapsedSeconds(usage != null ? usage.elapsed() : null));
        node.put("generatedTests", usage != null ? usage.generatedTests() : 0);
        node.put("llmCalls", usage != null ? usage.llmCalls() : 0);
        node.put("exhausted", usage != null && usage.exhausted());
        node.put("exhaustedReason", usage != null ? usage.exhaustedReason() : null);
        return node;
    }

    private static long seconds(Duration duration) {
        return duration == null ? 0L : duration.toSeconds();
    }

    private static double elapsedSeconds(Duration duration) {
        return duration == null ? 0.0 : duration.toMillis() / 1000.0;
    }

    // ------------------------------------------------------------------ primitives

    private ArrayNode strings(List<String> values) {
        ArrayNode array = mapper.createArrayNode();
        if (values != null) {
            for (String value : values) {
                array.add(value);
            }
        }
        return array;
    }

    private JsonNode valueNode(Object value) {
        return mapper.valueToTree(value == null ? Map.of() : value);
    }
}
