package dev.codewitness.ruby.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.ruby.RubyTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The security half of the Ruby rule table.
 *
 * <p>It is registered as its own provider because the engine schedules security scanning as its own phase,
 * so what matters here is that it reports exactly the {@code CW-RB-SEC-*} rules and agrees with the static
 * analyzer about which those are: two rule tables would eventually disagree, and a finding counted twice
 * under two definitions is worse than one counted once.
 *
 * <p>Each rule has a positive sample and, just as importantly, a nearby negative sample that must not fire.
 * The negative samples are the ordinary safe forms of the same call - a literal passed to {@code eval}, the
 * argument-list form of {@code system}, a parameterised query, {@code safe_load}, a symbol naming the method
 * to dispatch - because a rule that fires on those would teach a reader to ignore it.
 */
class RubySecurityAnalyzerTest {

    private static boolean hasRule(List<Finding> findings, String ruleId) {
        return findings.stream().anyMatch(finding -> ruleId.equals(finding.ruleId()));
    }

    @Test
    @DisplayName("the provider claims Ruby and nothing else")
    void supportsOnlyRuby(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        RubyTestContexts.write(root, "lib/ledger.rb", "class Ledger\nend\n");
        VerificationContext context = RubyTestContexts.context(root, runDirectory);
        RubySecurityAnalyzer analyzer = new RubySecurityAnalyzer();

        assertEquals("ruby-security", analyzer.id());
        assertTrue(analyzer.supports(context.projectInfo()));

        ProjectInfo javaProject = ProjectInfo.builder(context.projectInfo().project())
                .language(LanguageId.JAVA)
                .buildSystem(context.projectInfo().buildSystem())
                .build();
        assertFalse(analyzer.supports(javaProject),
                "the Java adapter has its own security analyzer; two would both run");
    }

    @Test
    @DisplayName("each CW-RB-SEC rule fires on its own sample, and the table is shared with the heuristics")
    void reportsEachSecurityRule(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        RubyTestContexts.write(root, "lib/risky.rb", """
                def risky(input, payload, name, body, command)
                  api_key = "sk-live-abcdef123456"
                  eval(input)
                  system(command)
                  where("id = #{name}")
                  Marshal.load(payload)
                  YAML.load(body)
                  record.send(name)
                end
                """);
        VerificationContext context = RubyTestContexts.context(root, runDirectory);

        List<Finding> findings = new RubySecurityAnalyzer().analyze(context);
        List<Finding> shared = new RubyHeuristicAnalyzer().analyzeSecurity(context);

        assertTrue(hasRule(findings, "CW-RB-SEC-001"), "a hardcoded credential must be reported: "
                + findings);
        assertTrue(hasRule(findings, "CW-RB-SEC-002"), "eval on a computed value must be reported: "
                + findings);
        assertTrue(hasRule(findings, "CW-RB-SEC-003"), "system with a computed command must be reported: "
                + findings);
        assertTrue(hasRule(findings, "CW-RB-SEC-004"), "interpolated SQL must be reported: " + findings);
        assertTrue(hasRule(findings, "CW-RB-SEC-005"), "Marshal.load must be reported: " + findings);
        assertTrue(hasRule(findings, "CW-RB-SEC-006"), "YAML.load must be reported: " + findings);
        assertTrue(hasRule(findings, "CW-RB-SEC-007"), "dynamic send must be reported: " + findings);
        assertTrue(findings.stream().allMatch(finding -> RubyHeuristicAnalyzer.isSecurityRule(
                finding.ruleId())), "the security phase must report nothing else: " + findings);
        assertEquals(shared.stream().map(Finding::fingerprint).sorted().toList(),
                findings.stream().map(Finding::fingerprint).sorted().toList(),
                "the two providers share one rule table and must not disagree about it");
    }

    @Test
    @DisplayName("the safe form of every call is not a finding")
    void reportsNothingForSafeConstructs(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        RubyTestContexts.write(root, "lib/safe.rb", """
                def safe(input, id, body, name)
                  eval("1 + 1")
                  system("ls -l")
                  where("id = ?", id)
                  YAML.safe_load(body)
                  YAML.load(body, permitted_classes: [])
                  record.send(:name)
                  password = ENV["PASSWORD"]
                  note = "Marshal.load(payload)"
                end
                """);
        VerificationContext context = RubyTestContexts.context(root, runDirectory);

        assertEquals(List.of(), new RubySecurityAnalyzer().analyze(context),
                "a literal passed to eval, the argument-list form of system, a parameterised query, "
                        + "safe_load, a permitted-class YAML.load and a symbol dispatch are the forms the "
                        + "rules exist to steer a reader towards");
    }

    @Test
    @DisplayName("the Rails escaping rule fires only where raw and html_safe are escaping switches")
    void theXssRuleIsReportedOnlyForRails(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
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

        List<Finding> rails = new RubySecurityAnalyzer()
                .analyze(RubyTestContexts.context(railsProject, runDirectory));
        List<Finding> plain = new RubySecurityAnalyzer()
                .analyze(RubyTestContexts.context(plainProject, runDirectory));

        assertTrue(hasRule(rails, "CW-RB-SEC-008"),
                "an interpolated value marked safe with raw is rendered as markup in Rails: " + rails);
        assertFalse(hasRule(plain, "CW-RB-SEC-008"),
                "outside Rails the same call is a method named raw, and the rule must stay off: " + plain);
    }
}
