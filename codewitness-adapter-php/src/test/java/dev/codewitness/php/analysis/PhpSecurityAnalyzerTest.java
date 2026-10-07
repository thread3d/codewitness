package dev.codewitness.php.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.php.PhpTestContexts;

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
 * The security half of the PHP rule table.
 *
 * <p>It is registered as its own provider because the engine schedules security scanning as its own phase,
 * so what matters here is that it reports exactly the {@code CW-PHP-SEC-*} rules and agrees with the static
 * analyzer about which those are: two rule tables would eventually disagree, and a finding counted twice
 * under two definitions is worse than one counted once.
 */
class PhpSecurityAnalyzerTest {

    @Test
    @DisplayName("the provider claims PHP and nothing else")
    void supportsOnlyPhp(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        PhpTestContexts.write(root, "ledger.php", "<?php\n$balance = 100;\n");
        VerificationContext context = PhpTestContexts.context(root, runDirectory);
        PhpSecurityAnalyzer analyzer = new PhpSecurityAnalyzer();

        assertEquals("php-security", analyzer.id());
        assertTrue(analyzer.supports(context.projectInfo()));

        ProjectInfo javaProject = ProjectInfo.builder(context.projectInfo().project())
                .language(LanguageId.JAVA)
                .buildSystem(context.projectInfo().buildSystem())
                .build();
        assertFalse(analyzer.supports(javaProject),
                "the Java adapter has its own security analyzer; two would both run");
    }

    @Test
    @DisplayName("only security findings are reported, and they are the ones the rule table defines")
    void reportsOnlySecurityFindings(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        PhpTestContexts.write(root, "risky.php", """
                <?php
                $apiKey = "sk-live-abcdef123456";
                if (in_array($role, $roles)) {
                    system($command);
                }
                """);
        VerificationContext context = PhpTestContexts.context(root, runDirectory);

        List<Finding> findings = new PhpSecurityAnalyzer().analyze(context);
        List<Finding> shared = new PhpHeuristicAnalyzer().analyzeSecurity(context);

        assertFalse(findings.isEmpty(), "the security phase must report the security rules");
        assertTrue(findings.stream().allMatch(finding -> PhpHeuristicAnalyzer.isSecurityRule(finding.ruleId())),
                "the security phase must report nothing else: " + findings);
        assertEquals(shared.stream().map(Finding::fingerprint).sorted().toList(),
                findings.stream().map(Finding::fingerprint).sorted().toList(),
                "the two providers share one rule table and must not disagree about it");
        assertTrue(findings.stream().noneMatch(finding -> "CW-PHP-CORRECT-003".equals(finding.ruleId())),
                "the correctness rule the same file triggers belongs to the static-analysis phase: "
                        + findings);
    }
}
