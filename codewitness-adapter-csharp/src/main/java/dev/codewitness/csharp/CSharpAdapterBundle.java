package dev.codewitness.csharp;

import dev.codewitness.core.spi.LanguageAdapterRegistry;
import dev.codewitness.core.spi.VerificationProviderRegistry;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.csharp.analysis.CSharpHeuristicAnalyzer;
import dev.codewitness.csharp.analysis.CSharpSecurityAnalyzer;
import dev.codewitness.csharp.coverage.CSharpCoverageProvider;
import dev.codewitness.csharp.dependency.CSharpDependencyAnalyzer;
import dev.codewitness.csharp.generate.CSharpGeneratedTestRunner;
import dev.codewitness.csharp.generate.CSharpScenarioTestGenerator;
import dev.codewitness.csharp.mutation.CSharpMutationTester;

/**
 * Wires the C# adapter and its providers into the verification core.
 *
 * <p>This is the only place that knows which concrete .NET tools exist. The engine sees
 * {@link dev.codewitness.core.spi.LanguageAdapter} and the provider interfaces, so substituting another
 * mutation tester or adding an external analyzer next to the built-in rule table is a change to this
 * class and nothing else.
 *
 * <p>Every provider resolves the project's {@code dotnet} executable from the run context, and there is
 * one .NET build system - the SDK itself - so no per-build-system wiring is needed.
 */
public final class CSharpAdapterBundle {

    private CSharpAdapterBundle() {
    }

    /** Registers the C# language adapter and every .NET verification provider. */
    public static void register(
            LanguageAdapterRegistry languages, VerificationProviderRegistry providers, boolean includeAnalysis) {

        languages.register(new CSharpLanguageAdapter());

        providers.register(new CSharpBuildProvider());
        providers.register(new CSharpTestProvider());
        providers.register(new CSharpCoverageProvider());
        providers.register(new CSharpMutationTester());
        providers.register(new CSharpDependencyAnalyzer());
        providers.register(new CSharpGeneratedTestRunner());
        providers.register(new CSharpScenarioTestGenerator());

        if (includeAnalysis) {
            providers.register((VerificationTools.StaticAnalyzer) new CSharpHeuristicAnalyzer());
            providers.register((VerificationTools.SecurityAnalyzer) new CSharpSecurityAnalyzer());
        }
    }
}
