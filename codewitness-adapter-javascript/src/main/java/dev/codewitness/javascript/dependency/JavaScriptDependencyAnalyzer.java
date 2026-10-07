package dev.codewitness.javascript.dependency;

import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.support.dependency.PackageJsonDependencyAnalyzer;

import java.util.List;

/**
 * Inventories a JavaScript project's declared dependencies from {@code package.json}.
 *
 * <p>Reading a {@code package.json} is the same job for JavaScript and TypeScript, so the work lives
 * once in {@link PackageJsonDependencyAnalyzer}. This class exists only to say which language it is
 * for, and it keeps the adapter-local name the bundle registers and the tests call.
 */
public final class JavaScriptDependencyAnalyzer implements VerificationTools.DependencyAnalyzer {

    private final PackageJsonDependencyAnalyzer delegate =
            new PackageJsonDependencyAnalyzer(LanguageId.JAVASCRIPT);

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return delegate.supports(project);
    }

    @Override
    public DependencyReport analyseDependencies(VerificationContext context) {
        return delegate.analyseDependencies(context);
    }

    /** @see PackageJsonDependencyAnalyzer#parse(String) */
    public static List<String> parse(String manifest) {
        return PackageJsonDependencyAnalyzer.parse(manifest);
    }
}
