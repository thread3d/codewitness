package dev.codewitness.verilog.analysis;

import dev.codewitness.core.model.LanguageId;
import dev.codewitness.support.DelegatingSecurityAnalyzer;

/**
 * Security oriented analysis for Verilog.
 *
 * <p>It shares one rule table with {@link VerilogHeuristicAnalyzer} so that a rule is defined once: the
 * static-analysis phase reports the correctness rules and this phase reports the security ones, and a finding
 * can never be counted twice under two different definitions.
 */
public final class VerilogSecurityAnalyzer extends DelegatingSecurityAnalyzer {

    public VerilogSecurityAnalyzer() {
        super("verilog-security", LanguageId.VERILOG, new VerilogHeuristicAnalyzer()::analyzeSecurity);
    }
}
