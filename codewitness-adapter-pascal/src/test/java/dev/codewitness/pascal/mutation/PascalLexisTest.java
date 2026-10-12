package dev.codewitness.pascal.mutation;

import dev.codewitness.support.mutation.TextMutator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The mutator's lexer treats Pascal's operators, comments, strings and exponents as Pascal means them. */
class PascalLexisTest {

    private final TextMutator mutator = new TextMutator(PascalLexis.INSTANCE);

    private List<TextMutator.Site> sites(String source) {
        return mutator.sites(source);
    }

    @Test
    @DisplayName("a spaced binary operator is a site and flips to its counterpart")
    void flipsOperators() {
        List<TextMutator.Site> sites = sites("x := a + b;");

        assertEquals(1, sites.size());
        assertEquals("+", sites.getFirst().operator());
        assertEquals("-", sites.getFirst().replacement());
    }

    @Test
    @DisplayName("Pascal's own comparison and equality operators are sites")
    void flipsComparisons() {
        assertTrue(sites("if a <= b then ;").stream().anyMatch(site -> site.operator().equals("<=")));
        assertTrue(sites("if a >= b then ;").stream().anyMatch(site -> site.operator().equals(">=")));
        assertTrue(sites("if a <> b then ;").stream().anyMatch(site -> site.operator().equals("<>")));
        assertTrue(sites("if a = b then ;").stream().anyMatch(site -> site.operator().equals("=")));
    }

    @Test
    @DisplayName("assignment is not mutated into anything, and is not read as a bare equals")
    void assignmentIsNotASite() {
        assertTrue(sites("x := a;").isEmpty(), sites("x := a;").toString());
    }

    @Test
    @DisplayName("operators inside every Pascal comment form, and inside strings, are left alone")
    void ignoresCommentsAndStrings() {
        assertTrue(sites("{ a + b }").isEmpty());
        assertTrue(sites("(* a - b *)").isEmpty());
        assertTrue(sites("// a * b").isEmpty());
        assertTrue(sites("s := 'a + b';").isEmpty());
    }

    @Test
    @DisplayName("a signed exponent is part of the number and does not expose a minus operator")
    void exponentSignIsNotAnOperator() {
        assertTrue(sites("x := 1.5E-3;").isEmpty(), sites("x := 1.5E-3;").toString());
    }

    @Test
    @DisplayName("the word operators are identifiers and are still mutated")
    void flipsWordOperators() {
        assertTrue(sites("if a and b then ;").stream().anyMatch(site -> site.operator().equals("and")));
        assertTrue(sites("x := a div b;").stream().anyMatch(site -> site.operator().equals("div")));
    }

    @Test
    @DisplayName("the mutation table names Pascal's operators and no others")
    void mutationTableIsPascal() {
        assertTrue(PascalLexis.INSTANCE.mutations().containsKey("="));
        assertTrue(PascalLexis.INSTANCE.mutations().containsKey("<>"));
        assertFalse(PascalLexis.INSTANCE.mutations().containsKey("=="));
        assertFalse(PascalLexis.INSTANCE.mutations().containsKey("!="));
    }
}
