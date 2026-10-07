package dev.codewitness.languages;

import java.util.List;

/**
 * Recognises {@code Swift} projects, which CodeWitness has no adapter for.
 *
 * <p>Reported as present and not verified. Nothing here builds, tests or analyses anything; it exists so
 * that a tree containing {@code Swift} code does not have every verdict computed as though that code
 * were absent.
 */
public final class SwiftLanguageDetector extends DescriptorLanguageDetector {

    public SwiftLanguageDetector() {
        super("swift", "Swift", List.of("Package.swift", "Podfile"), List.of(".swift"));
    }
}
