package dev.codewitness.cpp;

import dev.codewitness.core.spi.LanguageAdapterRegistry;
import dev.codewitness.core.spi.VerificationProviderRegistry;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.cpp.analysis.CppHeuristicAnalyzer;
import dev.codewitness.cpp.analysis.CppSecurityAnalyzer;
import dev.codewitness.cpp.coverage.CppCoverageProvider;
import dev.codewitness.cpp.dependency.CppDependencyAnalyzer;
import dev.codewitness.cpp.generate.CppGeneratedTestRunner;
import dev.codewitness.cpp.generate.CppScenarioTestGenerator;
import dev.codewitness.cpp.mutation.CppMutationTester;

/**
 * Wires the C and C++ adapter and its providers into the verification core.
 *
 * <p>This is the only place that knows which concrete C and C++ tools exist. The engine sees
 * {@link dev.codewitness.core.spi.LanguageAdapter} and the provider interfaces, so substituting another
 * mutation tester or adding an external analyzer next to the built-in rule table is a change to this class
 * and nothing else.
 *
 * <p>Every provider resolves the run's sandboxed runner from the {@link dev.codewitness.core.spi.VerificationContext}
 * at execution time, so nothing is captured at wiring time, and there is one build system - CMake - so no
 * per-build-system wiring is needed the way the Kotlin adapter needs it for Maven and Gradle.
 */
public final class CppAdapterBundle {

    private CppAdapterBundle() {
    }

    /** Registers the C and C++ language adapter and every CMake verification provider. */
    public static void register(
            LanguageAdapterRegistry languages, VerificationProviderRegistry providers, boolean includeAnalysis) {

        languages.register(new CppLanguageAdapter());
        CppLanguageAdapter.PROVIDERS.registerInto(providers, includeAnalysis);
    }
}
