package dev.codewitness.rust;

import dev.codewitness.core.spi.LanguageAdapterRegistry;
import dev.codewitness.core.spi.VerificationProviderRegistry;

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

    /**
     * Registers the Rust language adapter and every Rust verification provider.
     *
     * <p>The providers come from the same declaration the adapter is built from, so what is registered and what the
     * adapter says it can do are one list.
     *
     * @param languages       adapter registry
     * @param providers       provider registry
     * @param includeAnalysis whether to register the analysis providers
     */
    public static void register(
            LanguageAdapterRegistry languages, VerificationProviderRegistry providers, boolean includeAnalysis) {

        languages.register(new RustLanguageAdapter());
        RustLanguageAdapter.rustProviders().registerInto(providers, includeAnalysis);
    }
}
