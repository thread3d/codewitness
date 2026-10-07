package dev.codewitness.ruby;

import dev.codewitness.core.spi.LanguageAdapterRegistry;
import dev.codewitness.core.spi.VerificationProviderRegistry;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.ruby.analysis.RubyHeuristicAnalyzer;
import dev.codewitness.ruby.analysis.RubySecurityAnalyzer;
import dev.codewitness.ruby.coverage.RubyCoverageProvider;
import dev.codewitness.ruby.dependency.RubyDependencyAnalyzer;
import dev.codewitness.ruby.generate.RubyGeneratedTestRunner;
import dev.codewitness.ruby.generate.RubyScenarioTestGenerator;
import dev.codewitness.ruby.mutation.RubyMutationTester;

/**
 * Wires the Ruby adapter and its providers into the verification core.
 *
 * <p>This is the only place that knows which concrete Ruby tools exist. The engine sees
 * {@link dev.codewitness.core.spi.LanguageAdapter} and the provider interfaces, so adding an external
 * linter next to the built-in analyzers, or substituting a test runner, is a change to this class and
 * nothing else.
 *
 * <p>A Ruby project has one build system - Bundler - so unlike the Java and Kotlin adapters there is no
 * per-build-system wiring here: every provider resolves the project's interpreter from the run context.
 */
public final class RubyAdapterBundle {

    private RubyAdapterBundle() {
    }

    /**
     * Registers the Ruby language adapter and every Ruby verification provider.
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

        languages.register(new RubyLanguageAdapter());

        providers.register(new RubyBuildProvider());
        providers.register(new RubyTestProvider());
        providers.register(new RubyCoverageProvider());
        providers.register(new RubyDependencyAnalyzer());
        providers.register(new RubyMutationTester());
        providers.register(new RubyScenarioTestGenerator());
        providers.register(new RubyGeneratedTestRunner());

        if (includeAnalysis) {
            providers.register((VerificationTools.StaticAnalyzer) new RubyHeuristicAnalyzer());
            providers.register((VerificationTools.SecurityAnalyzer) new RubySecurityAnalyzer());
        }
    }
}
