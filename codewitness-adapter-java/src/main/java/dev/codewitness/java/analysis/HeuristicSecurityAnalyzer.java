package dev.codewitness.java.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.support.DelegatingSecurityAnalyzer;

import java.util.List;

/**
 * Security oriented analysis for Java.
 *
 * <p>The rules are the heuristic table's security entries, filtered out of the same scan the static-analysis
 * phase runs, so a rule is defined once and a finding can never be counted twice under two different
 * definitions. What is left here is the filter; the registration ceremony around it belongs to
 * {@link DelegatingSecurityAnalyzer}, because fourteen adapters needed the same three methods.
 */
public final class HeuristicSecurityAnalyzer extends DelegatingSecurityAnalyzer {

    public HeuristicSecurityAnalyzer() {
        super("heuristics-security", LanguageId.JAVA, HeuristicSecurityAnalyzer::securityFindings);
    }

    private static List<Finding> securityFindings(VerificationContext context) {
        return new HeuristicSourceAnalyzer().findAll(context).stream()
                .filter(finding -> HeuristicSourceAnalyzer.isSecurityRule(finding.ruleId()))
                .toList();
    }
}
