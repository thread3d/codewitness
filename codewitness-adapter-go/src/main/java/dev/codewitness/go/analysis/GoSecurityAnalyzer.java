package dev.codewitness.go.analysis;

import dev.codewitness.core.model.LanguageId;
import dev.codewitness.support.DelegatingSecurityAnalyzer;

/**
 * Security oriented analysis for Go.
 *
 * <p>It shares one rule table with {@link GoHeuristicAnalyzer} so that a rule is defined once: the static-analysis
 * phase reports the correctness rules and this phase reports the security ones, and a finding can never be
 * counted twice under two different definitions. The forwarding itself lives in
 * {@link DelegatingSecurityAnalyzer}, because fourteen adapters needed the same three methods.
 */
public final class GoSecurityAnalyzer extends DelegatingSecurityAnalyzer {

    public GoSecurityAnalyzer() {
        super("go-security", LanguageId.GO, new GoHeuristicAnalyzer()::analyzeSecurity);
    }
}
