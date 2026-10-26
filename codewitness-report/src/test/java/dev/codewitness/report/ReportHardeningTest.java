package dev.codewitness.report;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.result.VerificationReport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The report is written from data a hostile project controls, and it is the artifact a user keeps and shares.
 * These tests bound what that data can do: it cannot break out of the document, it cannot make the report
 * unencodable, and it cannot make the report unbounded.
 */
class ReportHardeningTest {

    @Test
    @DisplayName("the document carries a content security policy that allows only its own inline stylesheet")
    void theReportDeclaresAContentSecurityPolicy() {
        // Nothing in the report is executable today, so this is defence in depth: a report is emailed, archived
        // and opened from file://, and one interpolation that ever slips past the escaping must not be able to
        // reach an attacker's host.
        String html = new HtmlReportWriter().render(ReportFixtures.report());

        assertTrue(html.contains("http-equiv=\"Content-Security-Policy\""), "a CSP meta tag must be present");
        assertTrue(html.contains("default-src 'none'"), "nothing may be loaded by default");
        assertTrue(html.contains("style-src 'unsafe-inline'"), "the report's own stylesheet must still apply");
    }

    @Test
    @DisplayName("an unpaired surrogate is replaced, so the report can still be written")
    void anUnpairedSurrogateDoesNotDestroyTheReport(@TempDir Path tmp) throws Exception {
        // A hostile test report containing "\ud800" decodes to a lone surrogate, travels through the adapters
        // and reaches the writer. Files.writeString used to throw before writing anything, losing both report
        // files - and the durable record too, because they are written in one block.
        VerificationReport report = reportWithFindings(List.of(finding("a lone surrogate: \ud800 in a name")));
        Path html = tmp.resolve("report.html");
        Path json = tmp.resolve("report.json");

        new HtmlReportWriter().write(report, html);
        new JsonReportWriter().write(report, json);

        assertTrue(Files.isRegularFile(html), "the HTML report must exist");
        String text = Files.readString(html, StandardCharsets.UTF_8);
        assertTrue(text.contains("\uFFFD"), "the unencodable character is shown as a replacement, not dropped");
        assertTrue(Files.isRegularFile(json), "the JSON report must exist");
        assertTrue(Files.readString(json, StandardCharsets.UTF_8).contains("\uFFFD"));
    }

    @Test
    @DisplayName("a surrogate pair that is a real character is left alone")
    void aValidSurrogatePairSurvives() {
        String emoji = "verdict \uD83D\uDD0E ok";

        assertEquals(emoji, ReportText.encodable(emoji), "an ordinary character outside the basic plane is not damage");
    }

    @Test
    @DisplayName("the findings table is bounded and says so")
    void theFindingsTableIsBounded() {
        // A compiler emitting two million warnings is a property of the project, not of CodeWitness, and a report
        // nobody can open is not a report.
        List<Finding> many = new ArrayList<>();
        for (int i = 0; i < 900; i++) {
            many.add(finding("finding " + i));
        }
        String html = new HtmlReportWriter().render(reportWithFindings(many));

        assertTrue(html.contains("Showing the first 500 of 900 findings"), "the truncation must be stated");
        assertTrue(html.contains("finding 499"), "the rows inside the bound are rendered");
        assertTrue(!html.contains("finding 500"), "the rows outside the bound are not");
    }

    private static Finding finding(String message) {
        return new Finding("heuristic", "CW-TEST-001", Severity.MEDIUM, FindingCategory.BUG, message,
                "src/A.java", 1, "app", Map.of());
    }

    private static VerificationReport reportWithFindings(List<Finding> findings) {
        return ReportFixtures.reportWithFindings(findings);
    }
}
