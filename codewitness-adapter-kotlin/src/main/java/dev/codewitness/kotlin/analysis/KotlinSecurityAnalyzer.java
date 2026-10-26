package dev.codewitness.kotlin.analysis;

import dev.codewitness.core.model.LanguageId;
import dev.codewitness.support.DelegatingSecurityAnalyzer;

/**
 * Security oriented analysis for Kotlin.
 *
 * <p>It shares one rule table with {@link KotlinHeuristicAnalyzer} so that a rule is defined once: the
 * static-analysis phase reports the correctness rules and this phase reports the security ones, and a finding
 * can never be counted twice under two different definitions.
 */
public final class KotlinSecurityAnalyzer extends DelegatingSecurityAnalyzer {

    public KotlinSecurityAnalyzer() {
        super("kotlin-security-analysis", LanguageId.KOTLIN, new KotlinHeuristicAnalyzer()::findSecurityFindings);
    }
}
