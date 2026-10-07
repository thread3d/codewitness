package dev.codewitness.kotlin.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.util.List;

/**
 * The security half of the Kotlin rule table.
 *
 * <p>It reports exactly the {@code CW-KT-SEC-*} rules and shares their implementation with the static
 * analyzer, so that the two phases can never disagree about whether a finding is a security one. It is
 * registered separately because the engine schedules security scanning as its own phase with its own
 * configuration switch.
 */
public final class KotlinSecurityAnalyzer implements VerificationTools.SecurityAnalyzer {

    private final KotlinHeuristicAnalyzer delegate = new KotlinHeuristicAnalyzer();

    @Override
    public String id() {
        return "kotlin-security-analysis";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.KOTLIN);
    }

    @Override
    public List<Finding> analyze(VerificationContext context) {
        return delegate.findSecurityFindings(context);
    }
}
