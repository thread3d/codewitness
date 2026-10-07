package dev.codewitness.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.requirements.RequirementSet;
import dev.codewitness.core.result.VerificationReport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the shape and content of the canonical JSON report. */
class JsonReportWriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> REQUIRED_TOP_LEVEL = List.of(
            "runId", "project", "status", "statusExplanation", "generatedAt", "requirements",
            "counterexamples", "evidence", "tests", "mutation", "coverage", "findings", "security",
            "dependencies", "environment", "phases", "budget", "limitations");

    private static VerificationReport report;
    private static JsonNode json;

    @BeforeAll
    static void renderOnce() throws Exception {
        report = ReportFixtures.report();
        json = MAPPER.readTree(new JsonReportWriter().render(report));
    }

    @Test
    void aReportThatNeverCheckedAdvisoriesSaysSoInsteadOfReportingZero() throws Exception {
        JsonNode unchecked = MAPPER.readTree(
                new JsonReportWriter().render(ReportFixtures.reportWithoutAdvisoryScan()))
                .path("dependencies");

        assertEquals(0, unchecked.path("vulnerabilities").asInt());
        // The count alone would read as a clean scan; the flag is what makes it honest.
        assertFalse(unchecked.path("vulnerabilitiesChecked").asBoolean());
        assertEquals("none", unchecked.path("vulnerabilitySource").asText());
        assertTrue(unchecked.path("vulnerabilityScanNote").asText().contains("no advisory source"),
                unchecked.path("vulnerabilityScanNote").asText());
        assertEquals(0, unchecked.path("vulnerabilityFindings").size());
    }

    @Test
    void documentContainsRequiredKeysInOrder() {
        List<String> actual = new ArrayList<>();
        Iterator<String> names = json.fieldNames();
        while (names.hasNext()) {
            actual.add(names.next());
        }
        assertEquals(REQUIRED_TOP_LEVEL, actual);
    }

    @Test
    void documentIsPrettyPrinted() {
        String rendered = new JsonReportWriter().render(report);
        assertTrue(rendered.contains("\n"), "canonical report should be indented");
        assertTrue(rendered.contains("\"runId\""), rendered);
    }

    @Test
    void headerMatchesTheReport() {
        assertEquals(ReportFixtures.RUN_ID, json.path("runId").asText());
        assertEquals("FAILED", json.path("status").asText());
        assertTrue(json.path("statusExplanation").asText().contains("verified"),
                json.path("statusExplanation").asText());
        assertEquals(ReportFixtures.FINISHED_AT.toString(), json.path("generatedAt").asText());
    }

    @Test
    void projectBlockIsComplete() {
        JsonNode project = json.path("project");
        assertEquals(List.of("name", "path", "identifier", "language", "buildSystem", "modules", "javaVersion"),
                keys(project));
        assertEquals("example-project", project.path("name").asText());
        assertFalse(project.path("path").asText().isBlank());
        assertEquals("java", project.path("language").asText());
        assertEquals("maven", project.path("buildSystem").asText());
        assertEquals(2, project.path("modules").size());
        assertEquals("example-core", project.path("modules").get(0).asText());
        assertEquals("17", project.path("javaVersion").asText());
    }

    @Test
    void requirementsCarryVerdictsObligationsAndUnknownAspects() {
        JsonNode requirements = json.path("requirements");
        assertEquals(2, requirements.size());

        JsonNode verified = requirements.get(0);
        assertEquals(List.of("id", "title", "description", "status", "rationale", "evidence",
                        "obligations", "unknownAspects", "counterexamples"),
                keys(verified));
        assertEquals("RQ-1", verified.path("id").asText());
        assertEquals("VERIFIED", verified.path("status").asText());
        assertEquals("Cart total upper bound", verified.path("title").asText());
        assertFalse(verified.path("description").asText().isBlank());
        assertFalse(verified.path("rationale").asText().isBlank());
        assertEquals("EV-001", verified.path("evidence").get(0).asText());
        assertEquals(1, verified.path("unknownAspects").size());
        assertEquals(0, verified.path("counterexamples").size());

        JsonNode obligation = verified.path("obligations").get(0);
        assertEquals(List.of("id", "description", "kind", "origin", "status", "rationale", "evidence",
                "attempts"), keys(obligation));
        assertEquals("OB-001", obligation.path("id").asText());
        assertEquals("UPPER_BOUND", obligation.path("kind").asText());
        assertEquals("REQUIREMENT_TEXT", obligation.path("origin").asText());
        assertEquals("VERIFIED", obligation.path("status").asText());
        assertEquals(3, obligation.path("attempts").asInt());

        JsonNode failed = requirements.get(1);
        assertEquals("RQ-2", failed.path("id").asText());
        assertEquals("FAILED", failed.path("status").asText());
        assertEquals("CE-001", failed.path("counterexamples").get(0).asText());
        assertEquals("FAILED", failed.path("obligations").get(0).path("status").asText());
    }

    @Test
    void counterexamplesCarryInputsAndReproduction() {
        JsonNode counterexample = json.path("counterexamples").get(0);
        assertEquals(List.of("id", "requirementId", "obligationId", "summary", "inputs", "expected", "actual",
                        "invariantViolated", "observedBy", "evidenceId", "seed", "reproduction", "discoveredBy"),
                keys(counterexample));
        assertEquals("CE-001", counterexample.path("id").asText());
        assertEquals("RQ-2", counterexample.path("requirementId").asText());
        assertEquals("OB-002", counterexample.path("obligationId").asText());
        assertEquals(ReportFixtures.COUNTEREXAMPLE_QUANTITY, counterexample.path("inputs").path("quantity").asText());
        assertEquals(ReportFixtures.COUNTEREXAMPLE_ITEM, counterexample.path("inputs").path("item").asText());
        assertEquals("total >= 0", counterexample.path("invariantViolated").asText());
        assertEquals(42L, counterexample.path("seed").asLong());
        assertEquals("PROPERTY_SEARCH", counterexample.path("discoveredBy").asText());

        JsonNode reproduction = counterexample.path("reproduction");
        assertEquals(List.of("description", "command", "testArtifact", "properties"), keys(reproduction));
        assertTrue(reproduction.path("command").get(0).asText().equals("mvn"));
        assertEquals("42", reproduction.path("properties").path("cw.seed").asText());
    }

    @Test
    void evidenceBlockCarriesProvenance() {
        JsonNode evidence = json.path("evidence").get(0);
        assertEquals(List.of("id", "type", "status", "confidence", "description", "source", "timestamp",
                        "command", "inputs", "output", "result", "metadata", "artifacts", "requirements",
                        "obligations", "generatedByLlm", "executedIndependently", "reproducible"),
                keys(evidence));
        assertEquals("EV-001", evidence.path("id").asText());
        assertEquals("GENERATED_TEST", evidence.path("type").asText());
        assertEquals("SUPPORTS", evidence.path("status").asText());
        assertEquals("DETERMINISTIC", evidence.path("confidence").asText());
        assertEquals("mvn", evidence.path("command").get(0).asText());
        assertEquals(1000, evidence.path("inputs").path("limit").asInt());
        assertTrue(evidence.path("generatedByLlm").asBoolean());
        assertTrue(evidence.path("executedIndependently").asBoolean());
        assertTrue(evidence.path("reproducible").asBoolean());
        assertEquals("RQ-1", evidence.path("requirements").get(0).asText());
        assertEquals("OB-001", evidence.path("obligations").get(0).asText());
    }

    @Test
    void testsBlockReportsFailures() {
        JsonNode tests = json.path("tests");
        assertEquals(List.of("executed", "note", "total", "passed", "failed", "errors", "skipped",
                "durationMillis", "failures"), keys(tests));
        assertTrue(tests.path("executed").asBoolean());
        assertEquals(3, tests.path("total").asInt());
        assertEquals(2, tests.path("passed").asInt());
        assertEquals(1, tests.path("failed").asInt());
        assertEquals(3450L, tests.path("durationMillis").asLong());
        JsonNode failure = tests.path("failures").get(0);
        assertEquals(List.of("suite", "name", "failureType", "failureMessage"), keys(failure));
        assertEquals("dev.example.CartTest", failure.path("suite").asText());
        assertEquals("negativeQuantityRejected", failure.path("name").asText());
        assertTrue(failure.path("failureMessage").asText().contains("rejected"));
    }

    @Test
    void mutationBlockReportsSurvivors() {
        JsonNode mutation = json.path("mutation");
        assertEquals(List.of("measured", "tool", "note", "generated", "killed", "survived", "noCoverage",
                "timedOut", "scorePercent", "survivors"), keys(mutation));
        assertTrue(mutation.path("measured").asBoolean());
        assertEquals(8, mutation.path("survived").asInt());
        assertEquals(1, mutation.path("survivors").size());
        // 30 killed of 38 covered mutants (30 killed + 8 survived, no-coverage excluded).
        assertEquals(78.947, mutation.path("scorePercent").asDouble(), 0.01);
        JsonNode survivor = mutation.path("survivors").get(0);
        assertEquals(List.of("id", "status", "mutator", "className", "method", "line", "description"),
                keys(survivor));
        assertEquals("SURVIVED", survivor.path("status").asText());
        assertEquals(57, survivor.path("line").asInt());
    }

    @Test
    void coverageBlockReportsPercentages() {
        JsonNode coverage = json.path("coverage");
        assertEquals(List.of("measured", "tool", "note", "lineCoveragePercent", "branchCoveragePercent",
                "lineCovered", "lineTotal", "branchCovered", "branchTotal", "perClassLineCoverage"),
                keys(coverage));
        assertTrue(coverage.path("measured").asBoolean());
        assertEquals("jacoco", coverage.path("tool").asText());
        assertEquals(82.0, coverage.path("lineCoveragePercent").asDouble(), 0.001);
        assertEquals(100, coverage.path("lineTotal").asInt());
        assertEquals(91.0, coverage.path("perClassLineCoverage").path("dev.example.Cart").asDouble(), 0.001);
    }

    @Test
    void perFileCoverageIsSerialisedInAStableOrderWithAbsolutePathsMadeRelative() throws Exception {
        VerificationReport custom = reportWithCoverage(new CoverageReport(true, "jacoco", "", 3, 4, 0, 0,
                Map.of("/sandbox/project/src/b.ts", 50.0,
                        "/sandbox/project/src/a.ts", 100.0,
                        "src/c.ts", 25.0),
                List.of()));

        JsonNode perFile = MAPPER.readTree(new JsonReportWriter().render(custom))
                .path("coverage").path("perClassLineCoverage");

        // Sorted by the displayed key, and an absolute path below the project root is rendered relative.
        assertEquals(List.of("src/a.ts", "src/b.ts", "src/c.ts"), keys(perFile));
        assertEquals(100.0, perFile.path("src/a.ts").asDouble(), 0.001);
        assertEquals(25.0, perFile.path("src/c.ts").asDouble(), 0.001);
    }

    @Test
    void anEmptyPerFileCoverageMapIsOmittedRatherThanReportedAsEmpty() throws Exception {
        VerificationReport custom = reportWithCoverage(CoverageReport.notMeasured("jacoco", "no report"));

        JsonNode coverage = MAPPER.readTree(new JsonReportWriter().render(custom)).path("coverage");

        assertFalse(coverage.has("perClassLineCoverage"), coverage.toString());
    }

    /** @return a minimal report whose coverage section is the supplied one. */
    private static VerificationReport reportWithCoverage(CoverageReport coverage) {
        ProjectInfo project = ProjectInfo.builder(ProjectRef.of(Path.of("/sandbox/project")))
                .language(LanguageId.JAVA)
                .build();
        return VerificationReport.builder("run-coverage", project, new RequirementSet(List.of()))
                .coverage(coverage)
                .build();
    }

    @Test
    void findingsAlwaysCarryTheSpecificationKeys() {
        JsonNode finding = json.path("findings").get(0);
        assertEquals(List.of("severity", "category", "file", "line", "message", "analyzer", "rule"),
                keys(finding));
        assertEquals("HIGH", finding.path("severity").asText());
        assertEquals("SECURITY", finding.path("category").asText());
        assertEquals("src/main/java/dev/example/Repo.java", finding.path("file").asText());
        assertEquals(42, finding.path("line").asInt());
        assertFalse(finding.path("message").asText().isBlank());
        assertEquals("spotbugs", finding.path("analyzer").asText());
        assertEquals("SQL_INJECTION", finding.path("rule").asText());

        JsonNode hostile = json.path("findings").get(1);
        assertEquals(ReportFixtures.HOSTILE_MESSAGE, hostile.path("message").asText());
        assertEquals(7, hostile.path("line").asInt());
    }

    @Test
    void securityCountsOnlySecurityFindings() {
        JsonNode security = json.path("security");
        // One column per severity, in enum order; `unknown` exists so that a source which cannot state
        // a severity is counted rather than dropped.
        assertEquals(List.of("critical", "high", "medium", "low", "info", "unknown", "total"), keys(security));
        assertEquals(0, security.path("critical").asInt());
        assertEquals(1, security.path("high").asInt());
        assertEquals(0, security.path("medium").asInt());
        assertEquals(0, security.path("unknown").asInt());
        assertEquals(1, security.path("total").asInt());
    }

    @Test
    void dependenciesEnvironmentPhasesBudgetAndLimitations() {
        JsonNode dependencies = json.path("dependencies");
        assertEquals(List.of("analysed", "tool", "note", "totalDependencies", "vulnerabilities",
                        "vulnerabilitiesChecked", "vulnerabilitySource", "vulnerabilityEndpoint",
                        "vulnerabilitiesCheckedAt", "vulnerabilityScanNote", "vulnerabilityFindings"),
                keys(dependencies));
        assertTrue(dependencies.path("analysed").asBoolean());
        assertEquals(12, dependencies.path("totalDependencies").asInt());
        assertEquals(1, dependencies.path("vulnerabilities").asInt());
        // A count is only meaningful next to whether anything was looked for.
        assertTrue(dependencies.path("vulnerabilitiesChecked").asBoolean());
        assertEquals("osv", dependencies.path("vulnerabilitySource").asText());
        JsonNode finding = dependencies.path("vulnerabilityFindings").get(0);
        assertEquals(List.of("dependency", "version", "vulnerabilityId", "severity", "description", "source"),
                keys(finding));
        assertEquals("CVE-2024-0001", finding.path("vulnerabilityId").asText());
        assertEquals("HIGH", finding.path("severity").asText());

        JsonNode environment = json.path("environment");
        assertEquals(List.of("codewitnessVersion", "javaVersion", "javaVendor", "operatingSystem", "buildTool",
                        "buildToolVersion", "sourceRevision", "requirementsHash", "configurationHash",
                        "llmModel", "isolationLevel", "sandboxDescription", "startedAt", "finishedAt"),
                keys(environment));
        assertEquals("abc1234", environment.path("sourceRevision").asText());
        assertEquals("PROCESS", environment.path("isolationLevel").asText());
        assertEquals(ReportFixtures.STARTED_AT.toString(), environment.path("startedAt").asText());

        JsonNode phase = json.path("phases").get(0);
        assertEquals(List.of("phase", "executed", "success", "durationMillis", "summary", "evidence", "note"),
                keys(phase));
        assertEquals("build", phase.path("phase").asText());

        JsonNode budget = json.path("budget");
        assertEquals(List.of("maxDurationSeconds", "maxTestGenerationIterations", "maxGeneratedTests",
                "maxMutationTimeSeconds", "maxLlmCalls", "elapsedSeconds", "generatedTests", "llmCalls",
                "exhausted", "exhaustedReason"), keys(budget));
        assertEquals(900L, budget.path("maxDurationSeconds").asLong());
        assertEquals(12.0, budget.path("elapsedSeconds").asDouble(), 0.001);
        assertEquals(34, budget.path("generatedTests").asInt());
        assertFalse(budget.path("exhausted").asBoolean());

        assertEquals(1, json.path("limitations").size());
        assertEquals(ReportFixtures.LIMITATION, json.path("limitations").get(0).asText());
    }

    @Test
    void summaryIsCompactAndCarriesCounts() throws Exception {
        String summary = JsonReportWriter.renderSummary(report);
        assertFalse(summary.contains("\n"), "summary must not be pretty printed: " + summary);
        JsonNode node = MAPPER.readTree(summary);
        assertEquals(ReportFixtures.RUN_ID, node.path("runId").asText());
        assertEquals("FAILED", node.path("status").asText());
        assertEquals(1, node.path("verified").asInt());
        assertEquals(0, node.path("partiallyVerified").asInt());
        assertEquals(1, node.path("failed").asInt());
        assertEquals(0, node.path("unverified").asInt());
        assertEquals(0, node.path("blocked").asInt());
    }

    @Test
    void writeCreatesTheFile(@TempDir Path directory) throws Exception {
        Path target = directory.resolve("nested/reports/report.json");
        Path written = new JsonReportWriter().write(report, target);

        assertEquals(target, written);
        assertTrue(Files.exists(target), "report file should exist");
        JsonNode parsed = MAPPER.readTree(Files.readString(target));
        assertEquals(ReportFixtures.RUN_ID, parsed.path("runId").asText());
        assertNotNull(parsed.path("evidence"));
    }

    private static List<String> keys(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }
}
