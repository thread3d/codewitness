package dev.codewitness.languages;

import java.util.List;
import java.util.Optional;

/**
 * Recognises Pascal projects, which an adapter verifies.
 *
 * <p>This detector exists for the classpath that does not carry the Pascal adapter: it then reports
 * Pascal as present and not verified, which is honest when nothing can verify it. With the adapter
 * present it declares as much through {@link #verifiedAsAdapterLanguage()}, so a tree the adapter
 * claimed is not described a second time as unverifiable. Keeping it registered means that removing
 * the adapter from the classpath degrades to a named limitation rather than to silence.
 *
 * <p>The suffixes are the ones Free Pascal and the Delphi lineage accept: {@code .pas} and {@code .pp}
 * for units, {@code .lpr} and {@code .dpr} for programs, and {@code .p} for the older sources a
 * compiler still accepts. The detector cannot compile any of them, so naming the tree is the whole of
 * what it can say.
 */
public final class PascalLanguageDetector extends DescriptorLanguageDetector {

    public PascalLanguageDetector() {
        super("pascal", "Pascal", List.of(), List.of(".pas", ".pp", ".p", ".lpr", ".dpr"));
    }

    @Override
    public Optional<String> verifiedAsAdapterLanguage() {
        return Optional.of("pascal");
    }
}
