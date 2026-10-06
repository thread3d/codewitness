package dev.codewitness.python.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.util.List;

/**
 * Security oriented analysis for Python.
 *
 * <p>It shares one rule table with {@link PythonHeuristicAnalyzer} so that a rule is defined once:
 * the static-analysis phase reports the correctness rules and this phase reports the security ones,
 * and a finding can never be counted twice under two different definitions.
 */
public final class PythonSecurityAnalyzer implements VerificationTools.SecurityAnalyzer {

    private final PythonHeuristicAnalyzer heuristics = new PythonHeuristicAnalyzer();

    @Override
    public String id() {
        return "python-security";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(dev.codewitness.core.model.LanguageId.PYTHON);
    }

    @Override
    public List<Finding> analyze(VerificationContext context) {
        return heuristics.analyzeSecurity(context);
    }
}
