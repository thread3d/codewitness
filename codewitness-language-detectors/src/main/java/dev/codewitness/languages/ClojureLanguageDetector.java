package dev.codewitness.languages;

import java.util.List;

/**
 * Recognises {@code Clojure} projects, which CodeWitness has no adapter for.
 *
 * <p>Reported as present and not verified. Nothing here builds, tests or analyses anything; it exists so
 * that a tree containing {@code Clojure} code does not have every verdict computed as though that code
 * were absent.
 */
public final class ClojureLanguageDetector extends DescriptorLanguageDetector {

    public ClojureLanguageDetector() {
        super("clojure", "Clojure", List.of("project.clj", "deps.edn"), List.of(".clj", ".cljs", ".cljc"));
    }
}
