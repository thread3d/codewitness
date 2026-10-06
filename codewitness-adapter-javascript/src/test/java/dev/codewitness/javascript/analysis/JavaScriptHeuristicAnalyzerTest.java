package dev.codewitness.javascript.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.javascript.JavaScriptTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The JavaScript heuristic and security rule tables. */
class JavaScriptHeuristicAnalyzerTest {

    private static void writeHazards(Path root) throws IOException {
        JavaScriptTestContexts.write(root, "src/hazards.js", """
                export function update(a, b, user, element, raw) {
                  if (a == b) {
                    return 0;
                  }
                  const apiKey = "hardcoded-secret";
                  eval("1 + 1");
                  element.innerHTML = raw;
                  const count = parseInt("08");
                  process.exit(1);
                  return user.name + apiKey + count;
                }

                export function swallow() {
                  try { work(); } catch (error) {}
                }

                export function fail() {
                  throw "boom";
                }

                export function hoist() { var legacy = 1; return legacy; }

                export function money() {
                  const balance = 19.99;
                  return balance === 19.99;
                }

                export function window(millis) {
                  return millis / 86400000;
                }
                """);
        JavaScriptTestContexts.write(root, "src/clean.js", """
                // if (a == b) { return; }
                /* const apiKey = "hardcoded-secret"; */
                export const message = "ok";
                """);
        JavaScriptTestContexts.write(root, "tests/empty.test.js", """
                import test from "node:test";

                test("does nothing", () => {
                  const value = 1;
                });
                """);
    }

    @Test
    @DisplayName("the correctness, security and weak-test rules fire on their hazards")
    void reportsHazards(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        writeHazards(root);
        VerificationContext context = JavaScriptTestContexts.context(root, runDirectory);

        JavaScriptHeuristicAnalyzer analyzer = new JavaScriptHeuristicAnalyzer();
        Set<String> rules = analyzer.findAll(context).stream()
                .map(Finding::ruleId)
                .collect(Collectors.toSet());

        assertTrue(rules.contains("CW-JS-REL-001"), "loose equality must be reported: " + rules);
        assertTrue(rules.contains("CW-JS-SEC-001"), "a hardcoded credential must be reported: " + rules);
        assertTrue(rules.contains("CW-JS-SEC-002"), "eval must be reported: " + rules);
        assertTrue(rules.contains("CW-JS-SEC-005"), "innerHTML must be reported: " + rules);
        assertTrue(rules.contains("CW-JS-BUG-001"), "parseInt without a radix must be reported: " + rules);
        assertTrue(rules.contains("CW-JS-BUG-002"), "process.exit must be reported: " + rules);
        assertTrue(rules.contains("CW-JS-REL-002"), "an empty catch block must be reported: " + rules);
        assertTrue(rules.contains("CW-JS-REL-003"), "throwing a string must be reported: " + rules);
        assertTrue(rules.contains("CW-JS-REL-004"), "var must be reported: " + rules);
        assertTrue(rules.contains("CW-JS-NUM-001"), "a monetary float must be reported: " + rules);
        assertTrue(rules.contains("CW-JS-NUM-002"), "float equality must be reported: " + rules);
        assertTrue(rules.contains("CW-JS-DATE-001"), "calendar-day arithmetic must be reported: " + rules);
        assertTrue(rules.contains("CW-JS-TEST-001"), "a test with no assertion must be reported: " + rules);
    }

    @Test
    @DisplayName("a rule described only in a comment does not fire")
    void ignoresComments(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        writeHazards(root);
        VerificationContext context = JavaScriptTestContexts.context(root, runDirectory);

        List<Finding> cleanFindings = new JavaScriptHeuristicAnalyzer().findAll(context).stream()
                .filter(finding -> "src/clean.js".equals(finding.file()))
                .toList();

        assertTrue(cleanFindings.isEmpty(), "comments must not be analysed: " + cleanFindings);
    }

    @Test
    @DisplayName("the security phase reports only security rules")
    void securityPhaseIsSecurityOnly(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        writeHazards(root);
        VerificationContext context = JavaScriptTestContexts.context(root, runDirectory);

        List<Finding> security = new JavaScriptSecurityAnalyzer().analyze(context);

        assertFalse(security.isEmpty());
        assertTrue(security.stream().allMatch(finding -> finding.ruleId().startsWith("CW-JS-SEC")),
                "the security phase must not repeat the correctness findings: " + security);
        assertEquals(security.size(), new JavaScriptHeuristicAnalyzer().analyzeSecurity(context).size());
    }

    @Test
    @DisplayName("every documented rule has a stable identifier and severity")
    void exposesItsRuleTable() {
        List<String> rules = JavaScriptHeuristicAnalyzer.ruleIds();
        assertTrue(rules.size() >= 17, "the rule table must not silently shrink: " + rules);
        assertTrue(rules.contains("CW-JS-SEC-001"));
        assertTrue(rules.contains("CW-JS-TEST-001"));
        assertTrue(rules.stream().noneMatch(rule -> rule.startsWith("CW-JS-TYPE")),
                "JavaScript has no type system, so there is no escape-hatch rule table: " + rules);
        assertEquals(Severity.CRITICAL, JavaScriptHeuristicAnalyzer.severityOf("CW-JS-SEC-001"));
        assertEquals(Severity.HIGH, JavaScriptHeuristicAnalyzer.severityOf("CW-JS-BUG-002"));
        assertEquals("javascript-heuristics", new JavaScriptHeuristicAnalyzer().id());
    }
}
