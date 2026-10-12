package dev.codewitness.pascal;

import dev.codewitness.core.spi.LanguageAdapterRegistry;
import dev.codewitness.core.spi.VerificationProviderRegistry;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.pascal.analysis.PascalHeuristicAnalyzer;
import dev.codewitness.pascal.analysis.PascalSecurityAnalyzer;
import dev.codewitness.pascal.generate.PascalGeneratedTestRunner;
import dev.codewitness.pascal.generate.PascalScenarioTestGenerator;
import dev.codewitness.pascal.mutation.PascalMutationTester;

/**
 * Registers the Pascal language adapter and every Pascal verification provider.
 *
 * <p>The engine discovers adapters through {@link java.util.ServiceLoader} and providers through the
 * composition root, so this is the one place that says which tools this language contributes. It is
 * deliberately explicit: a capability the adapter advertises and a provider it registers must agree, or
 * a phase is scheduled but has nothing to run it.
 *
 * <p>Nothing is registered for coverage or dependencies. Free Pascal has no coverage export and no
 * package manager in its base distribution, so there is no tool to drive and no ecosystem to ask about;
 * the engine reports those phases as not measured rather than being told a language-specific story that
 * would not be true.
 */
public final class PascalAdapterBundle {

    private PascalAdapterBundle() {
    }

    /** Registers the Pascal language adapter and every Pascal verification provider. */
    public static void register(
            LanguageAdapterRegistry languages, VerificationProviderRegistry providers, boolean includeAnalysis) {

        languages.register(new PascalLanguageAdapter());

        providers.register(new PascalBuildProvider());
        providers.register(new PascalTestProvider());
        providers.register(new PascalMutationTester());
        providers.register(new PascalGeneratedTestRunner());
        providers.register(new PascalScenarioTestGenerator());

        if (includeAnalysis) {
            providers.register((VerificationTools.StaticAnalyzer) new PascalHeuristicAnalyzer());
            providers.register((VerificationTools.SecurityAnalyzer) new PascalSecurityAnalyzer());
        }
    }
}
