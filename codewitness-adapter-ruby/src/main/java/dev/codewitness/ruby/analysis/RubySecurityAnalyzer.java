package dev.codewitness.ruby.analysis;

import dev.codewitness.core.model.LanguageId;
import dev.codewitness.support.DelegatingSecurityAnalyzer;

/**
 * Security oriented analysis for Ruby.
 *
 * <p>It shares one rule table with {@link RubyHeuristicAnalyzer} so that a rule is defined once: the static-analysis
 * phase reports the correctness rules and this phase reports the security ones, and a finding can never be
 * counted twice under two different definitions. The forwarding itself lives in
 * {@link DelegatingSecurityAnalyzer}, because fourteen adapters needed the same three methods.
 */
public final class RubySecurityAnalyzer extends DelegatingSecurityAnalyzer {

    public RubySecurityAnalyzer() {
        super("ruby-security", LanguageId.RUBY, new RubyHeuristicAnalyzer()::analyzeSecurity);
    }
}
