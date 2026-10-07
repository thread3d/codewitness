package dev.codewitness.php.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.php.PhpTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The PHP heuristic and security rule table.
 *
 * <p>Every assertion here is one-directional on purpose: a rule must report the snippet it is written for,
 * and must report nothing at all for a file that contains none of its patterns. A rule that matched
 * nothing produces no pseudo-finding saying it was checked, because a report that says "checked and clean"
 * about a pattern that never ran is exactly the kind of unobserved success this project refuses.
 */
class PhpHeuristicAnalyzerTest {

    private static boolean hasRule(List<Finding> findings, String ruleId) {
        return findings.stream().anyMatch(finding -> ruleId.equals(finding.ruleId()));
    }

    @Test
    @DisplayName("loose comparisons, non-strict search, float money and count() in a loop are reported")
    void reportsCorrectnessHeuristics(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        PhpTestContexts.write(root, "ledger.php", """
                <?php
                function find(array $rows, string $needle): void
                {
                    if (strpos($rows[0], $needle) == false) {
                        return;
                    }
                    if (in_array($needle, $rows)) {
                        return;
                    }
                    $balance = 100.00 * 1.05;
                    for ($i = 0; $i < count($rows); $i++) {
                        echo $rows[$i];
                    }
                }
                """);
        VerificationContext context = PhpTestContexts.context(root, runDirectory);

        List<Finding> findings = new PhpHeuristicAnalyzer().analyze(context);

        assertTrue(hasRule(findings, "CW-PHP-CORRECT-001"),
                "strpos compared with == must be reported: " + findings);
        assertTrue(hasRule(findings, "CW-PHP-CORRECT-003"),
                "in_array without its strict flag must be reported: " + findings);
        assertTrue(hasRule(findings, "CW-PHP-CORRECT-004"),
                "money held as a binary float must be reported: " + findings);
        assertTrue(hasRule(findings, "CW-PHP-CORRECT-007"),
                "count() in a for-condition must be reported: " + findings);
        assertTrue(findings.stream().allMatch(finding -> finding.file().endsWith("ledger.php")),
                "every finding must name the file it came from: " + findings);
    }

    @Test
    @DisplayName("suppressed errors, empty and broad catches, die and global state are reported")
    void reportsReliabilityHeuristics(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        PhpTestContexts.write(root, "loader.php", """
                <?php
                function load(string $path): string
                {
                    $data = @file_get_contents($path);
                    try {
                        return $data;
                    } catch (Exception $e) {
                    }
                    if ($row = $data) {
                        global $cache;
                        return $row;
                    }
                    error_reporting(0);
                    return $data;
                }
                """);
        VerificationContext context = PhpTestContexts.context(root, runDirectory);

        List<Finding> findings = new PhpHeuristicAnalyzer().analyze(context);

        assertTrue(hasRule(findings, "CW-PHP-REL-001"), "the @ operator must be reported: " + findings);
        assertTrue(hasRule(findings, "CW-PHP-REL-002"), "an empty catch must be reported: " + findings);
        assertTrue(hasRule(findings, "CW-PHP-REL-003"), "a broad catch must be reported: " + findings);
        assertTrue(hasRule(findings, "CW-PHP-REL-005"), "error_reporting(0) must be reported: " + findings);
        assertTrue(hasRule(findings, "CW-PHP-REL-006"), "an assignment in an if must be reported: " + findings);
        assertTrue(hasRule(findings, "CW-PHP-REL-007"), "global state must be reported: " + findings);
    }

    @Test
    @DisplayName("foreach by reference without unset is reported once per variable")
    void reportsForeachByReference(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        PhpTestContexts.write(root, "sum.php", """
                <?php
                function total(array $rows): float
                {
                    $total = 0.0;
                    foreach ($rows as &$row) {
                        $row = $row * 2;
                    }
                    return $total;
                }
                """);
        VerificationContext context = PhpTestContexts.context(root, runDirectory);

        List<Finding> findings = new PhpHeuristicAnalyzer().analyze(context);

        assertTrue(hasRule(findings, "CW-PHP-CORRECT-005"),
                "a reference that survives the loop must be reported: " + findings);
    }

    @Test
    @DisplayName("a foreach by reference with unset is not reported")
    void unsetReferenceIsNotReported(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        PhpTestContexts.write(root, "sum.php", """
                <?php
                function doubled(array $rows): array
                {
                    foreach ($rows as &$row) {
                        $row = $row * 2;
                    }
                    unset($row);
                    return $rows;
                }
                """);
        VerificationContext context = PhpTestContexts.context(root, runDirectory);

        List<Finding> findings = new PhpHeuristicAnalyzer().analyze(context);

        assertTrue(findings.stream().noneMatch(finding -> "CW-PHP-CORRECT-005".equals(finding.ruleId())),
                "a reference that is unset cannot corrupt the next loop: " + findings);
    }

    @Test
    @DisplayName("eval, command execution, file inclusion and injected SQL are reported as security findings")
    void reportsSecurityRules(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        PhpTestContexts.write(root, "risky.php", """
                <?php
                $apiKey = "sk-live-abcdef123456";
                eval($input);
                $db->query("SELECT * FROM accounts WHERE id = $id");
                $db->query("SELECT * FROM accounts WHERE id = " . $id);
                include $template;
                $payload = unserialize($body);
                $digest = md5($password);
                extract($_GET);
                $out = `cat $path`;
                """);
        VerificationContext context = PhpTestContexts.context(root, runDirectory);

        List<Finding> all = new PhpHeuristicAnalyzer().findAll(context);

        assertTrue(hasRule(all, "CW-PHP-SEC-001"), "a hardcoded credential must be reported: " + all);
        assertTrue(hasRule(all, "CW-PHP-SEC-002"), "eval() must be reported: " + all);
        assertTrue(hasRule(all, "CW-PHP-SEC-005"), "a backtick command with a value in it must be reported: " + all);
        assertTrue(hasRule(all, "CW-PHP-SEC-006"), "unserialize() must be reported: " + all);
        assertTrue(hasRule(all, "CW-PHP-SEC-007"), "MD5 must be reported: " + all);
        assertTrue(hasRule(all, "CW-PHP-SEC-008"), "an interpolated SQL statement must be reported: " + all);
        assertTrue(hasRule(all, "CW-PHP-SEC-009"), "a concatenated SQL statement must be reported: " + all);
        assertTrue(hasRule(all, "CW-PHP-SEC-010"), "an included variable path must be reported: " + all);
        assertTrue(hasRule(all, "CW-PHP-SEC-011"), "extract() of request data must be reported: " + all);
    }

    @Test
    @DisplayName("the static-analysis view excludes security rules, which the security phase reports")
    void separatesSecurityFindings(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        PhpTestContexts.write(root, "risky.php", "<?php\n$apiKey = \"sk-live-abcdef123456\";\n");
        VerificationContext context = PhpTestContexts.context(root, runDirectory);

        PhpHeuristicAnalyzer analyzer = new PhpHeuristicAnalyzer();
        List<Finding> staticFindings = analyzer.analyze(context);
        List<Finding> securityFindings = analyzer.analyzeSecurity(context);

        assertTrue(staticFindings.stream().noneMatch(finding -> "SECURITY".equals(finding.category().name())),
                "the correctness phase must not report the security rules: " + staticFindings);
        assertTrue(hasRule(securityFindings, "CW-PHP-SEC-001"), securityFindings.toString());
    }

    @Test
    @DisplayName("a test method with no assertion is reported, and one with an assertion is not")
    void reportsTestsThatCannotFail(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        PhpTestContexts.write(root, "ledger.php", "<?php\n$balance = 100;\n");
        PhpTestContexts.write(root, "tests/LedgerTest.php", """
                <?php
                class LedgerTest extends TestCase
                {
                    public function testNothingHappens(): void
                    {
                        $value = 1;
                    }

                    public function testWithdraws(): void
                    {
                        $this->assertSame(1, 1);
                    }
                }
                """);
        VerificationContext context = PhpTestContexts.context(root, runDirectory);

        List<Finding> findings = new PhpHeuristicAnalyzer().analyzeTests(context);

        assertEquals(1, findings.size(), "only the test without an assertion may be reported: " + findings);
        assertEquals("CW-PHP-TEST-001", findings.getFirst().ruleId());
        assertEquals(4, findings.getFirst().line(),
                "the finding must point at the test method that cannot fail: " + findings);
        assertTrue(findings.getFirst().file().replace('\\', '/').endsWith("tests/LedgerTest.php"),
                findings.getFirst().file());
    }

    @Test
    @DisplayName("a #[Test]-attributed method is a test even when its name does not start with test")
    void attributesMarkATestMethod(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        PhpTestContexts.write(root, "ledger.php", "<?php\n$balance = 100;\n");
        PhpTestContexts.write(root, "tests/LedgerTest.php", """
                <?php
                class LedgerTest extends TestCase
                {
                    #[Test]
                    public function itWithdraws(): void
                    {
                        $ledger = new Ledger();
                    }
                }
                """);
        VerificationContext context = PhpTestContexts.context(root, runDirectory);

        List<Finding> findings = new PhpHeuristicAnalyzer().analyzeTests(context);

        assertTrue(hasRule(findings, "CW-PHP-TEST-001"),
                "the attribute is what makes this method a test: " + findings);
    }

    @Test
    @DisplayName("a correct file produces no findings at all, rather than a note per rule")
    void aCleanFileReportsNothing(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        PhpTestContexts.write(root, "ledger.php", """
                <?php
                declare(strict_types=1);

                function withdraw(int $amount, int $balance): int
                {
                    if ($amount > $balance) {
                        throw new RuntimeException('insufficient funds');
                    }
                    return $balance - $amount;
                }
                """);
        VerificationContext context = PhpTestContexts.context(root, runDirectory);

        assertEquals(List.of(), new PhpHeuristicAnalyzer().findAll(context),
                "a rule that matched nothing must report nothing: no finding is produced without an "
                        + "observed match");
    }

    @Test
    @DisplayName("a commented-out or quoted hazard is not a finding")
    void refusesToReportWhatItDidNotObserve(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        PhpTestContexts.write(root, "docs.php", """
                <?php
                // eval($userInput);
                # include $template;
                $doc = "eval($userInput) and include $template";
                """);
        VerificationContext context = PhpTestContexts.context(root, runDirectory);

        assertEquals(List.of(), new PhpHeuristicAnalyzer().findAll(context),
                "text that only looks like code is not code: a commented-out call and a call inside a string "
                        + "were not observed");
    }

    @Test
    @DisplayName("the documented severity of a rule and the rule table are stable")
    void exposesTheRuleTable() {
        assertTrue(Severity.CRITICAL == PhpHeuristicAnalyzer.severityOf("CW-PHP-SEC-001"));
        assertTrue(Severity.HIGH == PhpHeuristicAnalyzer.severityOf("CW-PHP-SEC-004"));
        assertTrue(Severity.MEDIUM == PhpHeuristicAnalyzer.severityOf("CW-PHP-REL-002"));
        assertTrue(PhpHeuristicAnalyzer.ruleIds().contains("CW-PHP-SEC-013"));
        assertTrue(PhpHeuristicAnalyzer.ruleIds().contains("CW-PHP-TEST-001"));
        assertTrue(PhpHeuristicAnalyzer.ruleIds().stream().allMatch(ruleId -> ruleId.startsWith("CW-PHP-")),
                PhpHeuristicAnalyzer.ruleIds().toString());
        Set<String> unique = new HashSet<>(PhpHeuristicAnalyzer.ruleIds());
        assertEquals(PhpHeuristicAnalyzer.ruleIds().size(), unique.size(),
                "two rules sharing an id would make a report ambiguous: " + PhpHeuristicAnalyzer.ruleIds());
        assertTrue(PhpHeuristicAnalyzer.isSecurityRule("CW-PHP-SEC-001"));
        assertTrue(!PhpHeuristicAnalyzer.isSecurityRule("CW-PHP-REL-001"));
    }
}
