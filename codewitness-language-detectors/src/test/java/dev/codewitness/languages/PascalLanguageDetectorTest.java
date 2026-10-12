package dev.codewitness.languages;

import dev.codewitness.core.spi.LanguageDetectorRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recognition of Pascal, which an adapter verifies.
 *
 * <p>The detector exists for the classpath without the adapter, so it must recognise every suffix Free
 * Pascal accepts without being so eager that it claims a tree of unrelated files.
 */
class PascalLanguageDetectorTest {

    @TempDir
    Path root;

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, content);
    }

    @Test
    @DisplayName("a unit under a source directory is recognised by its suffix")
    void recognisesUnits() throws IOException {
        write("src/ledger.pas", "unit ledger; interface implementation end.\n");

        assertEquals("src/ledger.pas", new PascalLanguageDetector().evidence(root).orElseThrow());
    }

    @Test
    @DisplayName("every suffix Free Pascal accepts names the language")
    void recognisesEverySuffix() throws IOException {
        for (String suffix : List.of("pas", "pp", "p", "lpr", "dpr")) {
            write("src/example." + suffix, "begin end.\n");
            assertTrue(new PascalLanguageDetector().evidence(root).isPresent(), suffix);
            Files.delete(root.resolve("src/example." + suffix));
        }
    }

    @Test
    @DisplayName("a project with no Pascal in it is not claimed from a name it merely shares")
    void doesNotClaimWhatIsNotThere() throws IOException {
        write("README.md", "A project with no Pascal in it.\n");
        write("src/Main.java", "class Main {}\n");

        assertTrue(new PascalLanguageDetector().evidence(root).isEmpty());
    }

    @Test
    @DisplayName("dependencies, build output and sample trees are not mistaken for the project's own Pascal")
    void ignoresVendoredAndSampleTrees() throws IOException {
        write("node_modules/some-package/thing.pas", "unit thing; interface implementation end.\n");
        write("target/generated/thing.pas", "unit thing; interface implementation end.\n");
        write("vendor/thing.pp", "unit thing; interface implementation end.\n");
        write("fixtures/sample/thing.pas", "unit thing; interface implementation end.\n");

        assertTrue(new PascalLanguageDetector().evidence(root).isEmpty());
    }

    @Test
    @DisplayName("the detector is discovered through ServiceLoader, as every other one is")
    void discoveryFindsThePascalDetector() {
        List<String> ids = new LanguageDetectorRegistry().discover().ids();

        assertTrue(ids.contains("pascal"), ids.toString());
        assertFalse(ids.contains("java"), "a language with an adapter does not need a detector here: " + ids);
    }
}
