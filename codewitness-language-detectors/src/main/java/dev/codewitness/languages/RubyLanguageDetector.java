package dev.codewitness.languages;

import java.util.List;

/**
 * Recognises {@code Ruby} projects, which CodeWitness has no adapter for.
 *
 * <p>Reported as present and not verified. Nothing here builds, tests or analyses anything; it exists so
 * that a tree containing {@code Ruby} code does not have every verdict computed as though that code
 * were absent.
 */
public final class RubyLanguageDetector extends DescriptorLanguageDetector {

    public RubyLanguageDetector() {
        super("ruby", "Ruby", List.of("Gemfile", "Rakefile", "config.ru", "Guardfile"), List.of(".rb", ".gemspec", ".rake"));
    }
}
