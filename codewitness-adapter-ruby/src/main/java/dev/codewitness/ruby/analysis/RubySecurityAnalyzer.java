package dev.codewitness.ruby.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.util.List;

/**
 * Security oriented analysis for Ruby.
 *
 * <p>It shares one rule table with {@link RubyHeuristicAnalyzer} so that a rule is defined once: the
 * static-analysis phase reports the correctness and reliability rules and this phase reports the
 * {@code CW-RB-SEC-*} ones, and a finding can never be counted twice under two different definitions. It
 * is registered separately because the engine schedules security scanning as its own phase with its own
 * configuration switch.
 *
 * <p>The rules it runs are the ones this adapter can decide from the source alone - {@code eval} on a
 * computed value, a shell command built by interpolation, SQL built by concatenation,
 * {@code Marshal.load}, {@code YAML.load}, dynamic {@code send}/{@code constantize}, a hardcoded
 * credential, and Rails' {@code html_safe}/{@code raw} on interpolated content. Anything that needs data
 * flow to decide is out of scope and is not guessed at: a finding here is a place to look, not a proof.
 */
public final class RubySecurityAnalyzer implements VerificationTools.SecurityAnalyzer {

    private final RubyHeuristicAnalyzer heuristics = new RubyHeuristicAnalyzer();

    @Override
    public String id() {
        return "ruby-security";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.RUBY);
    }

    @Override
    public List<Finding> analyze(VerificationContext context) {
        return heuristics.analyzeSecurity(context);
    }
}
