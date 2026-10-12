package dev.codewitness.swift.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.util.List;

/**
 * Security oriented analysis for Swift.
 *
 * <p>It shares one rule table with {@link SwiftHeuristicAnalyzer} so that a rule is defined once: the
 * static-analysis phase reports the correctness, reliability and style rules and this phase reports the
 * {@code CW-SWIFT-SEC-*} ones, and a finding can never be counted twice under two different definitions.
 * It is registered separately because the engine schedules security scanning as its own phase with its own
 * configuration switch.
 *
 * <p>The rules it runs are the ones this adapter can decide from the source alone - a force unwrap, a
 * {@code try!}, an {@code as!}, a {@code fatalError} in library code, a hard-coded credential, and a secret
 * written to {@code UserDefaults}. Anything that needs data flow to decide is out of scope and is not
 * guessed at: a finding here is a place to look, not a proof.
 *
 * <p>The findings keep the heuristic analyzer's own id, exactly as the Ruby adapter's security phase does,
 * so the engine continues to treat them as advisory: a pattern that cannot see data flow may point at a
 * defect, but it must not refute a requirement on its own.
 */
public final class SwiftSecurityAnalyzer implements VerificationTools.SecurityAnalyzer {

    private final SwiftHeuristicAnalyzer heuristics = new SwiftHeuristicAnalyzer();

    @Override
    public String id() {
        return "swift-security";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.SWIFT);
    }

    @Override
    public List<Finding> analyze(VerificationContext context) {
        return heuristics.analyzeSecurity(context);
    }
}
