package dev.codewitness.languages;

import java.util.List;
import java.util.Optional;

/**
 * Recognises {@code Swift} projects, which an adapter now verifies.
 *
 * <p>This detector used to stand in for the language: it reported Swift as present and not verified,
 * which was honest when nothing could verify it and is the whole of what CodeWitness could then say.
 * Swift now has an adapter, so this class declares as much through
 * {@link #verifiedAsAdapterLanguage()} and a tree the adapter claimed is no longer described a second
 * time as unverifiable.
 *
 * <p>It is kept registered rather than deleted, and still names Swift for the trees the adapter does not
 * claim. The adapter drives Swift Package Manager and claims a tree that holds Swift source or a
 * {@code Package.swift}; this detector's evidence - a {@code Package.swift} or a {@code Podfile}, or any
 * {@code .swift} file - is narrower for a CocoaPods tree the adapter does not drive, so the two cannot
 * disagree about the same tree. Keeping it here means that if the adapter is ever removed from the
 * classpath, Swift goes back to being reported as present and unverifiable rather than disappearing from
 * the report entirely.
 */
public final class SwiftLanguageDetector extends DescriptorLanguageDetector {

    public SwiftLanguageDetector() {
        super("swift", "Swift", List.of("Package.swift", "Podfile"), List.of(".swift"));
    }

    @Override
    public Optional<String> verifiedAsAdapterLanguage() {
        return Optional.of("swift");
    }
}
