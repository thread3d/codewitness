package dev.codewitness.go.mutation;

import dev.codewitness.support.mutation.TextMutator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Go lexical shape, exercised through the shared mutator: it must find operator sites without
 * corrupting comments, interpreted strings, raw strings or rune literals. A mutant that does not
 * compile tells us nothing about test strength, and a mutant inside a literal would change data
 * rather than behaviour.
 */
class GoLexisTest {

    /** The shared mutator, reading Go through the adapter's own lexis. */
    private final TextMutator mutator = new TextMutator(GoLexis.INSTANCE);

    @Test
    @DisplayName("binary operators, comparison boundaries and boolean literals are sites")
    void findsMutatableOperators() {
        String source = """
                package sample

                func value(a int, b int) int {
                    if a + b > 10 {
                        return a * b
                    }
                    return a - b % 3
                }

                func flag() bool { return true }
                """;

        List<TextMutator.Site> sites = mutator.sites(source);

        assertEquals(List.of("+", ">", "*", "-", "%", "true"),
                sites.stream().map(TextMutator.Site::operator).toList(), sites.toString());
        assertEquals(List.of(4, 4, 5, 7, 7, 10),
                sites.stream().map(TextMutator.Site::line).toList(), sites.toString());
    }

    @Test
    @DisplayName("comments, interpreted strings and raw strings are not mutated")
    void ignoresNonCode() {
        String source = """
                package sample

                // a - b in a comment
                func value(a int, b int) int {
                    text := "a + b"
                    raw := `c * d`
                    _ = text
                    _ = raw
                    /* e < f */
                    return a * b
                }
                """;

        List<TextMutator.Site> sites = mutator.sites(source);

        assertEquals(List.of("*"), sites.stream().map(TextMutator.Site::operator).toList(), sites.toString());
        assertEquals(10, sites.getFirst().line(), sites.toString());
    }

    @Test
    @DisplayName("a raw string hides the operators on every line it spans")
    void ignoresOperatorsInsideRawStringSpanningLines() {
        String source = "package sample\n"
                + "\n"
                + "func value(a int, b int) int {\n"
                + "\traw := `start\n"
                + "a + b\n"
                + "end`\n"
                + "\treturn a * b\n"
                + "}\n";

        List<TextMutator.Site> sites = mutator.sites(source);

        assertEquals(1, sites.size(), sites.toString());
        assertEquals("*", sites.getFirst().operator(), sites.toString());
        // The raw string spans lines 4 to 6, so the first code after it is on line 7.
        assertEquals(7, sites.getFirst().line(), sites.toString());
        assertTrue(sites.stream().noneMatch(site -> site.operator().equals("+")), sites.toString());
    }

    @Test
    @DisplayName("applying a site flips exactly that operator")
    void appliesOneMutation() {
        String source = "package sample\n\nfunc value(a int, b int) int { return a + b }\n";
        TextMutator.Site site = mutator.sites(source).stream()
                .filter(candidate -> candidate.operator().equals("+"))
                .findFirst()
                .orElseThrow();

        String mutated = TextMutator.apply(source, site);

        assertEquals("package sample\n\nfunc value(a int, b int) int { return a - b }\n", mutated);
        assertTrue(mutated.contains("a - b"), mutated);
        assertFalse(mutated.contains("a + b"), mutated);
        assertEquals(1, differingCharacters(source, mutated), mutated);
    }

    @Test
    @DisplayName("applying a comparison boundary changes one token without shifting the rest")
    void appliesComparisonBoundaryMutation() {
        String source = "package sample\n\nfunc less(a int, b int) bool { return a < b }\n";
        TextMutator.Site site = mutator.sites(source).stream()
                .filter(candidate -> candidate.operator().equals("<"))
                .findFirst()
                .orElseThrow();

        String mutated = TextMutator.apply(source, site);

        assertEquals("package sample\n\nfunc less(a int, b int) bool { return a <= b }\n", mutated);
        assertEquals(source.substring(0, site.offset()), mutated.substring(0, site.offset()), mutated);
        assertEquals(source.substring(site.offset() + site.operator().length()),
                mutated.substring(site.offset() + site.replacement().length()), mutated);
    }

    @Test
    @DisplayName("a unary operator is not treated as a binary mutation site")
    void ignoresUnaryOperators() {
        String source = "package sample\n\nfunc negate(a int) int { return -a }\n";

        assertTrue(mutator.sites(source).isEmpty(), mutator.sites(source).toString());
    }

    @Test
    @DisplayName("an operator inside a rune literal is not a site")
    void ignoresRuneLiterals() {
        String source = "package sample\n\nfunc runeValue() rune { return '+' }\n";

        assertTrue(mutator.sites(source).isEmpty(), mutator.sites(source).toString());
    }

    private static int differingCharacters(String before, String after) {
        assertEquals(before.length(), after.length(), "expected a same-length mutation");
        int differences = 0;
        for (int i = 0; i < before.length(); i++) {
            if (before.charAt(i) != after.charAt(i)) {
                differences++;
            }
        }
        return differences;
    }
}
