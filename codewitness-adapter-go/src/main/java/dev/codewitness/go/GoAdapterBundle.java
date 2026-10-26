package dev.codewitness.go;

import dev.codewitness.core.spi.LanguageAdapterRegistry;
import dev.codewitness.core.spi.VerificationProviderRegistry;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.go.analysis.GoHeuristicAnalyzer;
import dev.codewitness.go.analysis.GoSecurityAnalyzer;
import dev.codewitness.go.coverage.GoCoverageProvider;
import dev.codewitness.go.dependency.GoDependencyAnalyzer;
import dev.codewitness.go.generate.GoGeneratedTestRunner;
import dev.codewitness.go.generate.GoScenarioTestGenerator;
import dev.codewitness.go.mutation.GoMutationTester;

/**
 * Wires the Go adapter and its providers into the verification core.
 *
 * <p>This is the only place that knows which concrete Go tools exist. The engine sees
 * {@code LanguageAdapter} and the provider interfaces, so substituting another mutation tester or
 * adding an external linter next to the built-in analyzer is a change to this class and nothing
 * else.
 *
 * <p>Every provider resolves the project's {@code go} executable from the run context, and there is
 * one Go build system ({@code go} itself), so no per-build-system wiring is needed.
 */
public final class GoAdapterBundle {

    private GoAdapterBundle() {
    }

    /** Registers the Go language adapter and every Go verification provider. */
    public static void register(
            LanguageAdapterRegistry languages, VerificationProviderRegistry providers, boolean includeAnalysis) {

        languages.register(new GoLanguageAdapter());
        GoLanguageAdapter.PROVIDERS.registerInto(providers, includeAnalysis);
    }
}
