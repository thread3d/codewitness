package dev.codewitness.report;

import dev.codewitness.core.evidence.Evidence;
import dev.codewitness.core.model.AdvisoryScan;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.DependencyVulnerability;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.MutationReport;
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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Renders the human readable CodeWitness report as a single self-contained HTML file.
 *
 * <p>The output embeds all styling inline and requires no JavaScript, no network access and no
 * external resources, so a report can be archived, emailed or opened offline and still render
 * identically.
 *
 * <p>Every value taken from the report - requirement text, source paths, test output, analyzer
 * messages, evidence output - is untrusted input and is HTML escaped before it is written. Nothing
 * is ever emitted raw.
 *
 * <p>Status is never communicated by colour alone: each status is rendered as a symbol plus a text
 * label, and the palette is chosen to stay distinguishable for the most common forms of colour
 * vision deficiency.
 */
public final class HtmlReportWriter {

    /** Colour-blind safe Okabe-Ito palette, paired with symbols and labels. */
    private static final String CSS = """
            :root { color-scheme: light; }
            * { box-sizing: border-box; }
            body { margin: 0; background: #f7f7f5; color: #1b1b1b;
                   font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif;
                   line-height: 1.5; }
            main { max-width: 1080px; margin: 0 auto; padding: 1.5rem 1.25rem 4rem; }
            h1 { font-size: 2rem; letter-spacing: .14em; margin: 0; }
            h2 { font-size: 1.3rem; margin: 2.25rem 0 .75rem; padding-bottom: .35rem;
                 border-bottom: 2px solid #d8d8d2; }
            h3 { font-size: 1.05rem; margin: 1.4rem 0 .4rem; }
            h4 { font-size: .95rem; margin: 1rem 0 .3rem; }
            p { margin: .4rem 0; }
            code, pre { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: .86rem; }
            code { background: #ececE6; padding: .08rem .28rem; border-radius: 3px; }
            pre { background: #1f2933; color: #f2f4f7; padding: .7rem .8rem; border-radius: 6px;
                  overflow-x: auto; white-space: pre-wrap; word-break: break-word; }
            .hero { background: #10323f; color: #ffffff; padding: 1.6rem 1.25rem; }
            .hero .subtitle { margin: .1rem 0 1rem; font-size: 1.05rem; letter-spacing: .06em; color: #cfe6ee; }
            .hero dl { display: grid; grid-template-columns: max-content 1fr; gap: .25rem 1rem; margin: 0; }
            .hero dt { color: #cfe6ee; }
            .hero dd { margin: 0; word-break: break-word; }
            table { border-collapse: collapse; width: 100%; margin: .6rem 0 1rem; background: #ffffff; }
            th, td { border: 1px solid #d8d8d2; padding: .38rem .55rem; text-align: left; vertical-align: top;
                     font-size: .88rem; }
            th { background: #eceae4; font-weight: 600; }
            td.num, th.num { text-align: right; font-variant-numeric: tabular-nums; }
            .card { background: #ffffff; border: 1px solid #d8d8d2; border-radius: 8px;
                    padding: .85rem 1rem 1rem; margin: 1rem 0; }
            .badge { display: inline-block; padding: .1rem .5rem; border-radius: 999px; font-weight: 700;
                     font-size: .82rem; border: 2px solid transparent; white-space: nowrap; }
            .st-verified { background: #d7f0e6; color: #005c43; border-color: #009e73; }
            .st-partially_verified { background: #fbeccf; color: #7a4f00; border-color: #e69f00; }
            .st-failed { background: #fadfd2; color: #8a2f00; border-color: #d55e00; }
            .st-unverified { background: #d9e8f5; color: #00456f; border-color: #0072b2; }
            .st-blocked { background: #f2dceb; color: #6d2b57; border-color: #cc79a7; }
            .sym { font-size: 1.05rem; font-weight: 700; text-align: center; width: 2.2rem; }
            .note { background: #eef4f8; border-left: 4px solid #0072b2; padding: .55rem .75rem; margin: .6rem 0; }
            .warn { background: #fdf1e3; border-left: 4px solid #d55e00; padding: .55rem .75rem; margin: .6rem 0; }
            .ok { background: #e9f6f0; border-left: 4px solid #009e73; padding: .55rem .75rem; margin: .6rem 0; }
            ul, ol { margin: .35rem 0 .6rem 1.2rem; padding: 0; }
            li { margin: .18rem 0; }
            dl.kv { display: grid; grid-template-columns: max-content 1fr; gap: .2rem .9rem; margin: .4rem 0; }
            dl.kv dt { font-weight: 600; color: #40403c; }
            dl.kv dd { margin: 0; word-break: break-word; }
            details { background: #ffffff; border: 1px solid #d8d8d2; border-radius: 6px;
                      padding: .45rem .7rem; margin: .5rem 0; }
            details summary { cursor: pointer; font-weight: 600; }
            details[open] summary { margin-bottom: .5rem; }
            .muted { color: #5b5b56; font-size: .85rem; }
            footer { margin-top: 2.5rem; padding-top: .8rem; border-top: 1px solid #d8d8d2;
                     color: #5b5b56; font-size: .82rem; }
            @media print { body { background: #ffffff; } .hero { background: #10323f; } details { break-inside: avoid; } }
            """;

    /** Creates a stateless HTML report writer. */
    public HtmlReportWriter() {
    }

    /**
     * Renders the report as a self-contained HTML document.
     *
     * @param report report to render
     * @return complete HTML document, never {@code null}
     */
    public String render(VerificationReport report) {
        Objects.requireNonNull(report, "report");
        StringBuilder html = new StringBuilder(64 * 1024);
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>CodeWitness Verification Report - ")
                .append(esc(report.project() != null ? report.project().project().name() : report.runId()))
                .append("</title>\n<style>\n").append(CSS).append("</style>\n</head>\n<body>\n");
        appendHeader(html, report);
        html.append("<main>\n");
        appendExecutiveSummary(html, report);
        appendRequirements(html, report);
        appendCounterexamples(html, report);
        appendTests(html, report);
        appendMutation(html, report);
        appendCoverage(html, report);
        appendFindings(html, report);
        appendSecurity(html, report);
        appendEnvironment(html, report);
        appendLimitations(html, report);
        appendEvidenceAppendix(html, report);
        html.append("<footer>\n<p>Generated by CodeWitness. Every claim in this report is backed by the evidence "
                        + "listed in the appendix. Where evidence is missing, the report says so rather than "
                        + "assuming correctness.</p>\n")
                .append("<p>Run <code>").append(esc(report.runId())).append("</code> &mdash; ")
                .append(esc(report.describe())).append("</p>\n</footer>\n</main>\n</body>\n</html>\n");
        return html.toString();
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
            throw new UncheckedIOException("failed to write HTML report to " + target, e);
        }
        return target;
    }

    // ------------------------------------------------------------------ header & summary

    private void appendHeader(StringBuilder html, VerificationReport report) {
        EnvironmentSnapshot environment = environment(report);
        html.append("<header class=\"hero\">\n")
                .append("<h1>CODEWITNESS</h1>\n")
                .append("<p class=\"subtitle\">Verification Report</p>\n")
                .append("<dl>\n");
        ProjectInfo project = report.project();
        String projectName = project != null ? project.project().name() : "unknown project";
        String projectPath = project != null ? project.project().root().toString() : "unknown";
        html.append("<dt>Project</dt><dd>").append(esc(projectName)).append(" &mdash; <code>")
                .append(esc(projectPath)).append("</code></dd>\n")
                .append("<dt>Run</dt><dd><code>").append(esc(report.runId())).append("</code></dd>\n")
                .append("<dt>Revision</dt><dd>").append(esc(revision(report))).append("</dd>\n")
                .append("<dt>Generated</dt><dd>").append(esc(generatedAt(report).toString())).append("</dd>\n")
                .append("<dt>Overall status</dt><dd>").append(badge(report.overallStatus())).append(" ")
                .append(esc(report.describe())).append("</dd>\n");
        // Which languages were verified. A reader of a polyglot report has to be able to tell, at a glance,
        // what the verdicts below are actually about.
        for (VerificationReport.LanguageSummary language : report.languages()) {
            html.append("<dt>").append(language.verified() ? "Language" : "Not verified").append("</dt><dd>")
                    .append(esc(language.describe())).append("</dd>\n");
        }
        html.append("<dt>Environment</dt><dd>")
                .append(esc(environment != null ? environment.isolationLevel() : "unknown"))
                .append(" isolation</dd>\n")
                .append("</dl>\n</header>\n");
    }

    private void appendExecutiveSummary(StringBuilder html, VerificationReport report) {
        html.append("<section id=\"summary\">\n<h2>Executive summary</h2>\n")
                .append("<p>CodeWitness evaluated <strong>")
                .append(report.verdicts().size()).append("</strong> requirement(s) in run <code>")
                .append(esc(report.runId())).append("</code>. Overall status: ")
                .append(badge(report.overallStatus())).append(".</p>\n")
                .append("<p>").append(esc(report.describe())).append("</p>\n")
                .append("<table class=\"summary-table\">\n<thead><tr><th class=\"sym\">Status</th>"
                        + "<th>Meaning</th><th class=\"num\">Requirements</th></tr></thead>\n<tbody>\n");
        Map<RequirementStatus, Long> counts = report.verdictCounts();
        for (RequirementStatus status : RequirementStatus.values()) {
            html.append("<tr><td class=\"sym\">").append(statusSymbol(status)).append("</td><td>")
                    .append(badge(status)).append(" &mdash; ").append(esc(statusMeaning(status)))
                    .append("</td><td class=\"num\">").append(counts.getOrDefault(status, 0L))
                    .append("</td></tr>\n");
        }
        html.append("</tbody>\n</table>\n");
        html.append("<p class=\"muted\">Status keys: ✓ VERIFIED, ⚠ PARTIALLY_VERIFIED, "
                + "✗ FAILED, ? UNVERIFIED, ⛔ BLOCKED.</p>\n");
        html.append("</section>\n");
    }

    // ------------------------------------------------------------------ requirements

    private void appendRequirements(StringBuilder html, VerificationReport report) {
        html.append("<section id=\"requirements\">\n<h2>Requirements</h2>\n");
        if (report.requirements().isEmpty()) {
            html.append("<p class=\"warn\">No requirements were supplied, so nothing could be verified.</p>\n");
        }
        Map<String, RequirementVerdict> verdicts = new LinkedHashMap<>();
        for (RequirementVerdict verdict : report.verdicts()) {
            verdicts.put(verdict.requirementId(), verdict);
        }
        for (Requirement requirement : report.requirements().requirements()) {
            RequirementVerdict verdict = verdicts.remove(requirement.id());
            appendRequirement(html, requirement, verdict);
        }
        for (RequirementVerdict verdict : verdicts.values()) {
            appendRequirement(html, null, verdict);
        }
        if (!report.requirements().isEmpty() && report.verdicts().isEmpty()) {
            html.append("<p class=\"warn\">No verdicts were recorded for these requirements; "
                    + "they are reported as UNVERIFIED.</p>\n");
        }
        html.append("</section>\n");
    }

    private void appendRequirement(StringBuilder html, Requirement requirement, RequirementVerdict verdict) {
        String id = requirement != null ? requirement.id() : verdict.requirementId();
        RequirementStatus status = verdict != null && verdict.status() != null
                ? verdict.status() : RequirementStatus.UNVERIFIED;
        html.append("<article class=\"card\" id=\"req-").append(esc(id)).append("\">\n")
                .append("<h3><code>").append(esc(id)).append("</code> ")
                .append(esc(requirement != null ? requirement.title() : id)).append(" ")
                .append(badge(status)).append("</h3>\n");
        if (requirement != null) {
            html.append("<p>").append(esc(requirement.description())).append("</p>\n");
            if (requirement.sourceFile() != null) {
                html.append("<p class=\"muted\">Source: <code>").append(esc(requirement.sourceFile()))
                        .append(requirement.sourceLine() > 0 ? ":" + requirement.sourceLine() : "")
                        .append("</code></p>\n");
            }
        }
        html.append("<h4>Rationale</h4>\n<p>")
                .append(esc(verdict != null && verdict.rationale() != null
                        ? verdict.rationale() : "no verdict recorded for this requirement"))
                .append("</p>\n");

        List<ObligationOutcome> outcomes = verdict != null ? verdict.obligations() : List.of();
        Map<String, ObligationOutcome> byId = new LinkedHashMap<>();
        for (ObligationOutcome outcome : outcomes) {
            byId.put(outcome.obligationId(), outcome);
        }
        List<VerificationObligation> obligations = requirement != null ? requirement.obligations() : List.of();
        html.append("<h4>Obligations</h4>\n");
        if (obligations.isEmpty() && byId.isEmpty()) {
            html.append("<p class=\"muted\">No obligations were derived for this requirement.</p>\n");
        } else {
            html.append("<table>\n<thead><tr><th>Obligation</th><th>Description</th><th>Kind</th>"
                            + "<th>Origin</th><th>Status</th><th>Rationale</th><th class=\"num\">Attempts</th>"
                            + "</tr></thead>\n<tbody>\n");
            for (VerificationObligation obligation : obligations) {
                ObligationOutcome outcome = byId.remove(obligation.id());
                appendObligationRow(html, obligation, outcome);
            }
            for (ObligationOutcome outcome : byId.values()) {
                appendObligationRow(html, null, outcome);
            }
            html.append("</tbody>\n</table>\n");
        }

        List<String> unknown = verdict != null ? verdict.unknownAspects() : List.of();
        html.append("<h4>Unknown aspects</h4>\n");
        if (unknown.isEmpty()) {
            html.append("<p class=\"muted\">None recorded.</p>\n");
        } else {
            html.append("<ul>\n");
            for (String aspect : unknown) {
                html.append("<li>").append(esc(aspect)).append("</li>\n");
            }
            html.append("</ul>\n");
        }

        List<String> counterexampleIds = verdict != null ? verdict.counterexampleIds() : List.of();
        List<String> evidenceIds = verdict != null ? verdict.evidenceIds() : List.of();
        html.append("<h4>Evidence and counterexamples</h4>\n")
                .append("<p>Evidence: ").append(list(evidenceIds, true)).append("</p>\n")
                .append("<p>Counterexamples: ").append(list(counterexampleIds, true)).append("</p>\n");
        html.append("</article>\n");
    }

    private void appendObligationRow(StringBuilder html, VerificationObligation obligation, ObligationOutcome outcome) {
        String id = obligation != null ? obligation.id() : outcome.obligationId();
        RequirementStatus status = outcome != null && outcome.status() != null
                ? outcome.status() : RequirementStatus.UNVERIFIED;
        html.append("<tr><td><code>").append(esc(id)).append("</code></td><td>")
                .append(esc(obligation != null ? obligation.description() : ""))
                .append("</td><td>").append(esc(obligation != null && obligation.kind() != null
                        ? obligation.kind().name() : ""))
                .append("</td><td>").append(esc(obligation != null && obligation.origin() != null
                        ? obligation.origin().name() : ""))
                .append("</td><td>").append(badge(status))
                .append("</td><td>").append(esc(outcome != null ? outcome.rationale() : "not attempted"))
                .append("</td><td class=\"num\">").append(outcome != null ? outcome.attempts() : 0)
                .append("</td></tr>\n");
    }

    // ------------------------------------------------------------------ counterexamples

    private void appendCounterexamples(StringBuilder html, VerificationReport report) {
        html.append("<section id=\"counterexamples\">\n<h2>Counterexamples</h2>\n");
        if (report.counterexamples().isEmpty()) {
            html.append("<p class=\"ok\">No counterexample was observed. This means no test or experiment "
                    + "executed by CodeWitness produced a violation; it is not a proof that none exists.</p>\n");
        } else {
            html.append("<p>A counterexample is a concrete input that was <em>observed by execution</em> "
                    + "to violate a requirement. Each one below is reproducible with the command shown.</p>\n");
        }
        for (Counterexample counterexample : report.counterexamples()) {
            html.append("<article class=\"card\">\n<h3><code>").append(esc(counterexample.id()))
                    .append("</code> ").append(esc(counterexample.summary())).append("</h3>\n")
                    .append("<dl class=\"kv\">\n")
                    .append("<dt>Requirement</dt><dd><code>").append(esc(counterexample.requirementId()))
                    .append("</code></dd>\n")
                    .append("<dt>Obligation</dt><dd><code>").append(esc(counterexample.obligationId()))
                    .append("</code></dd>\n")
                    .append("<dt>Observed by</dt><dd>").append(esc(counterexample.observedBy()))
                    .append("</dd>\n")
                    .append("<dt>Evidence</dt><dd><code>").append(esc(counterexample.evidenceId()))
                    .append("</code></dd>\n")
                    .append("<dt>Discovered by</dt><dd>")
                    .append(esc(counterexample.discoveredBy() != null ? counterexample.discoveredBy().name() : ""))
                    .append("</dd>\n")
                    .append("<dt>Seed</dt><dd>")
                    .append(counterexample.seed() != null ? esc(String.valueOf(counterexample.seed())) : "n/a")
                    .append("</dd>\n</dl>\n");

            html.append("<h4>Inputs</h4>\n");
            if (counterexample.inputs().isEmpty()) {
                html.append("<p class=\"muted\">No explicit inputs recorded.</p>\n");
            } else {
                html.append("<table>\n<thead><tr><th>Input</th><th>Value</th></tr></thead>\n<tbody>\n");
                for (Map.Entry<String, Object> input : counterexample.inputs().entrySet()) {
                    html.append("<tr><td><code>").append(esc(input.getKey())).append("</code></td><td><code>")
                            .append(esc(String.valueOf(input.getValue()))).append("</code></td></tr>\n");
                }
                html.append("</tbody>\n</table>\n");
            }

            html.append("<h4>Expected, actual and invariant</h4>\n<dl class=\"kv\">\n")
                    .append("<dt>Expected</dt><dd>").append(esc(counterexample.expected())).append("</dd>\n")
                    .append("<dt>Actual</dt><dd>").append(esc(counterexample.actual())).append("</dd>\n")
                    .append("<dt>Invariant violated</dt><dd>").append(esc(counterexample.invariantViolated()))
                    .append("</dd>\n</dl>\n");

            html.append("<h4>Reproduction</h4>\n");
            Counterexample.ReproductionRecipe recipe = counterexample.reproduction();
            if (recipe == null) {
                html.append("<p class=\"warn\">No reproduction recipe was recorded; this counterexample "
                        + "cannot be replayed as-is.</p>\n");
            } else {
                if (recipe.description() != null && !recipe.description().isBlank()) {
                    html.append("<p>").append(esc(recipe.description())).append("</p>\n");
                }
                html.append("<p>Run:</p>\n<pre><code>").append(esc(recipe.displayCommand()))
                        .append("</code></pre>\n");
                if (!recipe.properties().isEmpty()) {
                    html.append("<p>With properties:</p>\n<pre><code>");
                    for (Map.Entry<String, String> property : recipe.properties().entrySet()) {
                        html.append(esc(property.getKey())).append("=").append(esc(property.getValue()))
                                .append("\n");
                    }
                    html.append("</code></pre>\n");
                }
            }
            html.append("</article>\n");
        }
        html.append("</section>\n");
    }

    // ------------------------------------------------------------------ tests

    private void appendTests(StringBuilder html, VerificationReport report) {
        TestResult tests = report.tests();
        html.append("<section id=\"tests\">\n<h2>Tests</h2>\n");
        if (!tests.executed()) {
            html.append("<p class=\"warn\">Tests were not executed: ").append(esc(tests.note())).append("</p>\n");
        } else if (tests.anyFailure()) {
            html.append("<p class=\"warn\">").append(esc(tests.describe())).append("</p>\n");
        } else {
            html.append("<p class=\"ok\">").append(esc(tests.describe())).append("</p>\n");
        }
        html.append("<table>\n<thead><tr><th>Executed</th><th class=\"num\">Total</th><th class=\"num\">Passed</th>"
                        + "<th class=\"num\">Failed</th><th class=\"num\">Errors</th><th class=\"num\">Skipped</th>"
                        + "<th class=\"num\">Duration (ms)</th></tr></thead>\n<tbody>\n<tr><td>")
                .append(tests.executed() ? "yes" : "no")
                .append("</td><td class=\"num\">").append(tests.total())
                .append("</td><td class=\"num\">").append(tests.passed())
                .append("</td><td class=\"num\">").append(tests.failed())
                .append("</td><td class=\"num\">").append(tests.errors())
                .append("</td><td class=\"num\">").append(tests.skipped())
                .append("</td><td class=\"num\">").append(tests.duration().toMillis())
                .append("</td></tr>\n</tbody>\n</table>\n");
        if (!tests.note().isBlank()) {
            html.append("<p class=\"muted\">Note: ").append(esc(tests.note())).append("</p>\n");
        }
        List<TestCaseResult> failures = tests.failures();
        html.append("<h3>Failing tests</h3>\n");
        if (failures.isEmpty()) {
            html.append("<p class=\"muted\">No failing tests.</p>\n");
        } else {
            html.append("<table>\n<thead><tr><th>Suite</th><th>Test</th><th>Failure type</th>"
                            + "<th>Message</th></tr></thead>\n<tbody>\n");
            for (TestCaseResult failure : failures) {
                html.append("<tr><td><code>").append(esc(failure.suite())).append("</code></td><td><code>")
                        .append(esc(failure.name())).append("</code></td><td>")
                        .append(esc(failure.failureType())).append("</td><td>")
                        .append(esc(failure.failureMessage())).append("</td></tr>\n");
            }
            html.append("</tbody>\n</table>\n");
        }
        html.append("</section>\n");
    }

    // ------------------------------------------------------------------ mutation

    private void appendMutation(StringBuilder html, VerificationReport report) {
        MutationReport mutation = report.mutation();
        html.append("<section id=\"mutation\">\n<h2>Mutation testing</h2>\n");
        html.append("<p class=\"note\">A surviving mutant means <strong>nobody checked</strong> this behaviour: "
                + "the code could be changed without any test failing. It does <strong>not</strong> mean the "
                + "requirement is violated &mdash; CodeWitness found no counterexample, only an untested "
                + "assumption.</p>\n");
        if (!mutation.measured()) {
            html.append("<p class=\"warn\">Mutation testing was not performed: ")
                    .append(esc(mutation.note())).append("</p>\n");
        } else {
            html.append("<p>Tool <code>").append(esc(mutation.tool())).append("</code>; ")
                    .append(esc(mutation.describe())).append("</p>\n");
        }
        html.append("<table>\n<thead><tr><th>Measured</th><th>Tool</th><th class=\"num\">Generated</th>"
                        + "<th class=\"num\">Killed</th><th class=\"num\">Survived</th>"
                        + "<th class=\"num\">No coverage</th><th class=\"num\">Timed out</th>"
                        + "<th class=\"num\">Score</th></tr></thead>\n<tbody>\n<tr><td>")
                .append(mutation.measured() ? "yes" : "no")
                .append("</td><td>").append(esc(mutation.tool()))
                .append("</td><td class=\"num\">").append(mutation.generated())
                .append("</td><td class=\"num\">").append(mutation.killed())
                .append("</td><td class=\"num\">").append(mutation.survived())
                .append("</td><td class=\"num\">").append(mutation.noCoverage())
                .append("</td><td class=\"num\">").append(mutation.timedOut())
                .append("</td><td class=\"num\">").append(percent(mutation.scorePercent()))
                .append("</td></tr>\n</tbody>\n</table>\n");
        if (!mutation.note().isBlank()) {
            html.append("<p class=\"muted\">Note: ").append(esc(mutation.note())).append("</p>\n");
        }
        html.append("<h3>Surviving mutants</h3>\n");
        if (mutation.survivors().isEmpty()) {
            html.append("<p class=\"muted\">No surviving mutants were reported.</p>\n");
        } else {
            html.append("<table>\n<thead><tr><th>Id</th><th>Status</th><th>Mutator</th><th>Location</th>"
                            + "<th>Mutation</th><th>Killing test</th></tr></thead>\n<tbody>\n");
            for (SurvivingMutant mutant : mutation.survivors()) {
                html.append("<tr><td><code>").append(esc(mutant.id())).append("</code></td><td>")
                        .append(esc(mutant.status() != null ? mutant.status().name() : ""))
                        .append("</td><td>").append(esc(mutant.mutator())).append("</td><td><code>")
                        .append(esc(mutant.location())).append("</code></td><td>")
                        .append(esc(mutant.description())).append("</td><td>")
                        .append(mutant.killingTest() == null
                                ? "<span class=\"muted\">none &mdash; nobody checked</span>"
                                : "<code>" + esc(mutant.killingTest()) + "</code>")
                        .append("</td></tr>\n");
            }
            html.append("</tbody>\n</table>\n");
        }
        html.append("</section>\n");
    }

    // ------------------------------------------------------------------ coverage

    private void appendCoverage(StringBuilder html, VerificationReport report) {
        CoverageReport coverage = report.coverage();
        html.append("<section id=\"coverage\">\n<h2>Coverage</h2>\n");
        if (!coverage.measured()) {
            html.append("<p class=\"warn\">Coverage was not measured: ").append(esc(coverage.note()))
                    .append(". Unmeasured coverage is reported as missing evidence, not as full coverage.</p>\n");
        } else {
            html.append("<p>Tool <code>").append(esc(coverage.tool())).append("</code>; ")
                    .append(esc(coverage.describe())).append("</p>\n");
        }
        html.append("<table>\n<thead><tr><th>Measured</th><th>Tool</th><th class=\"num\">Line coverage</th>"
                        + "<th class=\"num\">Lines</th><th class=\"num\">Branch coverage</th>"
                        + "<th class=\"num\">Branches</th></tr></thead>\n<tbody>\n<tr><td>")
                .append(coverage.measured() ? "yes" : "no")
                .append("</td><td>").append(esc(coverage.tool()))
                .append("</td><td class=\"num\">").append(percent(coverage.lineCoveragePercent()))
                .append("</td><td class=\"num\">").append(coverage.lineCovered()).append(" / ")
                .append(coverage.lineTotal())
                .append("</td><td class=\"num\">").append(percent(coverage.branchCoveragePercent()))
                .append("</td><td class=\"num\">").append(coverage.branchCovered()).append(" / ")
                .append(coverage.branchTotal())
                .append("</td></tr>\n</tbody>\n</table>\n");
        if (!coverage.note().isBlank()) {
            html.append("<p class=\"muted\">Note: ").append(esc(coverage.note())).append("</p>\n");
        }
        // Per-file coverage is computed and stored by several adapters, so it is shown here rather than
        // only reaching SQLite. The table says what it lists and how its keys are displayed.
        if (!coverage.perClassLineCoverage().isEmpty()) {
            html.append("<h3>Per-file line coverage</h3>\n")
                    .append("<p class=\"muted\">Line coverage for each file or class the coverage tool ")
                    .append("reported. Keys are shown relative to the project root when the report records ")
                    .append("an absolute path below it, and exactly as recorded otherwise.</p>\n")
                    .append("<table>\n<thead><tr><th>File or class</th><th class=\"num\">Line coverage</th>")
                    .append("</tr></thead>\n<tbody>\n");
            coverage.perClassLineCoverage().entrySet().stream()
                    .map(entry -> Map.entry(displayPath(report, entry.getKey()), entry.getValue()))
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> html.append("<tr><td><code>").append(esc(entry.getKey()))
                            .append("</code></td><td class=\"num\">").append(percent(entry.getValue()))
                            .append("</td></tr>\n"));
            html.append("</tbody>\n</table>\n");
        }
        html.append("</section>\n");
    }

    // ------------------------------------------------------------------ findings & security

    private void appendFindings(StringBuilder html, VerificationReport report) {
        html.append("<section id=\"findings\">\n<h2>Static analysis findings</h2>\n");
        if (report.findings().isEmpty()) {
            html.append("<p class=\"muted\">No static analysis findings were reported.</p>\n");
        } else {
            html.append("<table>\n<thead><tr><th>Severity</th><th>Category</th><th>File</th>"
                            + "<th class=\"num\">Line</th><th>Analyzer</th><th>Rule</th><th>Message</th>"
                            + "</tr></thead>\n<tbody>\n");
            for (Finding finding : report.findings()) {
                html.append("<tr><td>").append(esc(finding.severity().name())).append("</td><td>")
                        .append(esc(finding.category().name())).append("</td><td><code>")
                        .append(esc(finding.file() == null ? "<unknown>" : finding.file())).append("</code></td>")
                        .append("<td class=\"num\">")
                        .append(finding.line() == null ? "" : finding.line())
                        .append("</td><td>").append(esc(finding.analyzer())).append("</td><td>")
                        .append(esc(finding.ruleId())).append("</td><td>").append(esc(finding.message()))
                        .append("</td></tr>\n");
            }
            html.append("</tbody>\n</table>\n");
        }
        html.append("</section>\n");
    }

    private void appendSecurity(StringBuilder html, VerificationReport report) {
        Map<Severity, Integer> counts = new LinkedHashMap<>();
        for (Severity severity : Severity.values()) {
            counts.put(severity, 0);
        }
        int total = 0;
        for (Finding finding : report.findings()) {
            if (finding.category() == FindingCategory.SECURITY) {
                counts.merge(finding.severity(), 1, Integer::sum);
                total++;
            }
        }
        html.append("<section id=\"security\">\n<h2>Security</h2>\n")
                .append("<p>Security findings are the subset of static analysis findings whose category is "
                        + "<code>SECURITY</code>.</p>\n")
                .append("<table>\n<thead><tr><th>Critical</th><th>High</th><th>Medium</th><th>Low</th>"
                        + "<th>Info</th><th>Total</th></tr></thead>\n<tbody>\n<tr>");
        for (Severity severity : Severity.values()) {
            html.append("<td class=\"num\">").append(counts.get(severity)).append("</td>");
        }
        html.append("<td class=\"num\"><strong>").append(total).append("</strong></td></tr>\n</tbody>\n</table>\n");
        DependencyReport dependencies = report.dependencies();
        html.append("<h3>Dependencies</h3>\n");
        if (!dependencies.analysed()) {
            html.append("<p class=\"warn\">Dependency analysis did not run: ").append(esc(dependencies.note()))
                    .append("</p>\n");
        } else {
            html.append("<p>Tool <code>").append(esc(dependencies.tool())).append("</code>: ")
                    .append(dependencies.totalDependencies()).append(" dependencies.</p>\n");
            // Never state a vulnerability count without saying whether anything was looked for: "0 known
            // vulnerabilities" reads as a clean bill of health, and a run that consulted nothing has not
            // earned the right to say it.
            AdvisoryScan scan = dependencies.advisoryScan();
            if (scan.performed()) {
                html.append("<p>Known vulnerabilities: <strong>").append(dependencies.vulnerabilities().size())
                        .append("</strong> (source <code>").append(esc(scan.source())).append("</code>");
                if (scan.performedAt() != null) {
                    html.append(", checked ").append(esc(scan.performedAt().toString()));
                }
                html.append(")</p>\n");
            } else {
                html.append("<p class=\"warn\">Known vulnerabilities: <strong>not checked</strong> &mdash; ")
                        .append(esc(scan.note())).append("</p>\n");
            }
        }
        if (!dependencies.vulnerabilities().isEmpty()) {
            html.append("<table>\n<thead><tr><th>Dependency</th><th>Version</th><th>Advisory</th>"
                            + "<th>Severity</th><th>Description</th><th>Source</th></tr></thead>\n<tbody>\n");
            for (DependencyVulnerability vulnerability : dependencies.vulnerabilities()) {
                html.append("<tr><td><code>").append(esc(vulnerability.dependency())).append("</code></td><td>")
                        .append(esc(vulnerability.version())).append("</td><td>")
                        .append(esc(vulnerability.vulnerabilityId())).append("</td><td>")
                        .append(esc(vulnerability.severity() != null ? vulnerability.severity().name() : ""))
                        .append("</td><td>").append(esc(vulnerability.description())).append("</td><td>")
                        .append(esc(vulnerability.source())).append("</td></tr>\n");
            }
            html.append("</tbody>\n</table>\n");
        }
        if (!dependencies.advisoryScan().note().isBlank()) {
            html.append("<p class=\"muted\">Advisory scan: ").append(esc(dependencies.advisoryScan().note()))
                    .append("</p>\n");
        }
        if (!dependencies.note().isBlank()) {
            html.append("<p class=\"muted\">Note: ").append(esc(dependencies.note())).append("</p>\n");
        }
        html.append("</section>\n");
    }

    // ------------------------------------------------------------------ environment

    private void appendEnvironment(StringBuilder html, VerificationReport report) {
        EnvironmentSnapshot environment = environment(report);
        VerificationRun run = report.run();
        html.append("<section id=\"environment\">\n<h2>Environment and reproducibility</h2>\n");
        if (environment == null) {
            html.append("<p class=\"warn\">No environment snapshot was recorded for this run, so it cannot be "
                    + "reproduced exactly.</p>\n");
        }
        html.append("<dl class=\"kv\">\n");
        kv(html, "CodeWitness version", environment != null ? environment.codewitnessVersion() : null);
        kv(html, "Java version", environment != null ? environment.javaVersion() : null);
        kv(html, "Java vendor", environment != null ? environment.javaVendor() : null);
        kv(html, "Operating system", environment != null ? environment.operatingSystem() : null);
        kv(html, "Build tool", environment != null ? environment.buildTool() : null);
        kv(html, "Build tool version", environment != null ? environment.buildToolVersion() : null);
        kv(html, "Source revision", environment != null ? environment.sourceRevision() : null);
        kv(html, "Requirements hash", environment != null ? environment.requirementsHash() : null);
        kv(html, "Configuration hash", environment != null ? environment.configurationHash() : null);
        kv(html, "LLM model", environment != null ? environment.llmModel() : null);
        kv(html, "Isolation level", environment != null ? environment.isolationLevel() : null);
        kv(html, "Sandbox", environment != null ? environment.sandboxDescription() : null);
        Instant startedAt = environment != null && environment.startedAt() != null
                ? environment.startedAt() : (run != null ? run.startedAt() : null);
        Instant finishedAt = environment != null && environment.finishedAt() != null
                ? environment.finishedAt() : (run != null ? run.finishedAt() : null);
        kv(html, "Started at", startedAt != null ? startedAt.toString() : null);
        kv(html, "Finished at", finishedAt != null ? finishedAt.toString() : null);
        html.append("</dl>\n");

        html.append("<h3>Phases</h3>\n");
        if (run == null || run.phases().isEmpty()) {
            html.append("<p class=\"muted\">No phase records were reported.</p>\n");
        } else {
            html.append("<table>\n<thead><tr><th>Phase</th><th>Executed</th><th>Success</th>"
                            + "<th class=\"num\">Duration (ms)</th><th>Summary</th><th>Evidence</th>"
                            + "<th>Note</th></tr></thead>\n<tbody>\n");
            for (PhaseRecord phase : run.phases()) {
                html.append("<tr><td><code>").append(esc(phase.phase())).append("</code></td><td>")
                        .append(phase.executed() ? "yes" : "no").append("</td><td>")
                        .append(phase.success() ? "yes" : "no").append("</td><td class=\"num\">")
                        .append(phase.duration() != null ? phase.duration().toMillis() : 0L).append("</td><td>")
                        .append(esc(phase.summary())).append("</td><td>")
                        .append(list(phase.evidenceIds(), false)).append("</td><td>")
                        .append(esc(phase.note())).append("</td></tr>\n");
            }
            html.append("</tbody>\n</table>\n");
        }

        html.append("<h3>Budget</h3>\n");
        if (run == null) {
            html.append("<p class=\"muted\">No budget information was reported.</p>\n");
        } else {
            Budget budget = run.budget();
            BudgetUsage usage = run.usage();
            html.append("<table>\n<thead><tr><th>Limit</th><th>Value</th></tr></thead>\n<tbody>\n");
            if (budget != null) {
                row(html, "Max duration (s)", String.valueOf(budget.maxDuration().toSeconds()));
                row(html, "Max test generation iterations",
                        String.valueOf(budget.maxTestGenerationIterations()));
                row(html, "Max generated tests", String.valueOf(budget.maxGeneratedTests()));
                row(html, "Max mutation time (s)", String.valueOf(budget.maxMutationTime().toSeconds()));
                row(html, "Max LLM calls", String.valueOf(budget.maxLlmCalls()));
            }
            if (usage != null) {
                row(html, "Elapsed (s)", String.format(Locale.ROOT, "%.3f", usage.elapsed().toMillis() / 1000.0));
                row(html, "Generated tests", String.valueOf(usage.generatedTests()));
                row(html, "LLM calls", String.valueOf(usage.llmCalls()));
                row(html, "Exhausted", usage.exhausted() ? "yes" : "no");
                row(html, "Exhausted because", usage.exhaustedReason());
            }
            html.append("</tbody>\n</table>\n");
            if (usage != null && usage.exhausted()) {
                html.append("<p class=\"warn\">The run stopped early because a budget limit was reached"
                        + (usage.exhaustedReason() != null ? ": " + esc(usage.exhaustedReason()) : "")
                        + ". Results are therefore incomplete.</p>\n");
            }
        }
        html.append("</section>\n");
    }

    // ------------------------------------------------------------------ limitations

    private void appendLimitations(StringBuilder html, VerificationReport report) {
        html.append("<section id=\"limitations\">\n<h2>Limitations &mdash; things CodeWitness could NOT verify</h2>\n");
        if (report.limitations().isEmpty()) {
            html.append("<p class=\"muted\">No limitations were recorded. The absence of a recorded limitation "
                    + "is not proof that verification was complete.</p>\n");
        } else {
            html.append("<ul>\n");
            for (String limitation : report.limitations()) {
                html.append("<li>").append(esc(limitation)).append("</li>\n");
            }
            html.append("</ul>\n");
        }
        html.append("</section>\n");
    }

    // ------------------------------------------------------------------ evidence appendix

    private void appendEvidenceAppendix(StringBuilder html, VerificationReport report) {
        html.append("<section id=\"evidence\">\n<h2>Evidence appendix</h2>\n")
                .append("<p>Every conclusion above points at one of these records. Expand an entry to see the "
                        + "exact command, output and provenance.</p>\n");
        if (report.evidence().isEmpty()) {
            html.append("<p class=\"warn\">No evidence was recorded, so no claim in this report is backed by "
                    + "an observation.</p>\n");
        }
        for (Evidence evidence : report.evidence()) {
            html.append("<details id=\"ev-").append(esc(evidence.id())).append("\">\n<summary><code>")
                    .append(esc(evidence.id())).append("</code> &mdash; ")
                    .append(esc(evidence.type() != null ? evidence.type().name() : ""))
                    .append(" &middot; ").append(esc(evidence.status() != null ? evidence.status().name() : ""))
                    .append(" &middot; ")
                    .append(esc(evidence.confidence() != null ? evidence.confidence().name() : ""))
                    .append("</summary>\n")
                    .append("<dl class=\"kv\">\n");
            kv(html, "Description", evidence.description());
            kv(html, "Type", evidence.type() != null ? evidence.type().name() : null);
            kv(html, "Status", evidence.status() != null ? evidence.status().name() : null);
            kv(html, "Confidence", evidence.confidence() != null ? evidence.confidence().name() : null);
            kv(html, "Source", evidence.source());
            kv(html, "Timestamp", evidence.timestamp() != null ? evidence.timestamp().toString() : null);
            kv(html, "Command", evidence.command().isEmpty() ? null : String.join(" ", evidence.command()));
            kv(html, "Requirements", evidence.requirementIds().isEmpty() ? null
                    : String.join(", ", evidence.requirementIds()));
            kv(html, "Obligations", evidence.obligationIds().isEmpty() ? null
                    : String.join(", ", evidence.obligationIds()));
            kv(html, "Artifacts", evidence.artifacts().isEmpty() ? null
                    : evidence.artifacts().stream().map(Path::toString).reduce((a, b) -> a + ", " + b).orElse(""));
            kv(html, "Generated by LLM", evidence.generatedByLlm() ? "yes" : "no");
            kv(html, "Executed independently", evidence.executedIndependently() ? "yes" : "no");
            kv(html, "Reproducible", evidence.reproducible() ? "yes" : "no");
            html.append("</dl>\n");
            if (!evidence.inputs().isEmpty()) {
                html.append("<h4>Inputs</h4>\n<table>\n<thead><tr><th>Input</th><th>Value</th></tr></thead>\n"
                        + "<tbody>\n");
                for (Map.Entry<String, Object> input : evidence.inputs().entrySet()) {
                    html.append("<tr><td><code>").append(esc(input.getKey())).append("</code></td><td><code>")
                            .append(esc(String.valueOf(input.getValue()))).append("</code></td></tr>\n");
                }
                html.append("</tbody>\n</table>\n");
            }
            html.append("<h4>Output</h4>\n<pre><code>")
                    .append(esc(evidence.output().isBlank() ? "(no output recorded)" : evidence.output()))
                    .append("</code></pre>\n");
            if (!evidence.result().isBlank()) {
                html.append("<p><strong>Result:</strong> ").append(esc(evidence.result())).append("</p>\n");
            }
            if (!evidence.metadata().isEmpty()) {
                html.append("<h4>Metadata</h4>\n<table>\n<thead><tr><th>Key</th><th>Value</th></tr></thead>\n"
                        + "<tbody>\n");
                for (Map.Entry<String, Object> entry : evidence.metadata().entrySet()) {
                    html.append("<tr><td><code>").append(esc(entry.getKey())).append("</code></td><td><code>")
                            .append(esc(String.valueOf(entry.getValue()))).append("</code></td></tr>\n");
                }
                html.append("</tbody>\n</table>\n");
            }
            html.append("</details>\n");
        }
        html.append("</section>\n");
    }

    // ------------------------------------------------------------------ helpers

    private void kv(StringBuilder html, String key, String value) {
        html.append("<dt>").append(esc(key)).append("</dt><dd>")
                .append(esc(value == null || value.isBlank() ? "unknown" : value))
                .append("</dd>\n");
    }

    private void row(StringBuilder html, String key, String value) {
        html.append("<tr><td>").append(esc(key)).append("</td><td><code>")
                .append(esc(value == null || value.isBlank() ? "n/a" : value))
                .append("</code></td></tr>\n");
    }

    private String list(List<String> values, boolean asCode) {
        if (values == null || values.isEmpty()) {
            return "<span class=\"muted\">none</span>";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(asCode ? "<code>" + esc(values.get(i)) + "</code>" : esc(values.get(i)));
        }
        return sb.toString();
    }

    private String badge(RequirementStatus status) {
        RequirementStatus effective = status != null ? status : RequirementStatus.UNVERIFIED;
        return "<span class=\"badge st-" + statusClass(effective) + "\">" + statusSymbol(effective) + " "
                + effective.name() + "</span>";
    }

    private static String statusClass(RequirementStatus status) {
        return status.name().toLowerCase(Locale.ROOT);
    }

    private static String statusSymbol(RequirementStatus status) {
        return switch (status) {
            case VERIFIED -> "✓";
            case PARTIALLY_VERIFIED -> "⚠";
            case FAILED -> "✗";
            case UNVERIFIED -> "?";
            case BLOCKED -> "⛔";
        };
    }

    private static String statusMeaning(RequirementStatus status) {
        return switch (status) {
            case VERIFIED -> "independent evidence supports the requirement";
            case PARTIALLY_VERIFIED -> "some aspects have evidence, significant cases remain unverified";
            case FAILED -> "an executed counterexample violates the requirement";
            case UNVERIFIED -> "no evidence could establish the requirement either way";
            case BLOCKED -> "verification could not proceed because of a project or infrastructure problem";
        };
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value);
    }

    /**
     * Renders a coverage key relative to the project root when it is an absolute path below it.
     *
     * <p>This is a pure string operation on a path the report already holds: no symlink resolution, no
     * existence check and no guessing, so it can only shorten a path that is genuinely below the root.
     * Anything else is returned unchanged rather than invented. Several adapters key per-file coverage
     * by the absolute sandbox path the tool printed, which is what this fixes at render time only.
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

    private static EnvironmentSnapshot environment(VerificationReport report) {
        VerificationRun run = report.run();
        return run != null ? run.environment() : null;
    }

    private static String revision(VerificationReport report) {
        EnvironmentSnapshot environment = environment(report);
        String revision = environment != null ? environment.sourceRevision() : null;
        return revision == null || revision.isBlank() ? "unknown" : revision;
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
     * Escapes a value for both element text and quoted attribute contexts.
     *
     * <p>Report content is untrusted: requirement text comes from a file, analyzer messages and test
     * output come from tools run against arbitrary code. All five HTML significant characters are
     * escaped so that no value can break out of the element or attribute it is written into.
     *
     * @param value value to escape, may be {@code null}
     * @return escaped text, never {@code null}
     */
    private static String esc(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
