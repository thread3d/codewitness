package dev.codewitness.report;

import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.requirements.RequirementSet;
import dev.codewitness.core.result.VerificationReport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the structure, completeness and escaping behaviour of the HTML report. */
class HtmlReportWriterTest {

    private static VerificationReport report;
    private static String html;

    @BeforeAll
    static void renderOnce() {
        report = ReportFixtures.report();
        html = new HtmlReportWriter().render(report);
    }

    @Test
    void startsWithTheExecutiveSummary() {
        assertTrue(html.trim().startsWith("<!DOCTYPE html>"), "must be a complete HTML document");
        int title = html.indexOf("<title>");
        assertTrue(title >= 0, "document must have a title");
        assertTrue(html.contains("<h1>CODEWITNESS</h1>"), "document must be branded CODEWITNESS");
        assertTrue(html.contains("Verification Report"), "document must be titled Verification Report");
        assertTrue(html.indexOf("CODEWITNESS") < html.indexOf("id=\"requirements\""),
                "executive summary must come first");
        assertTrue(html.contains("Executive summary"));
    }

    @Test
    void summaryShowsProjectRevisionStatusAndExactMarkers() {
        assertTrue(html.contains("example-project"), "project name must be shown");
        assertTrue(html.contains("abc1234"), "source revision must be shown");
        assertTrue(html.contains("Verification Report"));
        assertTrue(html.contains("✓"), "VERIFIED marker must be present");
        assertTrue(html.contains("⚠"), "PARTIALLY_VERIFIED marker must be present");
        assertTrue(html.contains("✗"), "FAILED marker must be present");
        assertTrue(html.contains("?"), "UNVERIFIED marker must be present");
        assertTrue(html.contains("⛔"), "BLOCKED marker must be present");
        assertTrue(html.contains("VERIFIED") && html.contains("FAILED"));
        assertTrue(html.contains("summary-table"), "summary table must be present");
        // Colour is never the only signal: each status carries a text label next to its symbol.
        assertTrue(html.contains("✓ VERIFIED") || html.contains("✓</span>"));
    }

    @Test
    void rendersRequirementsWithObligationsRationalesAndUnknownAspects() {
        assertTrue(html.contains("id=\"requirements\""));
        assertTrue(html.contains("RQ-1") && html.contains("RQ-2"));
        assertTrue(html.contains("Cart total upper bound"));
        assertTrue(html.contains("OB-001") && html.contains("OB-002"));
        assertTrue(html.contains("UPPER_BOUND") && html.contains("REJECTION"));
        assertTrue(html.contains("REQUIREMENT_TEXT"));
        assertTrue(html.contains("A generated boundary test independently confirmed the upper bound."),
                "requirement rationale must be shown");
        assertTrue(html.contains("Behaviour under concurrent adds was not exercised."),
                "unknown aspects must be shown");
    }

    @Test
    void rendersCounterexampleInputsExpectedActualInvariantAndCommand() {
        assertTrue(html.contains("id=\"counterexamples\""));
        assertTrue(html.contains("CE-001"));
        assertTrue(html.contains(ReportFixtures.COUNTEREXAMPLE_QUANTITY), "input quantity must be shown");
        assertTrue(html.contains(ReportFixtures.COUNTEREXAMPLE_ITEM), "input item must be shown");
        assertTrue(html.contains("total &gt;= 0"), "invariant must be shown (escaped)");
        assertTrue(html.contains("the call is rejected with an error"), "expected must be shown");
        assertTrue(html.contains("the cart total decreased by 5"), "actual must be shown");
        assertTrue(html.contains("<pre><code>"), "reproduction command must be in a code block");
        assertTrue(html.contains("CartPropertyTest#negativeQuantityRejected"),
                "reproduction command must be shown");
    }

    @Test
    void rendersTestsAndFailures() {
        assertTrue(html.contains("id=\"tests\""));
        assertTrue(html.contains("dev.example.CartTest"));
        assertTrue(html.contains("negativeQuantityRejected"), "failing test name must be shown");
        assertTrue(html.contains("expected: &lt;rejected&gt; but was: &lt;accepted&gt;"),
                "failure message must be shown and escaped");
    }

    @Test
    void rendersMutationWithTheNobodyCheckedExplanation() {
        assertTrue(html.contains("id=\"mutation\""));
        assertTrue(html.contains("MUT-7"));
        assertTrue(html.contains("ConditionalsBoundaryMutator"));
        assertTrue(html.contains("nobody checked"),
                "surviving mutants must be explained as untested behaviour");
        assertTrue(html.contains("does <strong>not</strong> mean the requirement is violated"),
                "the report must say a surviving mutant is not a violation");
        assertTrue(html.contains("78.9%"), "mutation score must be shown");
    }

    @Test
    void rendersCoverageFindingsSecurityEnvironmentAndLimitations() {
        assertTrue(html.contains("id=\"coverage\""));
        assertTrue(html.contains("jacoco"));
        assertTrue(html.contains("82.0%"));
        assertTrue(html.contains("Per-file line coverage"),
                "per-file coverage must be shown, not only stored");
        assertTrue(html.contains("dev.example.Cart"), "the per-file key must be listed");
        assertTrue(html.contains("91.0%"), "the per-file percentage must be listed");
        assertTrue(html.contains("id=\"findings\""));
        assertTrue(html.contains("SQL_INJECTION"));
        assertTrue(html.contains("User input is concatenated into a SQL statement."));
        assertTrue(html.contains("id=\"security\""));
        assertTrue(html.contains("id=\"environment\""));
        assertTrue(html.contains("17.0.10"));
        assertTrue(html.contains("config-hash"));
        assertTrue(html.contains("generated-tests"), "phase records must be shown");
        assertTrue(html.contains("id=\"limitations\""));
        assertTrue(html.contains(ReportFixtures.LIMITATION), "limitations must be shown");
    }

    @Test
    void perFileCoverageRendersAbsoluteSandboxPathsRelativeToTheProjectRoot() {
        ProjectInfo project = ProjectInfo.builder(ProjectRef.of(Path.of("/sandbox/project")))
                .language(LanguageId.JAVA)
                .build();
        VerificationReport custom = VerificationReport.builder("run-coverage", project, new RequirementSet(List.of()))
                .coverage(new CoverageReport(true, "jacoco", "", 3, 4, 0, 0,
                        Map.of("/sandbox/project/src/a.ts", 100.0, "src/b.ts", 50.0), List.of()))
                .build();

        String rendered = new HtmlReportWriter().render(custom);

        assertFalse(rendered.contains("/sandbox/project/src/a.ts"),
                "an absolute path below the project root must be shown relative to it");
        assertTrue(rendered.contains("<code>src/a.ts</code>"), rendered);
        assertTrue(rendered.contains("<code>src/b.ts</code>"), rendered);
        // Sorted by displayed key: src/a.ts before src/b.ts.
        assertTrue(rendered.indexOf("src/a.ts") < rendered.indexOf("src/b.ts"), rendered);
    }

    @Test
    void rendersCollapsibleEvidenceAppendix() {
        assertTrue(html.contains("id=\"evidence\""));
        assertTrue(html.contains("<details"), "evidence appendix must be collapsible");
        assertTrue(html.contains("EV-001") && html.contains("EV-002") && html.contains("EV-003"));
        assertTrue(html.contains("Tests run: 1, Failures: 0, Errors: 0, Skipped: 0"),
                "evidence output must be shown");
        assertTrue(html.contains("GENERATED_TEST"));
        assertTrue(html.contains("DETERMINISTIC"));
    }

    @Test
    void sectionsAppearInTheRequiredOrder() {
        List<String> sections = List.of("summary", "requirements", "counterexamples", "tests", "mutation",
                "coverage", "findings", "security", "environment", "limitations", "evidence");
        int previous = -1;
        for (String section : sections) {
            int index = html.indexOf("id=\"" + section + "\"");
            assertTrue(index > previous, "section " + section + " is missing or out of order");
            previous = index;
        }
    }

    @Test
    void hostileFindingMessageIsEscaped() {
        assertFalse(html.contains(ReportFixtures.HOSTILE_MESSAGE),
                "untrusted finding message must never appear verbatim");
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"),
                "the message must be rendered escaped");
        assertFalse(html.toLowerCase(Locale.ROOT).contains("<script"),
                "no executable script may be emitted");
    }

    @Test
    void escapesEveryHtmlSignificantCharacter() {
        String html = new HtmlReportWriter().render(
                ReportFixtures.reportWithFindingMessage("A & B < C > D \"quoted\" 'single'"));
        assertTrue(html.contains("A &amp; B &lt; C &gt; D &quot;quoted&quot; &#39;single&#39;"),
                "all five significant characters must be escaped");
    }

    @Test
    void reportIsSelfContained() {
        assertFalse(html.contains("http://"), "no external resource may be referenced");
        assertFalse(html.contains("https://"), "no external resource may be referenced");
        assertFalse(html.contains("<link "), "no external stylesheet may be referenced");
        assertFalse(html.contains("src="), "no external script or image may be referenced");
    }

    @Test
    void writeCreatesTheFile(@TempDir Path directory) throws Exception {
        Path target = directory.resolve("out/report.html");
        Path written = new HtmlReportWriter().write(report, target);

        assertEquals(target, written);
        assertTrue(Files.exists(target), "report file should exist");
        String content = Files.readString(target);
        assertTrue(content.contains("CODEWITNESS"));
        assertTrue(content.contains("✓"));
    }

    @Test
    void emptyReportStillRendersAValidDocument() {
        VerificationReport empty = ReportFixtures.reportWithFindings(List.of(new Finding("analyzer", null,
                Severity.INFO, FindingCategory.UNKNOWN, "nothing of note", null, null, null,
                java.util.Map.of())));
        String rendered = new HtmlReportWriter().render(empty);
        assertTrue(rendered.contains("CODEWITNESS"));
        assertTrue(rendered.contains("&lt;unknown&gt;") || rendered.contains("unknown"));
    }

    @Test
    void reportsVulnerabilitiesOnlyAlongsideTheSourceThatWasConsulted() {
        assertTrue(html.contains("Known vulnerabilities: <strong>1</strong> (source <code>osv</code>"),
                "a count must name the source it came from");
        assertTrue(html.contains("Prototype pollution in the JSON parser."), "the finding must be listed");
    }

    @Test
    void neverClaimsZeroVulnerabilitiesWhenNoSourceWasConsulted() {
        String unchecked = new HtmlReportWriter().render(ReportFixtures.reportWithoutAdvisoryScan());

        assertFalse(unchecked.contains("0 known vulnerabilities"),
                "an unscanned dependency set must not be reported as a clean one");
        assertTrue(unchecked.contains("Known vulnerabilities: <strong>not checked</strong>"), "must say so");
    }
}
