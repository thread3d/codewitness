package dev.codewitness.languages;

import java.util.List;
import java.util.Optional;

/**
 * Recognises {@code PHP} projects, which an adapter now verifies.
 *
 * <p>This detector used to stand in for the language: it reported PHP as present and not verified,
 * which was honest when nothing could verify it and is the whole of what CodeWitness could then say. PHP
 * now has an adapter, so this class declares as much through
 * {@link #verifiedAsAdapterLanguage()} and a tree the adapter claimed is no longer described a second
 * time as unverifiable.
 *
 * <p>It is kept registered rather than deleted, and still names PHP for the trees the adapter does not
 * claim. The adapter drives Composer and claims a tree that holds PHP source or a PHP descriptor; this
 * detector's evidence is narrower than the adapter's claim, so the two cannot disagree about the same
 * tree. Keeping it here means that if the adapter is ever removed from the classpath, PHP goes back to
 * being reported as present and unverifiable rather than disappearing from the report entirely.
 */
public final class PhpLanguageDetector extends DescriptorLanguageDetector {

    public PhpLanguageDetector() {
        super("php", "PHP", List.of("composer.json", "artisan", "phpunit.xml", "phpunit.xml.dist"), List.of(".php"));
    }

    @Override
    public Optional<String> verifiedAsAdapterLanguage() {
        return Optional.of("php");
    }
}
