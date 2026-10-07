package dev.codewitness.core.engine;

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
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Naming the languages present in a project that nothing in the run could verify.
 *
 * <p>The recognition of individual languages lives in detectors, which are discovered like adapters are;
 * what is tested here is the core's half of it - which detections are reported, which are suppressed because
 * an adapter already verified the language, and what the sentence says.
 */
class UncoveredLanguagesTest {

    @TempDir
    Path root;

    /** A detector that recognises one file name, so a test can place a language without a real project. */
    private static LanguageDetector detector(String id, String language, String file, String adapterLanguage) {
        return new LanguageDetector() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String language() {
                return language;
            }

            @Override
            public Optional<String> verifiedAsAdapterLanguage() {
                return Optional.ofNullable(adapterLanguage);
            }

            @Override
            public Optional<String> evidence(Path root) {
                return Files.isRegularFile(root.resolve(file)) ? Optional.of(file) : Optional.empty();
            }
        };
    }

    private void write(String name) throws IOException {
        Files.writeString(root.resolve(name), "x\n");
    }

    @Test
    @DisplayName("a language present with no adapter is reported, with the file that gives it away")
    void reportsALanguageNothingCovers() throws IOException {
        write("Gemfile");
        LanguageDetectorRegistry detectors =
                LanguageDetectorRegistry.of(detector("ruby", "Ruby", "Gemfile", null));

        List<LanguageDetectorRegistry.Detection> detections =
                detectors.detect(root, Set.of(LanguageId.JAVA));

        assertEquals(1, detections.size(), detections.toString());
        assertEquals("ruby", detections.getFirst().language().id());
        assertEquals("Ruby", detections.getFirst().displayName());
        assertEquals("Gemfile", detections.getFirst().evidence());
    }

    @Test
    @DisplayName("a detector for a language an adapter already verified is suppressed")
    void ignoresLanguagesAnAdapterVerified() throws IOException {
        write("package.json");
        LanguageDetectorRegistry detectors = LanguageDetectorRegistry.of(
                detector("javascript", "JavaScript or TypeScript", "package.json", "javascript"),
                detector("ruby", "Ruby", "Gemfile", null));
        write("Gemfile");

        List<LanguageDetectorRegistry.Detection> detections = detectors.detect(root, Set.of(LanguageId.JAVASCRIPT));

        assertEquals(List.of("ruby"), detections.stream()
                .map(detection -> detection.language().id()).toList(),
                "a language an adapter claimed must not also be reported as unverifiable: " + detections);
    }

    @Test
    @DisplayName("a detector that finds nothing reports nothing, and an absent registry is survived")
    void silentWhenNothingIsFound() {
        assertEquals(List.of(), LanguageDetectorRegistry.of(detector("ruby", "Ruby", "Gemfile", null))
                .detect(root, Set.of()));
        assertEquals(List.of(), new LanguageDetectorRegistry().detect(root, Set.of()));
        assertEquals(List.of(), new LanguageDetectorRegistry().detect(null, Set.of()));
    }

    @Test
    @DisplayName("the sentence says what was not verified and what the verdicts do cover")
    void limitationSpellsOutTheGap() throws IOException {
        write("Gemfile");
        LanguageDetectorRegistry detectors = LanguageDetectorRegistry.of(detector("ruby", "Ruby", "Gemfile", null));

        List<String> limitations = UncoveredLanguages.limitations(
                detectors, root, Set.of(LanguageId.JAVA), List.of("java"));

        assertEquals(1, limitations.size());
        String text = limitations.getFirst();
        assertTrue(text.contains("Ruby"), text);
        assertTrue(text.contains("Gemfile"), text);
        assertTrue(text.contains("cover only java"), text);
        assertTrue(text.contains("built, tested or analysed"), text);
    }

    @Test
    @DisplayName("with no verified language the sentence says so rather than naming an empty set")
    void limitationWithoutAVerifiedLanguage() throws IOException {
        write("Gemfile");
        LanguageDetectorRegistry detectors = LanguageDetectorRegistry.of(detector("ruby", "Ruby", "Gemfile", null));

        List<String> limitations = UncoveredLanguages.limitations(detectors, root, Set.of(), List.of());

        assertTrue(limitations.getFirst().contains("cover only no language"), limitations.toString());
    }
}
