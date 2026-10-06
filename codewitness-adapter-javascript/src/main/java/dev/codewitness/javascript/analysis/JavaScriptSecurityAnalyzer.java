package dev.codewitness.javascript.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.util.List;

/**
 * Security oriented analysis for JavaScript.
 *
 * <p>It shares one rule table with {@link JavaScriptHeuristicAnalyzer} so that a rule is defined once:
 * the static-analysis phase reports the correctness rules and this phase reports the security ones, and
 * a finding can never be counted twice under two different definitions.
 */
public final class JavaScriptSecurityAnalyzer implements VerificationTools.SecurityAnalyzer {

    private final JavaScriptHeuristicAnalyzer heuristics = new JavaScriptHeuristicAnalyzer();

    @Override
    public String id() {
        return "javascript-security";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.JAVASCRIPT);
    }

    @Override
    public List<Finding> analyze(VerificationContext context) {
        return heuristics.analyzeSecurity(context);
    }
}
