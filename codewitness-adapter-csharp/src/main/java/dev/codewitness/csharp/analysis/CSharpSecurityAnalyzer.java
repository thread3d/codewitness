package dev.codewitness.csharp.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.util.List;

/**
 * Security oriented analysis for C#.
 *
 * <p>It shares one rule table with {@link CSharpHeuristicAnalyzer} so that a rule is defined once: the
 * static-analysis phase reports the correctness rules and this phase reports the security ones, and a
 * finding can never be counted twice under two different definitions.
 */
public final class CSharpSecurityAnalyzer implements VerificationTools.SecurityAnalyzer {

    private final CSharpHeuristicAnalyzer heuristics = new CSharpHeuristicAnalyzer();

    @Override
    public String id() {
        return "csharp-security";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.CSHARP);
    }

    @Override
    public List<Finding> analyze(VerificationContext context) {
        return heuristics.analyzeSecurity(context);
    }
}
