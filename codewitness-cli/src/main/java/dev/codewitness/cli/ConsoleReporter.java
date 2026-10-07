package dev.codewitness.cli;

import dev.codewitness.core.model.Severity;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.RequirementStatus;
import dev.codewitness.core.result.Counterexample;
import dev.codewitness.core.result.RequirementVerdict;
import dev.codewitness.core.result.VerificationReport;

import java.util.Locale;
import java.util.Map;

/**
 * Renders the console summary.
 *
 * <p>The shape follows the same principle as the HTML report: counts of things that were actually
 * observed, never a single "correctness percentage". Percentages appear only where the underlying
 * numbers are shown next to them.
 */
public final class ConsoleReporter {

    private ConsoleReporter() {
    }

    public static String render(VerificationReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("CODEWITNESS  verification report\n");
        sb.append("run          ").append(report.runId()).append('\n');
        sb.append("project      ").append(report.project().project().name())
                .append(" (").append(report.project().buildSystem().id())
                .append(", ").append(report.project().language().id()).append(")\n");
        sb.append("revision     ").append(report.run().environment().sourceRevision()).append('\n');
        sb.append("overall      ").append(report.overallStatus()).append('\n');
        sb.append("isolation    ").append(report.run().environment().isolationLevel()).append('\n');

        sb.append("\nRequirements\n");
        if (report.verdicts().isEmpty()) {
            sb.append("  (none: no requirements were supplied)\n");
        }
        for (RequirementVerdict verdict : report.verdicts()) {
            String title = report.requirements().byId(verdict.requirementId())
                    .map(Requirement::title).orElse("");
            sb.append(String.format(Locale.ROOT, "  %s %-8s %-28s %s%n",
                    CliSupport.marker(verdict.status()), verdict.requirementId(),
                    truncate(title, 28), verdict.status()));
            if (verdict.status() != RequirementStatus.VERIFIED) {
                sb.append("      ").append(wrap(verdict.rationale(), 100, "      ")).append('\n');
            }
        }

        Map<RequirementStatus, Long> counts = report.verdictCounts();
        sb.append("\nCounts\n");
        for (RequirementStatus status : RequirementStatus.values()) {
            sb.append("  ").append(CliSupport.marker(status)).append(' ')
                    .append(String.format(Locale.ROOT, "%-20s %d%n", status, counts.getOrDefault(status, 0L)));
        }

        sb.append("\nTests\n  ").append(report.tests().describe()).append('\n');
        sb.append("\nCoverage\n  ").append(report.coverage().describe()).append('\n');
        sb.append("\nMutation testing\n  ").append(report.mutation().describe()).append('\n');

        sb.append("\nStatic analysis\n  ").append(report.findings().size()).append(" finding(s): ");
        Map<Severity, Long> bySeverity = report.findingsBySeverity();
        sb.append(bySeverity.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(e -> e.getValue() + " " + e.getKey().name().toLowerCase(Locale.ROOT))
                .reduce((a, b) -> a + ", " + b).orElse("none")).append('\n');

        sb.append("\nDependencies\n  ").append(report.dependencies().analysed()
                ? report.dependencies().totalDependencies() + " dependencies ("
                        + report.dependencies().note() + ")"
                : "not analysed (" + report.dependencies().note() + ")").append('\n');
        // Say whether anything was looked for, so the count above is not read as a clean scan.
        sb.append("  known vulnerabilities: ").append(report.dependencies().advisoryScan().performed()
                ? report.dependencies().vulnerabilities().size() + " ("
                        + report.dependencies().advisoryScan().source() + ")"
                : "not checked (" + report.dependencies().advisoryScan().note() + ")").append('\n');

        sb.append("\nCounterexamples\n");
        if (report.counterexamples().isEmpty()) {
            sb.append("  none observed\n");
        }
        for (Counterexample counterexample : report.counterexamples()) {
            sb.append("  ").append(counterexample.id()).append("  ").append(counterexample.requirementId())
                    .append("  ").append(truncate(counterexample.summary(), 80)).append('\n');
            sb.append("      inputs:   ").append(counterexample.inputs()).append('\n');
            sb.append("      expected: ").append(truncate(counterexample.expected(), 100)).append('\n');
            sb.append("      actual:   ").append(truncate(counterexample.actual(), 100)).append('\n');
            if (counterexample.reproduction() != null) {
                sb.append("      reproduce: ").append(counterexample.reproduction().displayCommand()).append('\n');
            }
        }

        sb.append("\nUnknown / limitations\n");
        if (report.limitations().isEmpty()) {
            sb.append("  none recorded\n");
        }
        for (String limitation : report.limitations()) {
            sb.append("  - ").append(wrap(limitation, 100, "    ")).append('\n');
        }
        sb.append("\nElapsed ").append(CliSupport.duration(report.run().usage().elapsed()))
                .append(", generated tests ").append(report.run().usage().generatedTests())
                .append(", LLM calls ").append(report.run().usage().llmCalls()).append('\n');
        return sb.toString();
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String single = text.replaceAll("\\s+", " ").strip();
        return single.length() <= max ? single : single.substring(0, max - 1) + "\u2026";
    }

    private static String wrap(String text, int width, String indent) {
        String single = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        StringBuilder sb = new StringBuilder();
        int lineLength = 0;
        for (String word : single.split(" ")) {
            if (lineLength + word.length() + 1 > width) {
                sb.append('\n').append(indent).append(word).append(' ');
                lineLength = word.length() + 1;
            } else {
                sb.append(word).append(' ');
                lineLength += word.length() + 1;
            }
        }
        return sb.toString().stripTrailing();
    }
}
