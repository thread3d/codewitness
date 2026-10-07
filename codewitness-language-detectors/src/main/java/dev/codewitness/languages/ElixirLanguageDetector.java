package dev.codewitness.languages;

import java.util.List;

/**
 * Recognises {@code Elixir} projects, which CodeWitness has no adapter for.
 *
 * <p>Reported as present and not verified. Nothing here builds, tests or analyses anything; it exists so
 * that a tree containing {@code Elixir} code does not have every verdict computed as though that code
 * were absent.
 */
public final class ElixirLanguageDetector extends DescriptorLanguageDetector {

    public ElixirLanguageDetector() {
        super("elixir", "Elixir", List.of("mix.exs"), List.of(".ex", ".exs"));
    }
}
