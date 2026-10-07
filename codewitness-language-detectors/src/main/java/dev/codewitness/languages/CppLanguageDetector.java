package dev.codewitness.languages;

import java.util.List;
import java.util.Optional;

/**
 * Recognises {@code C or C++} projects.
 *
 * <p>C and C++ are no longer in the position this detector exists for: {@code codewitness-adapter-cpp}
 * claims a C or C++ tree built by CMake and reports it as {@code cpp}. Declaring that here means a tree the
 * adapter verified is not described a second time as present and unverifiable, which would contradict the
 * rest of the report.
 *
 * <p>The detector is still registered, and still looks for a build descriptor and a source file, because the
 * suppression only applies when the adapter actually claimed the tree. A C or C++ project built by a
 * hand-written {@code Makefile}, by Meson or by Autotools is exactly what is left for this detector to
 * speak for, and so is a CMake project whose sources the adapter's own scan skipped. Both are reported here
 * rather than being silently absent from the report.
 */
public final class CppLanguageDetector extends DescriptorLanguageDetector {

    public CppLanguageDetector() {
        super("cpp", "C or C++", List.of("CMakeLists.txt", "meson.build", "Makefile.am"),
                List.of(".cpp", ".cc", ".cxx", ".hpp", ".hh", ".c"));
    }

    /** @return {@code cpp}, because an adapter for that language decides the verdicts itself. */
    @Override
    public Optional<String> verifiedAsAdapterLanguage() {
        return Optional.of("cpp");
    }
}
