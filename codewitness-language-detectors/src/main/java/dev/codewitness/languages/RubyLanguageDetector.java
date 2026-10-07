package dev.codewitness.languages;

import java.util.List;
import java.util.Optional;

/**
 * Recognises {@code Ruby} projects, which an adapter now verifies.
 *
 * <p>This detector used to stand in for the language: it reported Ruby as present and not verified,
 * which was honest when nothing could verify it and is the whole of what CodeWitness could then say. Ruby
 * now has an adapter, so this class declares as much through
 * {@link #verifiedAsAdapterLanguage()} and a tree the adapter claimed is no longer described a second
 * time as unverifiable.
 *
 * <p>It is kept registered rather than deleted, and still names Ruby for the trees the adapter does not
 * claim. The adapter drives Bundler and claims a tree that holds Ruby source or a Ruby descriptor; this
 * detector's evidence is narrower than the adapter's claim, so the two cannot disagree about the same
 * tree. Keeping it here means that if the adapter is ever removed from the classpath, Ruby goes back to
 * being reported as present and unverifiable rather than disappearing from the report entirely.
 */
public final class RubyLanguageDetector extends DescriptorLanguageDetector {

    public RubyLanguageDetector() {
        super("ruby", "Ruby", List.of("Gemfile", "Rakefile", "config.ru", "Guardfile"), List.of(".rb", ".gemspec", ".rake"));
    }

    @Override
    public Optional<String> verifiedAsAdapterLanguage() {
        return Optional.of("ruby");
    }
}
