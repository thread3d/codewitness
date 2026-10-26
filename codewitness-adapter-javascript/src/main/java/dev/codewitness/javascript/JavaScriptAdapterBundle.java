package dev.codewitness.javascript;

import dev.codewitness.core.spi.LanguageAdapterRegistry;
import dev.codewitness.core.spi.VerificationProviderRegistry;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.javascript.analysis.JavaScriptHeuristicAnalyzer;
import dev.codewitness.javascript.analysis.JavaScriptSecurityAnalyzer;
import dev.codewitness.javascript.coverage.JavaScriptCoverageProvider;
import dev.codewitness.javascript.dependency.JavaScriptDependencyAnalyzer;
import dev.codewitness.javascript.generate.JavaScriptGeneratedTestRunner;
import dev.codewitness.javascript.generate.JavaScriptScenarioTestGenerator;
import dev.codewitness.javascript.mutation.JavaScriptMutationTester;

/**
 * Wires the JavaScript adapter and its providers into the verification core.
 *
 * <p>This is the only place that knows which concrete JavaScript tools exist. The engine sees
 * {@code LanguageAdapter} and the provider interfaces, so substituting another mutation tester or adding
 * an external linter next to the built-in analyzer is a change to this class and nothing else.
 *
 * <p>Every provider resolves the project's Node executable from the run context, and there is one Node
 * toolchain, so no per-build-system wiring is needed.
 */
public final class JavaScriptAdapterBundle {

    private JavaScriptAdapterBundle() {
    }

    /** Registers the JavaScript language adapter and every JavaScript verification provider. */
    public static void register(
            LanguageAdapterRegistry languages, VerificationProviderRegistry providers, boolean includeAnalysis) {

        languages.register(new JavaScriptLanguageAdapter());
        JavaScriptLanguageAdapter.PROVIDERS.registerInto(providers, includeAnalysis);
    }
}
