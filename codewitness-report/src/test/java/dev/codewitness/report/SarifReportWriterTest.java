package dev.codewitness.report;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.result.RunStatus;
import dev.codewitness.core.result.VerificationReport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SARIF writer is what lets a finding reach a reviewer where the code is. These tests pin the parts a
 * consumer depends on: the document header, one rule per rule id, one result per finding with a level and a
 * location, the counterexample as an error, and an invocation that says whether the run finished.
 */
class SarifReportWriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode sarif(VerificationReport report) throws Exception {
        return MAPPER.readTree(new SarifReportWriter().render(report));
    }

    @Test
    void emitsSarif21WithTheCodeWitnessDriver() throws Exception {
        JsonNode root = sarif(ReportFixtures.report());

        assertEquals("2.1.0", root.path("version").asText());
        assertTrue(root.has("$schema"), "the schema is what tells a consumer how to read the file");
        JsonNode driver = root.path("runs").get(0).path("tool").path("driver");
        assertEquals("CodeWitness", driver.path("name").asText());
        assertFalse(driver.path("version").asText().isBlank(), "the driver version is recorded");
        assertTrue(driver.path("rules").isArray(), "rules must be an array even when it is empty");
    }

    @Test
    void oneRulePerRuleIdAndOneResultPerFinding() throws Exception {
        VerificationReport report = ReportFixtures.report();
        JsonNode run = sarif(report).path("runs").get(0);

        assertEquals(report.findings().size() + report.counterexamples().size(), run.path("results").size(),
                "every finding and every counterexample must appear as a result");
        long distinctRules = report.findings().stream()
                .map(Finding::ruleId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .count();
        assertEquals(distinctRules, run.path("tool").path("driver").path("rules").size(),
                "a rule is declared once, however many findings it produced");
    }

    @Test
    void severityMapsOntoASarifLevelAndTheOriginalSurvivesInProperties() throws Exception {
        Finding high = new Finding("heuristic", "CW-TEST-001", Severity.HIGH, FindingCategory.BUG,
                "a high finding", "src/A.java", 12, "app", Map.of());
        Finding low = new Finding("heuristic", "CW-TEST-002", Severity.LOW, FindingCategory.STYLE,
                "a low finding", "src/B.java", 3, "app", Map.of());
        JsonNode results = sarif(ReportFixtures.reportWithFindings(List.of(high, low)))
                .path("runs").get(0).path("results");

        JsonNode highResult = results.get(0);
        JsonNode lowResult = results.get(1);
        assertEquals("error", highResult.path("level").asText());
        assertEquals("note", lowResult.path("level").asText());
        assertEquals("HIGH", highResult.path("properties").path("codeWitnessSeverity").asText(),
                "the SARIF level is lossy, so the original severity must be kept");
        assertFalse(highResult.path("properties").path("codeWitnessDeterministic").asBoolean(),
                "a heuristic finding is advisory, and a consumer must be able to see that");
    }

    @Test
    void aFindingCarriesAProjectRelativeLocationAndItsLine() throws Exception {
        Finding finding = new Finding("heuristic", "CW-TEST-003", Severity.MEDIUM, FindingCategory.SECURITY,
                "a finding", "src/main/java/dev/example/A.java", 42, "app", Map.of());
        JsonNode result = sarif(ReportFixtures.reportWithFindings(List.of(finding)))
                .path("runs").get(0).path("results").get(0);

        JsonNode physical = result.path("locations").get(0).path("physicalLocation");
        assertEquals(42, physical.path("region").path("startLine").asInt());
        assertFalse(physical.path("artifactLocation").path("uri").asText().startsWith("/"),
                "a location inside the project is reported relative to the project root");
        assertTrue(physical.path("artifactLocation").has("uriBaseId"),
                "a relative uri only means something with a base");
    }

    @Test
    void aCounterexampleIsAnErrorWithItsReproductionProperties() throws Exception {
        JsonNode results = sarif(ReportFixtures.report()).path("runs").get(0).path("results");

        JsonNode counterexample = null;
        for (JsonNode result : results) {
            if ("codewitness/counterexample".equals(result.path("ruleId").asText())) {
                counterexample = result;
            }
        }
        assertTrue(counterexample != null, "a counterexample must reach the machine-readable output");
        assertEquals("error", counterexample.path("level").asText(),
                "an executed counterexample is proof of a violation, not advice");
        assertFalse(counterexample.path("properties").path("codeWitnessRequirement").asText().isBlank(),
                "the violated requirement must be named");
    }

    @Test
    void aBlockedRunIsNotAnExecutionSuccess() throws Exception {
        VerificationReport blocked = ReportFixtures.reportWithRunStatus(RunStatus.BLOCKED);
        JsonNode invocation = sarif(blocked).path("runs").get(0).path("invocations").get(0);

        assertFalse(invocation.path("executionSuccessful").asBoolean(),
                "a truncated run must not look like a completed scan");
        assertEquals("BLOCKED", invocation.path("properties").path("runStatus").asText());
        assertTrue(invocation.path("toolExecutionNotifications").size() > 0,
                "what the run could not do travels with the results");
    }

    @Test
    void writesToDisk(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("out/results.sarif");
        Path written = new SarifReportWriter().write(ReportFixtures.report(), target);

        assertEquals(target, written);
        assertTrue(Files.isRegularFile(target), "the file must exist");
        assertEquals("2.1.0", MAPPER.readTree(Files.readString(target)).path("version").asText());
    }
}
