package dev.codewitness.rust.mutation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mutator must find operator sites without corrupting strings, comments or generic type
 * arguments: a mutant that does not compile tells us nothing about test strength.
 */
class RustMutatorTest {

    @Test
    @DisplayName("binary operators, comparison boundaries and boolean literals are sites")
    void findsMutatableOperators() {
        String source = """
                pub fn value(a: i64, b: i64) -> i64 {
                    if a + b > 10 { a * b } else { a - b % 3 }
                }

                pub fn flag() -> bool { true }
                """;

        List<RustMutator.Site> sites = RustMutator.sites(source);
        List<String> operators = sites.stream().map(RustMutator.Site::operator).toList();

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

        List<RustMutator.Site> sites = RustMutator.sites(source);
        List<String> operators = sites.stream().map(RustMutator.Site::operator).toList();

        assertEquals(List.of("*"), operators, operators.toString());
    }

    @Test
    @DisplayName("applying a site flips exactly that operator")
    void appliesOneMutation() {
        String source = "pub fn value(a: i64, b: i64) -> i64 { a + b }\n";
        RustMutator.Site site = RustMutator.sites(source).stream()
                .filter(candidate -> candidate.operator().equals("+"))
                .findFirst()
                .orElseThrow();

        String mutated = RustMutator.apply(source, site);

        assertTrue(mutated.contains("a - b"), mutated);
        assertFalse(mutated.contains("a + b"), mutated);
    }

    @Test
    @DisplayName("a unary operator is not treated as a binary mutation site")
    void ignoresUnaryOperators() {
        String source = "pub fn negate(a: i64) -> i64 { -a }\n";

        assertTrue(RustMutator.sites(source).isEmpty(), RustMutator.sites(source).toString());
    }
}
