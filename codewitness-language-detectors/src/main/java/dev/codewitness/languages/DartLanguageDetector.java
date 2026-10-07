package dev.codewitness.languages;

import java.util.List;

/**
 * Recognises {@code Dart} projects, which CodeWitness has no adapter for.
 *
 * <p>Reported as present and not verified. Nothing here builds, tests or analyses anything; it exists so
 * that a tree containing {@code Dart} code does not have every verdict computed as though that code
 * were absent.
 */
public final class DartLanguageDetector extends DescriptorLanguageDetector {

    public DartLanguageDetector() {
        super("dart", "Dart", List.of("pubspec.yaml"), List.of(".dart"));
    }
}
