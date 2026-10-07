package dev.codewitness.languages;

import java.util.List;

/**
 * Recognises {@code Perl} projects, which CodeWitness has no adapter for.
 *
 * <p>Reported as present and not verified. Nothing here builds, tests or analyses anything; it exists so
 * that a tree containing {@code Perl} code does not have every verdict computed as though that code
 * were absent.
 */
public final class PerlLanguageDetector extends DescriptorLanguageDetector {

    public PerlLanguageDetector() {
        super("perl", "Perl", List.of("cpanfile", "Makefile.PL", "Build.PL"), List.of(".pl", ".pm"));
    }
}
