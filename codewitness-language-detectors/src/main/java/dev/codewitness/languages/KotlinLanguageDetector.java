package dev.codewitness.languages;

import java.util.List;

/**
 * Recognises {@code Kotlin} projects, which CodeWitness has no adapter for.
 *
 * <p>Reported as present and not verified. Nothing here builds, tests or analyses anything; it exists so
 * that a tree containing {@code Kotlin} code does not have every verdict computed as though that code
 * were absent.
 */
public final class KotlinLanguageDetector extends DescriptorLanguageDetector {

    public KotlinLanguageDetector() {
        super("kotlin", "Kotlin", List.of(), List.of(".kt"));
    }
}
