package dev.codewitness.python;

import dev.codewitness.core.spi.LanguageAdapterRegistry;
import dev.codewitness.core.spi.VerificationProviderRegistry;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.python.analysis.PythonHeuristicAnalyzer;
import dev.codewitness.python.analysis.PythonSecurityAnalyzer;
import dev.codewitness.python.coverage.PythonCoverageProvider;
import dev.codewitness.python.dependency.PythonDependencyAnalyzer;
import dev.codewitness.python.generate.PythonGeneratedTestRunner;
import dev.codewitness.python.generate.PythonScenarioTestGenerator;
import dev.codewitness.python.mutation.PythonMutationTester;

/**
 * Wires the Python adapter and its providers into the verification core.
 *
 * <p>This is the only place that knows which concrete Python tools exist. The engine sees
 * {@code LanguageAdapter} and the provider interfaces, so substituting another test runner or
 * adding an external linter next to the built-in analyzer is a change to this class and nothing
 * else.
 *
 * <p>Unlike the Java adapter, the providers here need no per-build-system wiring: every one of them
 * resolves the project's interpreter from the run context, and there is one Python build system.
 */
public final class PythonAdapterBundle {

    private PythonAdapterBundle() {
    }

    /** Registers the Python language adapter and every Python verification provider. */
    public static void register(
            LanguageAdapterRegistry languages, VerificationProviderRegistry providers, boolean includeAnalysis) {

        languages.register(new PythonLanguageAdapter());

        providers.register(new PythonBuildProvider());
        providers.register(new PythonTestProvider());
        providers.register(new PythonCoverageProvider());
        providers.register(new PythonMutationTester());
        providers.register(new PythonDependencyAnalyzer());
        providers.register(new PythonGeneratedTestRunner());
        providers.register(new PythonScenarioTestGenerator());

        if (includeAnalysis) {
            providers.register((VerificationTools.StaticAnalyzer) new PythonHeuristicAnalyzer());
            providers.register((VerificationTools.SecurityAnalyzer) new PythonSecurityAnalyzer());
        }
    }
}
