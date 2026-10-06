package dev.codewitness.typescript;

import dev.codewitness.core.spi.LanguageAdapterRegistry;
import dev.codewitness.core.spi.VerificationProviderRegistry;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.typescript.analysis.TypeScriptHeuristicAnalyzer;
import dev.codewitness.typescript.analysis.TypeScriptSecurityAnalyzer;
import dev.codewitness.typescript.coverage.TypeScriptCoverageProvider;
import dev.codewitness.typescript.dependency.TypeScriptDependencyAnalyzer;
import dev.codewitness.typescript.generate.TypeScriptGeneratedTestRunner;
import dev.codewitness.typescript.generate.TypeScriptScenarioTestGenerator;
import dev.codewitness.typescript.mutation.TypeScriptMutationTester;

/**
 * Wires the TypeScript adapter and its providers into the verification core.
 *
 * <p>This is the only place that knows which concrete TypeScript tools exist. The engine sees
 * {@code LanguageAdapter} and the provider interfaces, so substituting another mutation tester or adding
 * an external linter next to the built-in analyzer is a change to this class and nothing else.
 *
 * <p>Every provider resolves the project's Node executable from the run context, and there is one Node
 * toolchain, so no per-build-system wiring is needed.
 */
public final class TypeScriptAdapterBundle {

    private TypeScriptAdapterBundle() {
    }

    /** Registers the TypeScript language adapter and every TypeScript verification provider. */
    public static void register(
            LanguageAdapterRegistry languages, VerificationProviderRegistry providers, boolean includeAnalysis) {

        languages.register(new TypeScriptLanguageAdapter());

        providers.register(new TypeScriptBuildProvider());
        providers.register(new TypeScriptTestProvider());
        providers.register(new TypeScriptCoverageProvider());
        providers.register(new TypeScriptMutationTester());
        providers.register(new TypeScriptDependencyAnalyzer());
        providers.register(new TypeScriptGeneratedTestRunner());
        providers.register(new TypeScriptScenarioTestGenerator());

        if (includeAnalysis) {
            providers.register((VerificationTools.StaticAnalyzer) new TypeScriptHeuristicAnalyzer());
            providers.register((VerificationTools.SecurityAnalyzer) new TypeScriptSecurityAnalyzer());
        }
    }
}
