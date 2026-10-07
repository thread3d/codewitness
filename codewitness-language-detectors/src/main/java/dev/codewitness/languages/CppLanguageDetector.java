package dev.codewitness.languages;

import java.util.List;

/**
 * Recognises {@code C or C++} projects, which CodeWitness has no adapter for.
 *
 * <p>Reported as present and not verified. Nothing here builds, tests or analyses anything; it exists so
 * that a tree containing {@code C or C++} code does not have every verdict computed as though that code
 * were absent.
 */
public final class CppLanguageDetector extends DescriptorLanguageDetector {

    public CppLanguageDetector() {
        super("cpp", "C or C++", List.of("CMakeLists.txt", "meson.build", "Makefile.am"), List.of(".cpp", ".cc", ".cxx", ".hpp", ".hh"));
    }
}
