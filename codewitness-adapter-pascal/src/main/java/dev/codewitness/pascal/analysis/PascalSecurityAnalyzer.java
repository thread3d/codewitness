package dev.codewitness.pascal.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.util.List;

/**
 * Presents the Pascal security rules through the security provider interface.
 *
 * <p>The rules live beside the other heuristics because they share a scanner, but the engine treats
 * security findings separately: this class is what keeps the two from being conflated. A separate
 * provider also means a run with security disabled does not pay for the security rules.
 */
public final class PascalSecurityAnalyzer implements VerificationTools.SecurityAnalyzer {

    private final PascalHeuristicAnalyzer delegate = new PascalHeuristicAnalyzer();

    @Override
    public String id() {
        return PascalHeuristicAnalyzer.ID;
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return delegate.supports(project);
    }

    @Override
    public List<Finding> analyze(VerificationContext context) {
        return delegate.analyzeSecurity(context);
    }
}
