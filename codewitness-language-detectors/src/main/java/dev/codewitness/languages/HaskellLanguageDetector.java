package dev.codewitness.languages;

import java.util.List;

/**
 * Recognises {@code Haskell} projects, which CodeWitness has no adapter for.
 *
 * <p>Reported as present and not verified. Nothing here builds, tests or analyses anything; it exists so
 * that a tree containing {@code Haskell} code does not have every verdict computed as though that code
 * were absent.
 */
public final class HaskellLanguageDetector extends DescriptorLanguageDetector {

    public HaskellLanguageDetector() {
        super("haskell", "Haskell", List.of("stack.yaml", "cabal.project"), List.of(".cabal", ".hs"));
    }
}
