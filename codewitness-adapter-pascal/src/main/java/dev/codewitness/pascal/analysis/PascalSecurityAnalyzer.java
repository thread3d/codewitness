package dev.codewitness.pascal.analysis;

import dev.codewitness.core.model.LanguageId;
import dev.codewitness.support.DelegatingSecurityAnalyzer;

/**
 * Security oriented analysis for Pascal.
 *
 * <p>It shares one rule table with {@link PascalHeuristicAnalyzer} so that a rule is defined once: the
 * static-analysis phase reports the correctness rules and this phase reports the security ones, and a finding
 * can never be counted twice under two different definitions.
 */
public final class PascalSecurityAnalyzer extends DelegatingSecurityAnalyzer {

    public PascalSecurityAnalyzer() {
        super("pascal-heuristics", LanguageId.PASCAL, new PascalHeuristicAnalyzer()::analyzeSecurity);
    }
}
