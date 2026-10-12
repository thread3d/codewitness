package dev.codewitness.swift;

import dev.codewitness.core.spi.LanguageAdapterRegistry;
import dev.codewitness.core.spi.VerificationProviderRegistry;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.swift.analysis.SwiftHeuristicAnalyzer;
import dev.codewitness.swift.analysis.SwiftSecurityAnalyzer;
import dev.codewitness.swift.coverage.SwiftCoverageProvider;
import dev.codewitness.swift.dependency.SwiftDependencyAnalyzer;
import dev.codewitness.swift.generate.SwiftGeneratedTestRunner;
import dev.codewitness.swift.generate.SwiftScenarioTestGenerator;
import dev.codewitness.swift.mutation.SwiftMutationTester;

/**
 * Wires the Swift adapter and its providers into the verification core.
 *
 * <p>This is the only place that knows which concrete Swift tools exist. The engine sees
 * {@link dev.codewitness.core.spi.LanguageAdapter} and the provider interfaces, so adding an external
 * linter next to the built-in analyzers, or substituting a test runner, is a change to this class and
 * nothing else.
 *
 * <p>A Swift package has one build system - Swift Package Manager - so there is no per-build-system
 * wiring here: every provider resolves the run's toolchain from the run context.
 */
public final class SwiftAdapterBundle {

    private SwiftAdapterBundle() {
    }

    /**
     * Registers the Swift language adapter and every Swift verification provider.
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

        languages.register(new SwiftLanguageAdapter());

        providers.register(new SwiftBuildProvider());
        providers.register(new SwiftTestProvider());
        providers.register(new SwiftCoverageProvider());
        providers.register(new SwiftDependencyAnalyzer());
        providers.register(new SwiftMutationTester());
        providers.register(new SwiftScenarioTestGenerator());
        providers.register(new SwiftGeneratedTestRunner());

        if (includeAnalysis) {
            providers.register((VerificationTools.StaticAnalyzer) new SwiftHeuristicAnalyzer());
            providers.register((VerificationTools.SecurityAnalyzer) new SwiftSecurityAnalyzer());
        }
    }
}
