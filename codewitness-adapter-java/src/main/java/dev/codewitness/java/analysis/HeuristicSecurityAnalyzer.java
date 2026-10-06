package dev.codewitness.java.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.util.List;

/**
 * Security rules, expressed as a separate provider from the general static analyzer.
 *
 * <p>The split matters for honesty in the report: a security finding is reported in its own section
 * and is not diluted by style findings. Both providers share the same rule table, so a rule is
 * written once.
 */
public final class HeuristicSecurityAnalyzer implements VerificationTools.SecurityAnalyzer {

    private final HeuristicSourceAnalyzer delegate = new HeuristicSourceAnalyzer();

    @Override
    public String id() {
        return "heuristics-security";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(dev.codewitness.core.model.LanguageId.JAVA);
    }

    @Override
    public List<Finding> analyze(VerificationContext context) {
        return delegate.findAll(context).stream()
                .filter(finding -> HeuristicSourceAnalyzer.isSecurityRule(finding.ruleId()))
                .toList();
    }
}
