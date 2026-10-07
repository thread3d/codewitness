package dev.codewitness.ruby.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.ruby.RubyTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Ruby heuristic and security rule table.
 *
 * <p>Every assertion here is one-directional on purpose: a rule must report the snippet it is written for,
 * and must report nothing at all for a file that contains none of its patterns. A rule that matched nothing
 * produces no pseudo-finding saying it was checked, because a report that says "checked and clean" about a
 * pattern that never ran is exactly the kind of unobserved success this project refuses.
 *
 * <p>Two rules are only applicable to some files or some projects - console output in library code, and
 * Rails' escaping switches - so the tests state where each applies and, just as importantly, where it does
 * not.
 */
class RubyHeuristicAnalyzerTest {

    private static boolean hasRule(List<Finding> findings, String ruleId) {
        return findings.stream().anyMatch(finding -> ruleId.equals(finding.ruleId()));
    }

    private static long countOf(List<Finding> findings, String ruleId) {
        return findings.stream().filter(finding -> ruleId.equals(finding.ruleId())).count();
    }

    @Test
    @DisplayName("a nil comparison and an and/or assignment are reported as correctness findings")
    void reportsCorrectnessHeuristics(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        RubyTestContexts.write(root, "ledger.rb", """
                def update(row, save)
                  if row == nil
                    return
                  end
                  ok = save or raise 'failed'
                  ok
                end
                """);
        VerificationContext context = RubyTestContexts.context(root, runDirectory);

        List<Finding> findings = new RubyHeuristicAnalyzer().analyze(context);

        assertTrue(hasRule(findings, "CW-RB-CORRECT-001"),
                "comparing with nil using == must be reported: " + findings);
        assertTrue(hasRule(findings, "CW-RB-BUG-001"),
                "an assignment that then uses or binds looser than = must be reported: " + findings);
        assertTrue(findings.stream().allMatch(finding -> finding.file().endsWith("ledger.rb")),
                "every finding must name the file it came from: " + findings);
    }

    @Test
    @DisplayName("a bare rescue, a global, an assignment in a condition, a fixed sleep and a puts are reported")
    void reportsReliabilityHeuristics(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        RubyTestContexts.write(root, "lib/ledger.rb", """
                def load_value(file, total, count)
                  $cache = read(file)
                  puts value
                  sleep 5
                  value = $cache
                  if (value = fetch(name))
                    return value
                  end
                  total / count
                rescue Exception => error
                  raise error
                end
                """);
        VerificationContext context = RubyTestContexts.context(root, runDirectory);

        List<Finding> findings = new RubyHeuristicAnalyzer().analyze(context);

        assertTrue(hasRule(findings, "CW-RB-REL-001"),
                "rescuing Exception, or with no class at all, must be reported: " + findings);
        assertTrue(hasRule(findings, "CW-RB-REL-004"), "a global assignment must be reported: " + findings);
        assertTrue(hasRule(findings, "CW-RB-REL-006"),
                "an assignment inside a condition must be reported: " + findings);
        assertTrue(hasRule(findings, "CW-RB-REL-007"),
                "a hard-coded sleep must be reported: " + findings);
        assertTrue(hasRule(findings, "CW-RB-STYLE-001"),
                "a puts from library code must be reported: " + findings);
    }

    @Test
    @DisplayName("a swallowed rescue, an unbounded loop, a leaked file handle and a bare method_missing")
    void reportsStructuralRules(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        RubyTestContexts.write(root, "ledger.rb", """
                def handle(stream)
                  begin
                    process(stream)
                  rescue
                  end
                  File.open('log.txt')
                  while true
                    work
                  end
                end

                def method_missing(name, *args)
                  nil
                end
                """);
        VerificationContext context = RubyTestContexts.context(root, runDirectory);

        List<Finding> findings = new RubyHeuristicAnalyzer().analyze(context);

        assertTrue(hasRule(findings, "CW-RB-REL-002"),
                "a rescue whose body is empty discards the failure: " + findings);
        assertTrue(hasRule(findings, "CW-RB-REL-003"),
                "a while true with no break or return runs until the process is killed: " + findings);
        assertTrue(hasRule(findings, "CW-RB-REL-005"),
                "File.open without a block leaks the handle: " + findings);
        assertTrue(hasRule(findings, "CW-RB-BUG-002"),
                "method_missing without respond_to_missing? makes respond_to? answer false: " + findings);
    }

    @Test
    @DisplayName("a loop with a way out, a block-formed File.open and a paired method_missing are not reported")
    void doesNotReportTheCleanVersionOfEachStructuralRule(@TempDir Path root, @TempDir Path runDirectory)
            throws IOException {
        RubyTestContexts.write(root, "ledger.rb", """
                def handle(stream)
                  while true
                    break if done?(stream)
                  end
                  File.open('log.txt') { |file| file.read }
                  stream
                end

                def method_missing(name, *args)
                  nil
                end

                def respond_to_missing?(name, include_private = false)
                  true
                end
                """);
        VerificationContext context = RubyTestContexts.context(root, runDirectory);

        List<Finding> findings = new RubyHeuristicAnalyzer().findAll(context);

        assertFalse(hasRule(findings, "CW-RB-REL-003"),
                "a loop that leaves through break is not unbounded: " + findings);
        assertFalse(hasRule(findings, "CW-RB-REL-005"),
                "a File.open given a block closes the file even when the body raises: " + findings);
        assertFalse(hasRule(findings, "CW-RB-BUG-002"),
                "the companion method is what the rule asks for: " + findings);
    }

    @Test
    @DisplayName("a method or a class past its line limit is reported as a maintainability finding")
    void reportsLongDeclarations(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        StringBuilder source = new StringBuilder("def big\n");
        for (int i = 0; i < 45; i++) {
            source.append("  step").append(i).append("\n");
        }
        source.append("end\n\nclass Giant\n");
        for (int i = 0; i < 260; i++) {
            source.append("  value").append(i).append(" = ").append(i).append("\n");
        }
        source.append("end\n");
        RubyTestContexts.write(root, "ledger.rb", source.toString());
        VerificationContext context = RubyTestContexts.context(root, runDirectory);

        List<Finding> findings = new RubyHeuristicAnalyzer().analyze(context);

        assertTrue(hasRule(findings, "CW-RB-MAIN-002"),
                "a method past the 40-line limit must be reported: " + findings);
        assertTrue(hasRule(findings, "CW-RB-MAIN-003"),
                "a class past the 250-line limit must be reported: " + findings);
        assertTrue(findings.stream()
                        .filter(finding -> "CW-RB-MAIN-002".equals(finding.ruleId()))
                        .allMatch(finding -> finding.attributes().containsKey("lines")),
                "the finding states the measured size, so a reader can judge the report: " + findings);
    }

    @Test
    @DisplayName("a TODO marker is read from the raw file, which is the one documented exception")
    void reportsTheFirstPendingTag(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        // The markers live in comments, and every projection this adapter has blanks comments, so the rule
        // has to read the file itself; matching a marker inside a string literal is the price of that.
        RubyTestContexts.write(root, "ledger.rb", """
                # TODO: decide the rounding rule
                value = "FIXME: also in a literal"
                """);
        VerificationContext context = RubyTestContexts.context(root, runDirectory);

        List<Finding> findings = new RubyHeuristicAnalyzer().analyze(context);

        assertEquals(1, countOf(findings, "CW-RB-MAIN-001"),
                "a file is reported at the first marker, once: " + findings);
        Finding tag = findings.stream()
                .filter(finding -> "CW-RB-MAIN-001".equals(finding.ruleId()))
                .findFirst()
                .orElseThrow();
        assertEquals(1, tag.line(), findings.toString());
        assertEquals("TODO", tag.attributes().get("tag"), tag.attributes().toString());
    }

    @Test
    @DisplayName("a rule reports at most one finding per file, however many times it matches")
    void reportsOneFindingPerRulePerFile(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        RubyTestContexts.write(root, "lib/ledger.rb", """
                def compare(a, b, note)
                  if a == nil
                    puts note
                  end
                  if b == nil
                    puts note
                  end
                  a == nil || b == nil
                end
                """);
        VerificationContext context = RubyTestContexts.context(root, runDirectory);

        List<Finding> findings = new RubyHeuristicAnalyzer().analyze(context);

        assertEquals(1, countOf(findings, "CW-RB-CORRECT-001"),
                "a pattern that matches four lines is one decision about the file: " + findings);
        assertEquals(1, countOf(findings, "CW-RB-STYLE-001"), findings.toString());
    }

    @Test
    @DisplayName("the console-output rule applies to library code, not to a script or a rake task")
    void theConsoleOutputRuleIsGatedOnLibrarySource(@TempDir Path root, @TempDir Path runDirectory)
            throws IOException {
        Path library = root.resolve("library");
        RubyTestContexts.write(library, "lib/ledger.rb", "def report(total)\n  puts total\nend\n");
        Path railsish = root.resolve("railsish");
        RubyTestContexts.write(railsish, "app/ledger.rb", "def report(total)\n  puts total\nend\n");
        Path script = root.resolve("script");
        RubyTestContexts.write(script, "ledger.rb", "def report(total)\n  puts total\nend\n");

        assertEquals(List.of("CW-RB-STYLE-001"),
                new RubyHeuristicAnalyzer().analyze(RubyTestContexts.context(library, runDirectory))
                        .stream().map(Finding::ruleId).toList(),
                "a library that owns the console cannot be used by a program that owns it too");
        assertTrue(hasRule(new RubyHeuristicAnalyzer()
                        .analyze(RubyTestContexts.context(railsish, runDirectory)), "CW-RB-STYLE-001"),
                "app/ is library code too");
        assertTrue(new RubyHeuristicAnalyzer().analyze(RubyTestContexts.context(script, runDirectory))
                        .isEmpty(),
                "a script exists to print, so the same line is not a defect there");
    }

    @Test
    @DisplayName("the XSS rule applies only to a Rails-like project, where raw and html_safe mean escaping")
    void theXssRuleIsGatedOnRails(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        String helper = """
                def badge(name)
                  raw("<b>#{name}</b>")
                end
                """;
        Path railsProject = root.resolve("rails-project");
        RubyTestContexts.write(railsProject, "Gemfile", "gem \"rails\", \"~> 7.1\"\n");
        RubyTestContexts.write(railsProject, "app/helpers/ledger_helper.rb", helper);
        Path plainProject = root.resolve("plain-project");
        RubyTestContexts.write(plainProject, "app/helpers/ledger_helper.rb", helper);

        assertTrue(hasRule(new RubyHeuristicAnalyzer().findAll(
                        RubyTestContexts.context(railsProject, runDirectory)),
                        "CW-RB-SEC-008"),
                "an interpolated value marked safe with raw is rendered as markup in Rails");
        assertTrue(new RubyHeuristicAnalyzer().analyzeSecurity(
                        RubyTestContexts.context(plainProject, runDirectory)).isEmpty(),
                "outside Rails, raw and html_safe are just method names, not escaping switches");
    }

    @Test
    @DisplayName("the static-analysis view excludes security rules, which the security phase reports")
    void separatesSecurityFindings(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        RubyTestContexts.write(root, "risky.rb", """
                def persist(record)
                  api_key = "sk-live-abcdef123456"
                  record
                end
                """);
        VerificationContext context = RubyTestContexts.context(root, runDirectory);

        RubyHeuristicAnalyzer analyzer = new RubyHeuristicAnalyzer();
        List<Finding> staticFindings = analyzer.analyze(context);
        List<Finding> securityFindings = analyzer.analyzeSecurity(context);

        assertTrue(staticFindings.stream().noneMatch(finding -> finding.category() == FindingCategory.SECURITY),
                "the correctness phase must not report the security rules: " + staticFindings);
        assertTrue(staticFindings.stream().noneMatch(finding -> RubyHeuristicAnalyzer.isSecurityRule(
                finding.ruleId())), staticFindings.toString());
        assertTrue(hasRule(securityFindings, "CW-RB-SEC-001"), securityFindings.toString());
        assertTrue(securityFindings.stream().allMatch(finding -> RubyHeuristicAnalyzer.isSecurityRule(
                finding.ruleId())), securityFindings.toString());
    }

    @Test
    @DisplayName("an example with no expectation is reported, and one with an expectation is not")
    void reportsTestsThatCannotFail(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        RubyTestContexts.write(root, "lib/ledger.rb", "class Ledger\nend\n");
        RubyTestContexts.write(root, "spec/ledger_spec.rb", """
                RSpec.describe('Ledger') do
                  it "records a withdrawal" do
                    value = 1
                  end

                  it "rejects a negative amount" do
                    expect(ledger).to be_valid
                  end
                end
                """);
        // Scaffolding this adapter's own generator wrote, at the path it actually writes: the generator
        // puts one `<test root>/generated/cw_sc_<ScenarioId>_spec.rb` per scenario. Reporting it would be
        // a finding about this tool's output rather than about the project, and the directory is what
        // makes it recognisable - the scenario id carries no fixed spelling.
        RubyTestContexts.write(root, "spec/generated/cw_sc_0001_spec.rb", """
                RSpec.describe('Generated') do
                  it "does nothing" do
                    value = 1
                  end
                end
                """);
        VerificationContext context = RubyTestContexts.context(root, runDirectory);

        List<Finding> findings = new RubyHeuristicAnalyzer().analyzeTests(context);

        assertEquals(1, findings.size(), "only the example without an expectation may be reported: "
                + findings);
        assertEquals("CW-RB-TEST-001", findings.getFirst().ruleId());
        assertEquals(2, findings.getFirst().line(),
                "the finding must point at the example that cannot fail: " + findings);
        assertTrue(findings.getFirst().file().replace('\\', '/').endsWith("spec/ledger_spec.rb"),
                findings.getFirst().file());
    }

    @Test
    @DisplayName("a correct file produces no findings at all, rather than a note per rule")
    void aCleanFileReportsNothing(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        RubyTestContexts.write(root, "ledger.rb", """
                # Withdraws an amount from a balance.
                def withdraw(amount, balance)
                  raise ArgumentError, 'amount must not be negative' if amount.negative?
                  return balance - amount if amount <= balance

                  balance
                end
                """);
        VerificationContext context = RubyTestContexts.context(root, runDirectory);

        assertEquals(List.of(), new RubyHeuristicAnalyzer().findAll(context),
                "a rule that matched nothing must report nothing: no finding is produced without an "
                        + "observed match");
    }

    @Test
    @DisplayName("a commented-out hazard is not a finding, because no projection ever saw it as code")
    void refusesToReportWhatItDidNotObserve(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        RubyTestContexts.write(root, "notes.rb", """
                # eval(input)
                # system(command)
                # Marshal.load(payload)
                value = 1
                """);
        VerificationContext context = RubyTestContexts.context(root, runDirectory);

        assertEquals(List.of(), new RubyHeuristicAnalyzer().findAll(context),
                "a commented-out call was not observed as code by any projection");
    }

    @Test
    @DisplayName("the documented severity of a rule and the rule table are stable")
    void exposesTheRuleTable() {
        assertTrue(Severity.CRITICAL == RubyHeuristicAnalyzer.severityOf("CW-RB-SEC-001"));
        assertTrue(Severity.HIGH == RubyHeuristicAnalyzer.severityOf("CW-RB-SEC-002"));
        assertTrue(Severity.MEDIUM == RubyHeuristicAnalyzer.severityOf("CW-RB-REL-002"));
        assertTrue(Severity.LOW == RubyHeuristicAnalyzer.severityOf("CW-RB-CORRECT-001"));
        assertTrue(Severity.INFO == RubyHeuristicAnalyzer.severityOf("CW-RB-MAIN-001"));
        assertTrue(RubyHeuristicAnalyzer.ruleIds().contains("CW-RB-SEC-008"),
                "the Rails-gated rule is part of the table even though it is not always applied");
        assertTrue(RubyHeuristicAnalyzer.ruleIds().contains("CW-RB-TEST-001"));
        assertTrue(RubyHeuristicAnalyzer.ruleIds().contains("CW-RB-STYLE-001"));
        assertTrue(RubyHeuristicAnalyzer.ruleIds().stream().allMatch(ruleId -> ruleId.startsWith("CW-RB-")),
                RubyHeuristicAnalyzer.ruleIds().toString());
        Set<String> unique = new HashSet<>(RubyHeuristicAnalyzer.ruleIds());
        assertEquals(RubyHeuristicAnalyzer.ruleIds().size(), unique.size(),
                "two rules sharing an id would make a report ambiguous: " + RubyHeuristicAnalyzer.ruleIds());
        assertTrue(RubyHeuristicAnalyzer.isSecurityRule("CW-RB-SEC-001"));
        assertFalse(RubyHeuristicAnalyzer.isSecurityRule("CW-RB-REL-001"));
    }
}
