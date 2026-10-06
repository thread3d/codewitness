package dev.codewitness.typescript.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.util.List;

/**
 * Security oriented analysis for TypeScript.
 *
 * <p>It shares one rule table with {@link TypeScriptHeuristicAnalyzer} so that a rule is defined once:
 * the static-analysis phase reports the correctness rules and this phase reports the security ones, and
 * a finding can never be counted twice under two different definitions.
 */
public final class TypeScriptSecurityAnalyzer implements VerificationTools.SecurityAnalyzer {

    private final TypeScriptHeuristicAnalyzer heuristics = new TypeScriptHeuristicAnalyzer();

    @Override
    public String id() {
        return "typescript-security";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.TYPESCRIPT);
    }

    @Override
    public List<Finding> analyze(VerificationContext context) {
        return heuristics.analyzeSecurity(context);
    }
}
