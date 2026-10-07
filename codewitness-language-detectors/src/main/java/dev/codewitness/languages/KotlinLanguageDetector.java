package dev.codewitness.languages;

import java.util.List;
import java.util.Optional;

/**
 * Recognises {@code Kotlin} projects.
 *
 * <p>Kotlin is no longer in the position this detector exists for: {@code codewitness-adapter-kotlin}
 * claims Kotlin trees and reports them as {@code kotlin}. Declaring that here means a tree the adapter
 * verified is not described a second time as present and unverifiable, which would contradict the rest of
 * the report.
 *
 * <p>The detector is still registered, and still looks for a {@code .kt} file, because the suppression only
 * applies when the adapter actually claimed the tree. A Kotlin source file the adapter never saw - one
 * nested deeper than its own scan, or under a directory it skips - is reported here rather than being
 * silently absent.
 */
public final class KotlinLanguageDetector extends DescriptorLanguageDetector {

    public KotlinLanguageDetector() {
        super("kotlin", "Kotlin", List.of(), List.of(".kt"));
    }

    /** @return {@code kotlin}, because an adapter for that language decides the verdicts itself. */
    @Override
    public Optional<String> verifiedAsAdapterLanguage() {
        return Optional.of("kotlin");
    }
}
