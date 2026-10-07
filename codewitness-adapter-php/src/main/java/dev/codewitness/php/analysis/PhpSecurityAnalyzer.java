package dev.codewitness.php.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.util.List;

/**
 * Security oriented analysis for PHP.
 *
 * <p>It shares one rule table with {@link PhpHeuristicAnalyzer} so that a rule is defined once: the
 * static-analysis phase reports the correctness and reliability rules and this phase reports the
 * {@code CW-PHP-SEC-*} ones, and a finding can never be counted twice under two different definitions.
 * It is registered separately because the engine schedules security scanning as its own phase with its
 * own configuration switch.
 */
public final class PhpSecurityAnalyzer implements VerificationTools.SecurityAnalyzer {

    private final PhpHeuristicAnalyzer heuristics = new PhpHeuristicAnalyzer();

    @Override
    public String id() {
        return "php-security";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.PHP);
    }

    @Override
    public List<Finding> analyze(VerificationContext context) {
        return heuristics.analyzeSecurity(context);
    }
}
