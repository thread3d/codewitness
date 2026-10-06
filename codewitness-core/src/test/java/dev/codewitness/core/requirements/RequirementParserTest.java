package dev.codewitness.core.requirements;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requirement ids must be stable across runs and the natural language text must survive parsing
 * verbatim: evidence from one run is compared with evidence from another by id, and the text is the
 * authoritative statement that everything else is an interpretation of.
 */
class RequirementParserTest {

    private static final String SOURCE = "requirements.md";

    @Test
    @DisplayName("'# R-001' produces a requirement whose id is R-001")
    void markdownHeadingProducesId() {
        RequirementSet set = RequirementParser.parseMarkdown("""
                # R-001
                A customer may withdraw at most EUR 1,000 per day.
                """, SOURCE);

        assertEquals(List.of("R-001"), set.ids());
        assertTrue(set.require("R-001").description().contains("A customer may withdraw"), set.require("R-001").description());
    }

    @Test
    @DisplayName("'## R-001 Rolling withdrawal limit' keeps the remainder as the title")
    void markdownHeadingWithTitleKeepsTitle() {
        RequirementSet set = RequirementParser.parseMarkdown("""
                ## R-001 Rolling withdrawal limit
                The limit applies over any rolling 24 hour window.
                """, SOURCE);

        Requirement requirement = set.require("R-001");
        assertEquals("Rolling withdrawal limit", requirement.title());
        assertTrue(requirement.description().startsWith("The limit applies"), requirement.description());
    }

    @Test
    @DisplayName("free text below the heading is the authoritative description, verbatim")
    void freeTextBecomesAuthoritativeDescription() {
        String body = "A customer may withdraw at most \u20ac1,000 during any rolling 24-hour period.";
        RequirementSet set = RequirementParser.parseMarkdown("# R-001\n" + body + "\n", SOURCE);

        assertEquals(body, set.require("R-001").description());
    }

    @Test
    @DisplayName("multi-line bodies are preserved and trimmed, not collapsed")
    void multiLineBodyIsPreserved() {
        RequirementSet set = RequirementParser.parseMarkdown("""
                # R-001
                First line of the requirement.
                Second line of the requirement.
                """, SOURCE);

        String description = set.require("R-001").description();
        assertTrue(description.contains("First line"), description);
        assertTrue(description.contains("Second line"), description);
    }

    @Test
    @DisplayName("several headings produce several requirements in document order")
    void severalHeadingsProduceSeveralRequirements() {
        RequirementSet set = RequirementParser.parseMarkdown("""
                # R-001
                First requirement.
                # R-002
                Second requirement.
                # R-003
                Third requirement.
                """, SOURCE);

        assertEquals(List.of("R-001", "R-002", "R-003"), set.ids());
        assertEquals(3, set.size());
    }

    @Test
    @DisplayName("ids are assigned positionally when a document has none")
    void positionalIdsAreAssignedWhenDocumentHasNoIds() {
        RequirementSet set = RequirementParser.parseMarkdown("""
                The ledger must never allow an overdraft.

                Every withdrawal must be recorded with a timestamp.
                """, SOURCE);

        assertEquals(List.of("R-001", "R-002"), set.ids());
        assertTrue(set.require("R-001").description().contains("overdraft"));
        assertTrue(set.require("R-002").description().contains("timestamp"));
    }

    @Test
    @DisplayName("positionally assigned ids are flagged in the requirement attributes")
    void positionalIdsAreFlaggedAsGenerated() {
        RequirementSet set = RequirementParser.parseMarkdown("Only one paragraph, no id.", SOURCE);

        assertEquals(List.of("R-001"), set.ids());
        assertEquals("assigned by CodeWitness from document order", set.require("R-001").attributes().get("id_source"));
    }

    @Test
    @DisplayName("the same document always yields the same ids")
    void idsAreStableAcrossParses() {
        String document = """
                # R-010
                Alpha.
                # R-011
                Beta.
                """;

        RequirementSet first = RequirementParser.parseMarkdown(document, SOURCE);
        RequirementSet second = RequirementParser.parseMarkdown(document, SOURCE);

        assertEquals(first.ids(), second.ids());
        assertEquals(first.require("R-010").contentHash(), second.require("R-010").contentHash());
    }

    @Test
    @DisplayName("blank content yields an empty requirement set")
    void blankContentYieldsEmptySet() {
        assertTrue(RequirementParser.parseMarkdown("   \n\n", SOURCE).isEmpty());
    }

    @Test
    @DisplayName("a lowercase 'r-001' is not treated as an id and becomes positional text")
    void lowercaseIdIsNotAnIdentifier() {
        RequirementSet set = RequirementParser.parseMarkdown("# r-001\nlowercase heading", SOURCE);

        assertEquals(List.of("R-001"), set.ids());
        assertEquals("assigned by CodeWitness from document order", set.require("R-001").attributes().get("id_source"));
    }

    @Test
    @DisplayName("the heading line itself is not part of the description")
    void headingLineIsNotPartOfDescription() {
        RequirementSet set = RequirementParser.parseMarkdown("# R-001 Title\nBody text.", SOURCE);

        assertFalse(set.require("R-001").description().contains("R-001"));
        assertEquals("Body text.", set.require("R-001").description());
    }

    @Test
    @DisplayName("YAML requirements parse ids, titles, properties, paths and tests")
    void yamlParsesAllDeclaredFields() {
        RequirementSet set = RequirementParser.parseYaml("""
                requirements:
                  - id: R-001
                    title: Withdrawal limit
                    description: A customer may withdraw at most 1000 per day.
                    properties:
                      - type: upper_bound
                        value: 1000
                      - manual
                    paths:
                      - src/main/java/com/example/account/Ledger.java
                    tests:
                      - com.example.account.LedgerTest
                """, "requirements.yaml");

        Requirement requirement = set.require("R-001");
        assertEquals("Withdrawal limit", requirement.title());
        assertEquals("A customer may withdraw at most 1000 per day.", requirement.description());
        assertEquals(2, requirement.properties().size());
        assertEquals("upper_bound", requirement.properties().get(0).type());
        assertTrue(ObligationPlanner.hasUsableBound(requirement.properties().get(0)));
        assertEquals("src/main/java/com/example/account/Ledger.java", requirement.attributes().get("paths"));
        assertEquals("com.example.account.LedgerTest", requirement.attributes().get("tests"));
    }

    @Test
    @DisplayName("YAML entries without an id get a positional id")
    void yamlEntryWithoutIdGetsPositionalId() {
        RequirementSet set = RequirementParser.parseYaml("""
                requirements:
                  - title: Untitled
                    description: Something must hold.
                """, "requirements.yaml");

        assertEquals(List.of("R-001"), set.ids());
    }

    @Test
    @DisplayName("a YAML document that is a bare list of requirements is accepted")
    void yamlBareListIsAccepted() {
        RequirementSet set = RequirementParser.parseYaml("""
                - id: R-042
                  description: A bare list entry.
                """, "requirements.yaml");

        assertEquals(List.of("R-042"), set.ids());
    }

    @Test
    @DisplayName("a YAML requirement without description falls back to its title")
    void yamlWithoutDescriptionFallsBackToTitle() {
        RequirementSet set = RequirementParser.parseYaml("""
                requirements:
                  - id: R-001
                    title: Title only
                """, "requirements.yaml");

        assertEquals("Title only", set.require("R-001").description());
    }

    @Test
    @DisplayName("the manual flag becomes a manual property")
    void yamlManualFlagBecomesProperty() {
        RequirementSet set = RequirementParser.parseYaml("""
                requirements:
                  - id: R-001
                    description: Needs a human.
                    manual: true
                """, "requirements.yaml");

        assertTrue(set.require("R-001").properties().stream().anyMatch(p -> p.type().equals("manual")));
        assertEquals(1, set.require("R-001").properties().size());
    }

    @Test
    @DisplayName("heuristics run while parsing Markdown requirements")
    void heuristicsRunWhileParsingMarkdown() {
        RequirementSet set = RequirementParser.parseMarkdown("""
                # R-001
                A customer may withdraw at most \u20ac1,000 during any rolling 24-hour period.
                Operations may be issued concurrently from several threads.
                """, SOURCE);

        List<String> types = set.require("R-001").properties().stream().map(RequirementProperty::type).toList();
        assertTrue(types.contains("upper_bound"), types.toString());
        assertTrue(types.contains("concurrency"), types.toString());
        assertEquals(0, HeuristicPropertyExtractor
                .property(set.require("R-001"), "upper_bound").orElseThrow().decimalValue("value")
                .compareTo(new java.math.BigDecimal("1000")));
    }

    @Test
    @DisplayName("parseFile chooses YAML for a .yaml file and Markdown otherwise")
    void parseFileChoosesFormatFromExtension(@TempDir Path dir) throws IOException {
        Path yaml = dir.resolve("requirements.yaml");
        Files.writeString(yaml, "requirements:\n  - id: R-001\n    description: yaml text\n", StandardCharsets.UTF_8);
        Path markdown = dir.resolve("requirements.md");
        Files.writeString(markdown, "# R-001\nmarkdown text\n", StandardCharsets.UTF_8);

        assertEquals("yaml text", RequirementParser.parseFile(yaml).require("R-001").description());
        assertEquals("markdown text", RequirementParser.parseFile(markdown).require("R-001").description());
    }

    @Test
    @DisplayName("parseFile reports an unreadable file rather than returning an empty set")
    void parseFileReportsMissingFile(@TempDir Path dir) {
        Path missing = dir.resolve("does-not-exist.md");

        assertThrows(java.io.UncheckedIOException.class, () -> RequirementParser.parseFile(missing));
    }

    @Test
    @DisplayName("RequirementSet lookup and require() failure behave predictably")
    void requirementSetLookup() {
        RequirementSet set = RequirementParser.parseMarkdown("# R-001\nText.", SOURCE);

        assertTrue(set.byId("R-001").isPresent());
        assertTrue(set.byId("R-999").isEmpty());
        assertEquals("R-001", set.require("R-001").id());
        assertThrows(IllegalArgumentException.class, () -> set.require("R-999"));
    }

    @Test
    @DisplayName("contentHash is stable for the same text and changes with the text")
    void contentHashIsStableAndSensitive() {
        Requirement a = Requirement.builder("R-001", "Same text.").build();
        Requirement b = Requirement.builder("R-001", "Same text.").build();
        Requirement c = Requirement.builder("R-001", "Different text.").build();

        assertEquals(a.contentHash(), b.contentHash());
        assertFalse(a.contentHash().equals(c.contentHash()));
    }

    @Test
    @DisplayName("withObligations replaces obligations without touching the description")
    void withObligationsKeepsEverythingElse() {
        Requirement requirement = Requirement.builder("R-001", "Authoritative text.")
                .title("Title")
                .source("file.md", 7)
                .build();

        Requirement updated = requirement.withObligations(List.of(
                VerificationObligation.of("OB-001", "R-001", "check it", ObligationKind.GENERIC,
                        ObligationOrigin.REQUIREMENT_TEXT)));

        assertEquals(requirement.description(), updated.description());
        assertEquals(requirement.title(), updated.title());
        assertEquals(requirement.sourceLine(), updated.sourceLine());
        assertEquals(1, updated.obligations().size());
    }
}
