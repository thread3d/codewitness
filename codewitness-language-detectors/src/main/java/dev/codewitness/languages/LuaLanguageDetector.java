package dev.codewitness.languages;

import java.util.List;

/**
 * Recognises {@code Lua} projects, which CodeWitness has no adapter for.
 *
 * <p>Reported as present and not verified. Nothing here builds, tests or analyses anything; it exists so
 * that a tree containing {@code Lua} code does not have every verdict computed as though that code
 * were absent.
 */
public final class LuaLanguageDetector extends DescriptorLanguageDetector {

    public LuaLanguageDetector() {
        super("lua", "Lua", List.of("rockspec"), List.of(".rockspec", ".lua"));
    }
}
