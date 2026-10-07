package dev.codewitness.languages;

import java.util.List;

/**
 * Recognises {@code PHP} projects, which CodeWitness has no adapter for.
 *
 * <p>Reported as present and not verified. Nothing here builds, tests or analyses anything; it exists so
 * that a tree containing {@code PHP} code does not have every verdict computed as though that code
 * were absent.
 */
public final class PhpLanguageDetector extends DescriptorLanguageDetector {

    public PhpLanguageDetector() {
        super("php", "PHP", List.of("composer.json", "artisan", "phpunit.xml", "phpunit.xml.dist"), List.of(".php"));
    }
}
