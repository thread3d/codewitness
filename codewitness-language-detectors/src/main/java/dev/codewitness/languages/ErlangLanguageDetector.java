package dev.codewitness.languages;

import java.util.List;

/**
 * Recognises {@code Erlang} projects, which CodeWitness has no adapter for.
 *
 * <p>Reported as present and not verified. Nothing here builds, tests or analyses anything; it exists so
 * that a tree containing {@code Erlang} code does not have every verdict computed as though that code
 * were absent.
 */
public final class ErlangLanguageDetector extends DescriptorLanguageDetector {

    public ErlangLanguageDetector() {
        super("erlang", "Erlang", List.of("rebar.config", "rebar.lock"), List.of(".erl", ".hrl"));
    }
}
