package dev.codewitness.languages;

import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.spi.LanguageDetector;
import dev.codewitness.core.spi.LanguageDetectorRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recognition of the languages CodeWitness has no adapter for.
 *
 * <p>These detectors verify nothing, so the tests are about the two ways they can be wrong: missing a
 * language that is plainly there, and claiming one from a file that happens to share a name.
 */
class CommonLanguageDetectorsTest {

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
    @DisplayName("a language's own descriptor names it, with the file that gives it away")
    void readsDescriptors() throws IOException {
        write("Gemfile", "source 'https://rubygems.org'\n");

        assertEquals("Gemfile", new RubyLanguageDetector().evidence(root).orElseThrow());
    }

    @Test
    @DisplayName("a language with no descriptor of its own is recognised from a source file below the root")
    void readsSourceFiles() throws IOException {
        write("lib/ledger.rb", "VALUE = 1\n");

        assertEquals("lib/ledger.rb", new RubyLanguageDetector().evidence(root).orElseThrow());
    }

    @Test
    @DisplayName("a project that does not contain the language is not claimed from a name it merely shares")
    void doesNotClaimWhatIsNotThere() throws IOException {
        write("README.md", "A project with no Ruby in it.\n");

        assertTrue(new RubyLanguageDetector().evidence(root).isEmpty());
        assertTrue(new LuaLanguageDetector().evidence(root).isEmpty());
        assertTrue(new DartLanguageDetector().evidence(root).isEmpty());
    }

    @Test
    @DisplayName("dependency, build-output and sample-project trees are not mistaken for the project's code")
    void ignoresVendoredAndSampleTrees() throws IOException {
        write("node_modules/some-gem/lib.rb", "VALUE = 1\n");
        write("vendor/bundle/thing.rb", "VALUE = 1\n");
        write("fixtures/sample/ledger.rb", "VALUE = 1\n");
        write("testdata/sample.rb", "VALUE = 1\n");

        assertTrue(new RubyLanguageDetector().evidence(root).isEmpty());
    }

    @Test
    @DisplayName("the detectors are discovered through ServiceLoader, as adapters are")
    void discoveryFindsEveryRegisteredDetector() {
        LanguageDetectorRegistry registry = new LanguageDetectorRegistry().discover();

        List<String> ids = registry.ids();
        assertTrue(ids.contains("ruby"), ids.toString());
        assertTrue(ids.contains("php"), ids.toString());
        assertTrue(ids.contains("csharp"), ids.toString());
        assertFalse(ids.contains("java"), "a language with an adapter does not need a detector here: " + ids);
        assertTrue(ids.size() >= 10, "the common languages should all be recognised: " + ids);
    }

    @Test
    @DisplayName("a tree holding several unverifiable languages reports each one once")
    void reportsEveryLanguagePresent() throws IOException {
        write("Gemfile", "source 'https://rubygems.org'\n");
        write("composer.json", "{}\n");
        write("app/Main.csproj", "<Project/>\n");

        List<LanguageDetectorRegistry.Detection> detections =
                new LanguageDetectorRegistry().discover().detect(root, Set.of(LanguageId.JAVA));

        List<String> languages = detections.stream().map(LanguageDetectorRegistry.Detection::displayName).toList();
        assertTrue(languages.containsAll(List.of("Ruby", "PHP", "C# or F#")), languages.toString());
    }

    @Test
    @DisplayName("every detector names a language and an id, and any adapter language it claims is well formed")
    void everyDetectorIsWellFormed() {
        for (LanguageDetector detector : new LanguageDetectorRegistry().discover().all()) {
            assertFalse(detector.id().isBlank(), detector.getClass().getName());
            assertFalse(detector.language().isBlank(), detector.getClass().getName());
            // A detector may declare the id of an adapter language: that is how a language which later gains
            // an adapter stops being reported twice, and DotNetLanguageDetector does exactly that for csharp.
            // Whether the adapter it names really exists cannot be checked from here - this module depends on
            // no adapter at all - so it is checked in codewitness-cli, where both sides are on the classpath.
            // What can be checked here is that the claim is shaped like a language id rather than like prose.
            detector.verifiedAsAdapterLanguage().ifPresent(id -> assertTrue(
                    id.matches("[a-z][a-z0-9-]*"),
                    detector.getClass().getName() + " declares the adapter language \"" + id
                            + "\", which is not shaped like a language id"));
        }
    }
}
