package dev.codewitness.go.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.util.List;

/**
 * Security oriented analysis for Go.
 *
 * <p>It shares one rule table with {@link GoHeuristicAnalyzer} so that a rule is defined once: the
 * static-analysis phase reports the correctness rules and this phase reports the security ones, and
 * a finding can never be counted twice under two different definitions.
 */
public final class GoSecurityAnalyzer implements VerificationTools.SecurityAnalyzer {

    private final GoHeuristicAnalyzer heuristics = new GoHeuristicAnalyzer();

    @Override
    public String id() {
        return "go-security";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.GO);
    }

    @Override
    public List<Finding> analyze(VerificationContext context) {
        return heuristics.analyzeSecurity(context);
    }
}
