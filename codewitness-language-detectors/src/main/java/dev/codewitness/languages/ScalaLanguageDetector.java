package dev.codewitness.languages;

import java.util.List;

/**
 * Recognises {@code Scala} projects, which CodeWitness has no adapter for.
 *
 * <p>Reported as present and not verified. Nothing here builds, tests or analyses anything; it exists so
 * that a tree containing {@code Scala} code does not have every verdict computed as though that code
 * were absent.
 */
public final class ScalaLanguageDetector extends DescriptorLanguageDetector {

    public ScalaLanguageDetector() {
        super("scala", "Scala", List.of("build.sbt"), List.of(".scala", ".sbt"));
    }
}
