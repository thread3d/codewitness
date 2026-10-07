package dev.codewitness.rust.mutation;

import dev.codewitness.support.mutation.TextMutator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Rust lexical shape, exercised through the shared mutator: it must find operator sites without
 * corrupting strings, comments, lifetimes or generic type arguments, because a mutant that does not
 * compile tells us nothing about test strength.
 */
class RustLexisTest {

    /** The shared mutator, reading Rust through the adapter's own lexis. */
    private final TextMutator mutator = new TextMutator(RustLexis.INSTANCE);

    @Test
    @DisplayName("binary operators, comparison boundaries and boolean literals are sites")
    void findsMutatableOperators() {
        String source = """
                pub fn value(a: i64, b: i64) -> i64 {
                    if a + b > 10 { a * b } else { a - b % 3 }
                }

                pub fn flag() -> bool { true }
                """;

        List<TextMutator.Site> sites = mutator.sites(source);
        List<String> operators = sites.stream().map(TextMutator.Site::operator).toList();

        assertTrue(operators.contains("+"), operators.toString());
        assertTrue(operators.contains(">"), operators.toString());
        assertTrue(operators.contains("*"), operators.toString());
        assertTrue(operators.contains("-"), operators.toString());
        assertTrue(operators.contains("%"), operators.toString());
        assertTrue(operators.contains("true"), operators.toString());
    }

    @Test
    @DisplayName("strings, comments and generic arguments are not mutated")
    void ignoresNonCode() {
        String source = """
                // a - b in a comment
                pub fn value(a: i64, b: i64) -> i64 {
                    let text = "a + b";
                    let items = Vec::<String>::new();
                    let _ = items;
                    let _ = text;
                    a * b
                }
                """;

        List<TextMutator.Site> sites = mutator.sites(source);
        List<String> operators = sites.stream().map(TextMutator.Site::operator).toList();

        assertEquals(List.of("*"), operators, operators.toString());
    }

    @Test
    @DisplayName("applying a site flips exactly that operator")
    void appliesOneMutation() {
        String source = "pub fn value(a: i64, b: i64) -> i64 { a + b }\n";
        TextMutator.Site site = mutator.sites(source).stream()
                .filter(candidate -> candidate.operator().equals("+"))
                .findFirst()
                .orElseThrow();

        String mutated = TextMutator.apply(source, site);

        assertTrue(mutated.contains("a - b"), mutated);
        assertFalse(mutated.contains("a + b"), mutated);
    }

    @Test
    @DisplayName("a unary operator is not treated as a binary mutation site")
    void ignoresUnaryOperators() {
        String source = "pub fn negate(a: i64) -> i64 { -a }\n";

        assertTrue(mutator.sites(source).isEmpty(), mutator.sites(source).toString());
    }
}
