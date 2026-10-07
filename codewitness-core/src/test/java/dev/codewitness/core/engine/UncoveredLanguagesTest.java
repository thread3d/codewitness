package dev.codewitness.core.engine;

import dev.codewitness.core.model.LanguageId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Naming the languages present in a project that nothing in the run could verify. */
class UncoveredLanguagesTest {

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
    @DisplayName("a language with no adapter is named, with the file that gives it away")
    void namesALanguageNothingCovers() throws IOException {
        write("Gemfile", "source 'https://rubygems.org'\n");
        write("lib/ledger.rb", "VALUE = 1\n");

        List<UncoveredLanguages.Uncovered> uncovered =
                UncoveredLanguages.detect(root, Set.of(LanguageId.JAVA));

        assertEquals(1, uncovered.size(), uncovered.toString());
        assertEquals("Ruby", uncovered.getFirst().language());
        assertEquals("Gemfile", uncovered.getFirst().evidence());
    }

    @Test
    @DisplayName("a language an adapter verified is not reported as uncovered")
    void ignoresLanguagesThatWereVerified() throws IOException {
        write("pom.xml", "<project/>");
        write("Cargo.toml", "[package]\nname = \"x\"\n");

        List<UncoveredLanguages.Uncovered> uncovered =
                UncoveredLanguages.detect(root, Set.of(LanguageId.JAVA));

        assertEquals(List.of("Rust"), uncovered.stream().map(UncoveredLanguages.Uncovered::language).toList(),
                "the Java side was verified and must not be reported as missing: " + uncovered);
    }

    @Test
    @DisplayName("package.json counts as covered when either Node adapter ran")
    void aNodeProjectIsCoveredByEitherNodeAdapter() throws IOException {
        write("package.json", "{\"name\": \"app\"}");
        write("src/app.ts", "export {};\n");

        assertEquals(List.of(), UncoveredLanguages.detect(root, Set.of(LanguageId.TYPESCRIPT)));
        assertEquals(List.of("JavaScript or TypeScript"),
                UncoveredLanguages.detect(root, Set.of(LanguageId.JAVA)).stream()
                        .map(UncoveredLanguages.Uncovered::language).toList());
    }

    @Test
    @DisplayName("dependencies, build output and sample projects do not make a project look like another language")
    void ignoresVendoredAndGeneratedTrees() throws IOException {
        write("package.json", "{\"name\": \"app\"}");
        write("node_modules/some-dep/index.js", "module.exports = 1;\n");
        write("vendor/bundle/gem.rb", "VALUE = 1\n");
        write("fixtures/go-withdrawal-ledger/ledger.go", "package main\n");
        write("testdata/sample.rb", "VALUE = 1\n");
        write("main.py", "VALUE = 1\n");

        List<UncoveredLanguages.Uncovered> uncovered =
                UncoveredLanguages.detect(root, Set.of(LanguageId.JAVASCRIPT));

        assertEquals(List.of("Python"), uncovered.stream().map(UncoveredLanguages.Uncovered::language).toList(),
                uncovered.toString());
    }

    @Test
    @DisplayName("an empty project uncovers nothing")
    void emptyProjectIsSilent() {
        assertEquals(List.of(), UncoveredLanguages.detect(root, Set.of(LanguageId.JAVA)));
        assertEquals(List.of(), UncoveredLanguages.detect(null, Set.of()));
    }

    @Test
    @DisplayName("the limitation says what was not verified and what the verdicts do cover")
    void limitationSpellsOutTheGap() throws IOException {
        write("Gemfile", "source 'https://rubygems.org'\n");

        List<String> limitations = UncoveredLanguages.limitations(
                UncoveredLanguages.detect(root, Set.of(LanguageId.JAVA)), List.of("java"));

        assertEquals(1, limitations.size());
        String text = limitations.getFirst();
        assertTrue(text.contains("Ruby"), text);
        assertTrue(text.contains("Gemfile"), text);
        assertTrue(text.contains("cover only java"), text);
    }
}
