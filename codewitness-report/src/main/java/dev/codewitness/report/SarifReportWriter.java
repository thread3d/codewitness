package dev.codewitness.report;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.result.Counterexample;
import dev.codewitness.core.result.RunStatus;
import dev.codewitness.core.result.VerificationReport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes findings and counterexamples as SARIF 2.1.0.
 *
 * <p>The HTML report answers a human and the JSON report answers a program that knows CodeWitness. Neither
 * can put an annotation on a pull request, so a finding could only reach a reviewer if somebody opened the
 * report and copied it by hand. SARIF is the format code scanning understands - GitHub's code scanning,
 * GitLab's SAST report, VS Code's Problems panel - so this writer is what turns the findings into review
 * comments where the code is.
 *
 * <p>What it emits is deliberately narrow and honest:
 *
 * <ul>
 *   <li>a <em>rule</em> per rule id that actually fired, so a suppression or a baseline can be written
 *       against a stable name;</li>
 *   <li>a <em>result</em> per finding, with its severity mapped onto SARIF's three levels, its location
 *       relative to the project root, and the finding fingerprint as a partial fingerprint so a code host
 *       can tell a recurring finding from a new one;</li>
 *   <li>a <em>result</em> per counterexample, because a counterexample is the one thing this tool produces
 *       that is proof rather than advice, and dropping it from the machine-readable output would be the
 *       same mistake as dropping it from the report;</li>
 *   <li>a single <em>invocation</em> whose {@code executionSuccessful} reflects the run status, so a
 *       truncated run cannot be read as a clean scan;</li>
 *   <li>the run's limitations in the invocation's tool-execution notifications, so the things the run could
 *       not do travel with the results instead of staying behind in an HTML file.</li>
 * </ul>
 *
 * <p>Severity cannot be expressed exactly: SARIF has {@code error}, {@code warning} and {@code note}. A
 * heuristic rule's HIGH is not the same claim as a compiler's HIGH, so every result also carries
 * {@code properties.codeWitnessSeverity} and {@code properties.codeWitnessDeterministic}, and the original
 * severity is never lost to the mapping.
 */
public final class SarifReportWriter {

    /** SARIF schema this writer produces. */
    private static final String SARIF_VERSION = "2.1.0";
    private static final String SARIF_SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json";

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Renders a report as a SARIF document.
     *
     * @param report report to render
     * @return SARIF JSON, never {@code null}
     */
    public String render(VerificationReport report) {
        Objects.requireNonNull(report, "report");
        ObjectNode root = mapper.createObjectNode();
        root.put("$schema", SARIF_SCHEMA);
        root.put("version", SARIF_VERSION);
        ArrayNode runs = root.putArray("runs");
        runs.add(runNode(report));
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + System.lineSeparator();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("failed to render the SARIF report", e);
        }
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
        Objects.requireNonNull(target, "target");
        try {
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, render(report), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write SARIF report to " + target, e);
        }
        return target;
    }

    private ObjectNode runNode(VerificationReport report) {
        ObjectNode run = mapper.createObjectNode();
        run.set("tool", toolNode(report));
        run.set("invocations", invocationNode(report));

        Path projectRoot = projectRoot(report);
        if (projectRoot != null) {
            // Results carry project-relative paths, which only mean something if the consumer knows what
            // they are relative to. This is how SARIF spells that.
            ObjectNode base = mapper.createObjectNode();
            base.put("uri", toUri(projectRoot));
            run.set("originalUriBaseIds", mapper.createObjectNode().set("PROJECTROOT", base));
        }

        run.set("results", resultsNode(report, projectRoot));
        return run;
    }

    private ObjectNode toolNode(VerificationReport report) {
        ObjectNode driver = mapper.createObjectNode();
        driver.put("name", "CodeWitness");
        driver.put("fullName", "CodeWitness independent verification engine");
        driver.put("informationUri", "https://github.com/thread3d/CodeWitness");
        String version = report.run() != null && report.run().environment() != null
                ? report.run().environment().codewitnessVersion() : null;
        driver.put("version", version == null || version.isBlank() ? "unknown" : version);
        driver.set("rules", rulesNode(report.findings()));
        ObjectNode tool = mapper.createObjectNode();
        tool.set("driver", driver);
        return tool;
    }

    /**
     * @return one rule per rule id that fired, in first-seen order
     */
    private ArrayNode rulesNode(List<Finding> findings) {
        Map<String, Finding> byRule = new LinkedHashMap<>();
        for (Finding finding : findings) {
            if (finding.ruleId() != null && !finding.ruleId().isBlank()) {
                byRule.putIfAbsent(finding.ruleId(), finding);
            }
        }
        ArrayNode rules = mapper.createArrayNode();
        for (Map.Entry<String, Finding> entry : byRule.entrySet()) {
            Finding finding = entry.getValue();
            ObjectNode rule = rules.addObject();
            rule.put("id", entry.getKey());
            rule.put("name", entry.getKey());
            ObjectNode shortDescription = rule.putObject("shortDescription");
            shortDescription.put("text", finding.category().name().toLowerCase(java.util.Locale.ROOT)
                    + " rule reported by " + finding.analyzer());
            rule.put("defaultConfiguration", mapper.createObjectNode()
                    .put("level", level(finding.severity())));
            ObjectNode properties = rule.putObject("properties");
            properties.put("category", finding.category().name());
            properties.put("analyzer", finding.analyzer());
            properties.put("advisory", !finding.fromDeterministicTool());
        }
        return rules;
    }

    private ArrayNode invocationNode(VerificationReport report) {
        ArrayNode invocations = mapper.createArrayNode();
        ObjectNode invocation = invocations.addObject();
        RunStatus status = report.run() != null ? report.run().status() : null;
        // `executionSuccessful` answers "did the analysis finish", not "is the code correct": a run that
        // found counterexamples still executed successfully. A BLOCKED run did not, and a consumer that
        // uploads its results has to be able to see that.
        invocation.put("executionSuccessful", status != RunStatus.BLOCKED);
        if (status != null) {
            invocation.put("exitCode", status == RunStatus.BLOCKED ? 2 : 0);
        }
        ObjectNode properties = invocation.putObject("properties");
        properties.put("runId", report.runId());
        properties.put("runStatus", status == null ? "unknown" : status.name());
        properties.put("overallStatus", report.overallStatus().name());
        properties.put("verdicts", verdictSummary(report));

        ArrayNode notifications = invocation.putArray("toolExecutionNotifications");
        for (String limitation : report.limitations()) {
            ObjectNode notification = notifications.addObject();
            notification.put("level", "note");
            notification.set("message", mapper.createObjectNode().put("text", limitation));
        }
        return invocations;
    }

    private ObjectNode verdictSummary(VerificationReport report) {
        ObjectNode counts = mapper.createObjectNode();
        report.verdictCounts().forEach((status, count) -> counts.put(status.name(), count));
        return counts;
    }

    private ArrayNode resultsNode(VerificationReport report, Path projectRoot) {
        ArrayNode results = mapper.createArrayNode();
        for (Finding finding : report.findings()) {
            ObjectNode result = results.addObject();
            result.put("ruleId", finding.ruleId() == null ? finding.analyzer() : finding.ruleId());
            result.put("level", level(finding.severity()));
            result.set("message", mapper.createObjectNode().put("text", finding.message()));
            if (finding.file() != null && !finding.file().isBlank()) {
                ObjectNode location = result.putArray("locations").addObject();
                ObjectNode physical = location.putObject("physicalLocation");
                ObjectNode artifact = physical.putObject("artifactLocation");
                artifact.put("uri", relativise(finding.file(), projectRoot));
                if (projectRoot != null) {
                    artifact.put("uriBaseId", "PROJECTROOT");
                }
                if (finding.line() != null && finding.line() > 0) {
                    physical.putObject("region").put("startLine", finding.line());
                }
            }
            result.set("partialFingerprints", mapper.createObjectNode()
                    .put("codeWitnessFingerprint", finding.fingerprint()));
            ObjectNode properties = result.putObject("properties");
            properties.put("codeWitnessSeverity", finding.severity().name());
            properties.put("codeWitnessDeterministic", finding.fromDeterministicTool());
            properties.put("codeWitnessAnalyzer", finding.analyzer());
            properties.put("codeWitnessCategory", finding.category().name());
            if (finding.module() != null && !finding.module().isBlank()) {
                properties.put("codeWitnessModule", finding.module());
            }
        }
        for (Counterexample counterexample : report.counterexamples()) {
            ObjectNode result = results.addObject();
            result.put("ruleId", "codewitness/counterexample");
            // A counterexample reproduced by execution is not advice, so it is an error by definition.
            result.put("level", "error");
            result.set("message", mapper.createObjectNode().put("text",
                    counterexample.summary() + " (requirement " + counterexample.requirementId()
                            + "; expected " + nullSafe(counterexample.expected())
                            + ", observed " + nullSafe(counterexample.actual()) + ")"));
            Path artifact = counterexample.reproduction() != null
                    ? counterexample.reproduction().testArtifact() : null;
            if (artifact != null) {
                ObjectNode location = result.putArray("locations").addObject();
                ObjectNode physical = location.putObject("physicalLocation");
                physical.putObject("artifactLocation").put("uri", relativise(artifact.toString(), projectRoot));
            }
            result.set("partialFingerprints", mapper.createObjectNode()
                    .put("codeWitnessFingerprint", "counterexample:" + counterexample.requirementId()
                            + ":" + nullSafe(counterexample.invariantViolated())));
            ObjectNode properties = result.putObject("properties");
            properties.put("codeWitnessRequirement", counterexample.requirementId());
            properties.put("codeWitnessObligation", counterexample.obligationId());
            properties.put("codeWitnessDiscoveredBy", counterexample.discoveredBy() == null
                    ? "unknown" : counterexample.discoveredBy().name());
            if (counterexample.reproduction() != null) {
                properties.put("codeWitnessReproduce", counterexample.reproduction().displayCommand());
            }
            if (counterexample.seed() != null) {
                properties.put("codeWitnessSeed", counterexample.seed());
            }
        }
        return results;
    }

    /**
     * Maps a CodeWitness severity onto SARIF's three levels.
     *
     * <p>The mapping is lossy on purpose: SARIF has no room for six severities, and inventing a scale a
     * consumer does not understand would be worse than a faithful approximation. The original value is kept
     * in each result's properties, so nothing is actually lost.
     *
     * @param severity severity to map
     * @return {@code error}, {@code warning} or {@code note}
     */
    private static String level(Severity severity) {
        if (severity == null) {
            return "note";
        }
        return switch (severity) {
            case CRITICAL, HIGH -> "error";
            case MEDIUM -> "warning";
            case LOW, INFO, UNKNOWN -> "note";
        };
    }

    private static Path projectRoot(VerificationReport report) {
        return report.project() != null ? report.project().project().root() : null;
    }

    /**
     * @return {@code path} relative to the project root when it is inside it, otherwise {@code path}
     */
    private static String relativise(String path, Path projectRoot) {
        if (path == null) {
            return "";
        }
        if (projectRoot == null) {
            return path.replace('\\', '/');
        }
        String root = projectRoot.toAbsolutePath().normalize().toString();
        String normalised = path.replace('\\', '/');
        String normalisedRoot = root.replace('\\', '/');
        if (normalised.startsWith(normalisedRoot + "/")) {
            return normalised.substring(normalisedRoot.length() + 1);
        }
        return normalised;
    }

    private static String toUri(Path root) {
        String text = root.toAbsolutePath().normalize().toString().replace('\\', '/');
        return text.startsWith("/") ? "file://" + text + "/" : "file:///" + text + "/";
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
