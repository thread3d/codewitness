package dev.codewitness.php;

import dev.codewitness.core.spi.LanguageAdapterRegistry;
import dev.codewitness.core.spi.VerificationProviderRegistry;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.php.analysis.PhpHeuristicAnalyzer;
import dev.codewitness.php.analysis.PhpSecurityAnalyzer;
import dev.codewitness.php.coverage.PhpCoverageProvider;
import dev.codewitness.php.dependency.PhpDependencyAnalyzer;
import dev.codewitness.php.generate.PhpGeneratedTestRunner;
import dev.codewitness.php.generate.PhpScenarioTestGenerator;
import dev.codewitness.php.mutation.PhpMutationTester;

/**
 * Wires the PHP adapter and its providers into the verification core.
 *
 * <p>This is the only place that knows which concrete PHP tools exist. The engine sees
 * {@link dev.codewitness.core.spi.LanguageAdapter} and the provider interfaces, so adding an external
 * linter next to the built-in analyzers, or substituting a test runner, is a change to this class and
 * nothing else.
 *
 * <p>A PHP project has one build system - Composer - so unlike the Java and Kotlin adapters there is no
 * per-build-system wiring here: every provider resolves the project's interpreter from the run context.
 */
public final class PhpAdapterBundle {

    private PhpAdapterBundle() {
    }

    /**
     * Registers the PHP language adapter and every PHP verification provider.
     *
     * @param languages       adapter registry to add the adapter to
     * @param providers       provider registry to add the tool providers to
     * @param includeAnalysis whether the two built-in analyzer providers should be registered. They are
     *                        the slowest and least decisive of the providers - a rule table is advisory
     *                        evidence and never refutes a requirement - so a caller that only wants
     *                        build, test and measurement can leave them out
     */
    public static void register(
            LanguageAdapterRegistry languages, VerificationProviderRegistry providers, boolean includeAnalysis) {

        languages.register(new PhpLanguageAdapter());

        providers.register(new PhpBuildProvider());
        providers.register(new PhpTestProvider());
        providers.register(new PhpCoverageProvider());
        providers.register(new PhpDependencyAnalyzer());
        providers.register(new PhpMutationTester());
        providers.register(new PhpScenarioTestGenerator());
        providers.register(new PhpGeneratedTestRunner());

        if (includeAnalysis) {
            providers.register((VerificationTools.StaticAnalyzer) new PhpHeuristicAnalyzer());
            providers.register((VerificationTools.SecurityAnalyzer) new PhpSecurityAnalyzer());
        }
    }
}
