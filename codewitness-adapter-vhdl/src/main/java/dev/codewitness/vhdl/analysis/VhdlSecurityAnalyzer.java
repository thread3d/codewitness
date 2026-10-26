package dev.codewitness.vhdl.analysis;

import dev.codewitness.core.model.LanguageId;
import dev.codewitness.support.DelegatingSecurityAnalyzer;

/**
 * Security oriented analysis for VHDL.
 *
 * <p>It shares one rule table with {@link VhdlHeuristicAnalyzer} so that a rule is defined once: the
 * static-analysis phase reports the correctness rules and this phase reports the security ones, and a finding
 * can never be counted twice under two different definitions.
 */
public final class VhdlSecurityAnalyzer extends DelegatingSecurityAnalyzer {

    public VhdlSecurityAnalyzer() {
        super("vhdl-security", LanguageId.VHDL, new VhdlHeuristicAnalyzer()::analyzeSecurity);
    }
}
