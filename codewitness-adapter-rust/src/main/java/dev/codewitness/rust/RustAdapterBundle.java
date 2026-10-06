package dev.codewitness.rust;

import dev.codewitness.core.spi.LanguageAdapterRegistry;
import dev.codewitness.core.spi.VerificationProviderRegistry;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.rust.analysis.RustHeuristicAnalyzer;
import dev.codewitness.rust.analysis.RustSecurityAnalyzer;
import dev.codewitness.rust.coverage.RustCoverageProvider;
import dev.codewitness.rust.dependency.RustDependencyAnalyzer;
import dev.codewitness.rust.generate.RustGeneratedTestRunner;
import dev.codewitness.rust.generate.RustScenarioTestGenerator;
import dev.codewitness.rust.mutation.RustMutationTester;

/**
 * Wires the Rust adapter and its providers into the verification core.
 *
 * <p>This is the only place that knows which concrete Rust tools exist. The engine sees
 * {@code LanguageAdapter} and the provider interfaces, so substituting another mutation tester or
 * adding an external linter next to the built-in analyzer is a change to this class and nothing else.
 *
 * <p>Every provider resolves the project's Cargo executable from the run context, and there is one
 * Rust build system, so no per-build-system wiring is needed.
 */
public final class RustAdapterBundle {

    private RustAdapterBundle() {
    }

    /** Registers the Rust language adapter and every Rust verification provider. */
    public static void register(
            LanguageAdapterRegistry languages, VerificationProviderRegistry providers, boolean includeAnalysis) {

        languages.register(new RustLanguageAdapter());

        providers.register(new RustBuildProvider());
        providers.register(new RustTestProvider());
        providers.register(new RustCoverageProvider());
        providers.register(new RustMutationTester());
        providers.register(new RustDependencyAnalyzer());
        providers.register(new RustGeneratedTestRunner());
        providers.register(new RustScenarioTestGenerator());

        if (includeAnalysis) {
            providers.register((VerificationTools.StaticAnalyzer) new RustHeuristicAnalyzer());
            providers.register((VerificationTools.SecurityAnalyzer) new RustSecurityAnalyzer());
        }
    }
}
