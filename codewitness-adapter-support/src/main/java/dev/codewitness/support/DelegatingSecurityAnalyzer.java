package dev.codewitness.support;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The security phase for a language whose rules live in its heuristic analyzer.
 *
 * <p>Every adapter with a heuristic rule table had a class of its own that did three things: return an id, say
 * which language it supports, and forward {@code analyze} to {@code <Language>HeuristicAnalyzer.analyzeSecurity}.
 * Fourteen of those existed and not one of them contained a rule, a threshold or a language quirk — they were the
 * registration ceremony around a method call. The behaviour lives in the heuristic analyzer, which is where a
 * security rule belongs, because that is what makes a rule defined once instead of twice.
 *
 * <p>Each adapter still has a named class, and it is now a constructor call: the name stays because everything
 * that refers to it — the bundle that registers it, the test that asserts its id, a stack trace — names a
 * language, and reading {@code new SwiftSecurityAnalyzer()} is clearer than reading a lambda at the registration
 * site. What is gone is fourteen copies of the same three methods.
 *
 * <p>The {@code SQL} adapter does not use this: its security analysis reads a schema, not a rule table, and is a
 * real implementation.
 */
public class DelegatingSecurityAnalyzer implements VerificationTools.SecurityAnalyzer {

    private final String id;
    private final LanguageId language;
    private final Function<VerificationContext, List<Finding>> delegate;

    /**
     * @param id       provider id, for example {@code swift-security}
     * @param language the one language this provider claims
     * @param delegate the analysis, normally a heuristic analyzer's security method
     */
    public DelegatingSecurityAnalyzer(
            String id, LanguageId language, Function<VerificationContext, List<Finding>> delegate) {
        this.id = Objects.requireNonNull(id, "id");
        this.language = Objects.requireNonNull(language, "language");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project != null && language.equals(project.language());
    }

    @Override
    public List<Finding> analyze(VerificationContext context) {
        List<Finding> findings = delegate.apply(context);
        return findings == null ? List.of() : List.copyOf(findings);
    }
}
