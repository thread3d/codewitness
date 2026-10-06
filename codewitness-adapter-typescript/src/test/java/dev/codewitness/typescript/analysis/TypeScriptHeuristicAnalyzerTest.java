package dev.codewitness.typescript.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.typescript.TypeScriptTestContexts;

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

/** The TypeScript heuristic and security rule tables. */
class TypeScriptHeuristicAnalyzerTest {

    private static void writeHazards(Path root) throws IOException {
        TypeScriptTestContexts.write(root, "src/hazards.ts", """
                export function update(a: number, b: number, user: { name: string }, element: Element, raw: string) {
                  if (a == b) {
                    return 0;
                  }
                  const value: any = 1;
                  const apiKey = "hardcoded-secret";
                  eval("1 + 1");
                  element.innerHTML = raw;
                  const count = parseInt("08");
                  process.exit(1);
                  return user!.name + value + apiKey + count;
                }
                """);
        TypeScriptTestContexts.write(root, "src/clean.ts", """
                // if (a == b) { return; }
                /* const value: any = 1; */
                export const message = "ok";
                """);
        TypeScriptTestContexts.write(root, "tests/empty.test.ts", """
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
        VerificationContext context = TypeScriptTestContexts.context(root, runDirectory);

        TypeScriptHeuristicAnalyzer analyzer = new TypeScriptHeuristicAnalyzer();
        Set<String> rules = analyzer.findAll(context).stream()
                .map(Finding::ruleId)
                .collect(Collectors.toSet());

        assertTrue(rules.contains("CW-TS-REL-001"), "loose equality must be reported: " + rules);
        assertTrue(rules.contains("CW-TS-TYPE-001"), "any must be reported: " + rules);
        assertTrue(rules.contains("CW-TS-TYPE-002"), "a non-null assertion must be reported: " + rules);
        assertTrue(rules.contains("CW-TS-SEC-001"), "a hardcoded credential must be reported: " + rules);
        assertTrue(rules.contains("CW-TS-SEC-002"), "eval must be reported: " + rules);
        assertTrue(rules.contains("CW-TS-SEC-005"), "innerHTML must be reported: " + rules);
        assertTrue(rules.contains("CW-TS-BUG-001"), "parseInt without a radix must be reported: " + rules);
        assertTrue(rules.contains("CW-TS-BUG-002"), "process.exit must be reported: " + rules);
        assertTrue(rules.contains("CW-TS-TEST-001"), "a test with no assertion must be reported: " + rules);
    }

    @Test
    @DisplayName("a rule described only in a comment does not fire")
    void ignoresComments(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        writeHazards(root);
        VerificationContext context = TypeScriptTestContexts.context(root, runDirectory);

        List<Finding> cleanFindings = new TypeScriptHeuristicAnalyzer().findAll(context).stream()
                .filter(finding -> "src/clean.ts".equals(finding.file()))
                .toList();

        assertTrue(cleanFindings.isEmpty(), "comments must not be analysed: " + cleanFindings);
    }

    @Test
    @DisplayName("the security phase reports only security rules")
    void securityPhaseIsSecurityOnly(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        writeHazards(root);
        VerificationContext context = TypeScriptTestContexts.context(root, runDirectory);

        List<Finding> security = new TypeScriptSecurityAnalyzer().analyze(context);

        assertFalse(security.isEmpty());
        assertTrue(security.stream().allMatch(finding -> finding.ruleId().startsWith("CW-TS-SEC")),
                "the security phase must not repeat the correctness findings: " + security);
        assertEquals(security.size(), new TypeScriptHeuristicAnalyzer().analyzeSecurity(context).size());
    }

    @Test
    @DisplayName("every documented rule has a stable identifier and severity")
    void exposesItsRuleTable() {
        List<String> rules = TypeScriptHeuristicAnalyzer.ruleIds();
        assertTrue(rules.size() >= 19, "the rule table must not silently shrink: " + rules);
        assertTrue(rules.contains("CW-TS-SEC-001"));
        assertTrue(rules.contains("CW-TS-TEST-001"));
        assertEquals(dev.codewitness.core.model.Severity.CRITICAL,
                TypeScriptHeuristicAnalyzer.severityOf("CW-TS-SEC-001"));
    }
}
