package dev.codewitness.kotlin.mutation;

import dev.codewitness.support.mutation.TextMutator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for Kotlin's lexical shape as the shared mutator reads it.
 *
 * <p>The interesting cases are the ones where Kotlin differs from the languages the mutator was first
 * written for: referential equality, the range operators, the elvis and safe-call operators, raw strings and
 * template holes, and a numeric literal whose decimal point is also the range operator.
 */
class KotlinLexisTest {

    private final TextMutator mutator = new TextMutator(KotlinLexis.INSTANCE);

    private List<String> descriptions(String source) {
        return mutator.sites(source).stream().map(TextMutator.Site::describe).toList();
    }

    @Test
    @DisplayName("only a spaced, binary operator is a mutation site")
    void findsBinaryOperators() {
        assertEquals(List.of("+ -> -"), descriptions("val a = b + c"));
        assertTrue(descriptions("val a = b+c").isEmpty(),
                "an unspaced operator is how a unary or generic use is written, and mutating it would not compile");
        assertEquals(List.of("<= -> <"), descriptions("val a = b <= c"));
        assertEquals(List.of("&& -> ||", "!= -> =="), descriptions("val a = b && c != d"));
        assertEquals(List.of("== -> !=", "true -> false"), descriptions("val a = (b == true)"));
    }

    @Test
    @DisplayName("referential equality is not the equality operator")
    void referentialEqualityIsNotMutated() {
        assertTrue(descriptions("val a = b === c").isEmpty(),
                "=== is a different operator, and mutating its inner == would change what is compared");
        assertTrue(descriptions("val a = b !== c").isEmpty());
        assertEquals(List.of("!= -> =="), descriptions("val a = b != c"));
    }

    @Test
    @DisplayName("the range operators keep their < and are never split")
    void rangeOperatorsAreNotSplit() {
        assertTrue(descriptions("for (i in 1..<10) { }").isEmpty(),
                "..< is one token; its < must not be read as a comparison: " + descriptions("for (i in 1..<10) { }"));
        assertTrue(descriptions("for (i in 1..10) { }").isEmpty());
    }

    @Test
    @DisplayName("the elvis, safe-call, non-null and callable-reference operators stay whole")
    void twoCharacterOperatorsStayWhole() {
        assertTrue(descriptions("val a = b ?: c").isEmpty());
        assertTrue(descriptions("val a = b?.c").isEmpty());
        assertTrue(descriptions("val a = b!!").isEmpty());
        assertTrue(descriptions("val f = ::ledger").isEmpty());
        assertTrue(descriptions("val a = b -> c").isEmpty());
    }

    @Test
    @DisplayName("nothing inside a literal, a template hole or a comment is a site")
    void neverTouchesLiteralsOrComments() {
        assertTrue(descriptions("val s = \"a + b\"").isEmpty());
        assertTrue(descriptions("val s = \"\"\"a + b\"\"\"").isEmpty());
        assertTrue(descriptions("val s = \"total ${x + y}\"").isEmpty(),
                "a template hole is consumed as part of the literal");
        assertTrue(descriptions("val c = 'x'").isEmpty());
        assertTrue(descriptions("// a + b").isEmpty());
        assertTrue(descriptions("/* a + b */").isEmpty());
        assertEquals(List.of("+ -> -"), descriptions("val s = \"a + b\"; val t = x + y"));
    }

    @Test
    @DisplayName("a raw string may span lines and is not read as code")
    void skipsRawStrings() {
        assertEquals(List.of("+ -> -"),
                descriptions("val s = \"\"\"\na + b\n\"\"\"\nval t = x + y\n"));
        assertEquals(List.of("+ -> -"),
                descriptions("val s = \"\"\"${x + y}\"\"\"\nval t = x + y\n"));
    }

    @Test
    @DisplayName("a signed exponent is part of the number, not a binary operator")
    void skipsSignedExponents() {
        assertEquals(List.of("+ -> -"), descriptions("val a = 1e-3 + b"));
        assertTrue(descriptions("val a = 1e-3").isEmpty());
        assertEquals(List.of("+ -> -"), descriptions("val a = 0x1F + 1"),
                "the hexadecimal literal is one token, and the operator beside it is still a site");
        assertEquals(List.of("+ -> -"), descriptions("val a = 1_000L + 1"));
    }

    @Test
    @DisplayName("the decimal point of a number is not the range operator")
    void aDecimalsPointIsNotARange() {
        assertEquals(List.of("+ -> -"), descriptions("val a = 1.5 + b"));
        assertTrue(descriptions("for (i in 1..5) { }").isEmpty(),
                "1..5 is a number, a range and a number, so nothing here is an operator site");
    }

    @Test
    @DisplayName("a member call after a number is not swallowed by the numeric literal")
    void aNumberFollowedByACallIsStillACall() {
        // `1.toString()` must not become a single numeric token, whose absence would hide the call.
        assertEquals(List.of(), descriptions("val s = 1.toString()"));
    }

    @Test
    @DisplayName("assignment compounds and increments are not split")
    void keepsCompoundsTogether() {
        assertTrue(descriptions("a += b").isEmpty());
        assertTrue(descriptions("i++").isEmpty());
        assertTrue(descriptions("a = b").isEmpty());
        assertEquals(List.of("+ -> -"), descriptions("a = b + c"));
    }

    @Test
    @DisplayName("a boolean literal is flipped wherever it appears")
    void flipsBooleanLiterals() {
        assertEquals(List.of("false -> true"), descriptions("val a = false"));
        assertTrue(descriptions("val a = ffalse").isEmpty(),
                "an identifier that merely contains the word is not the literal");
    }

    @Test
    @DisplayName("a backtick identifier is a name, not an operator")
    void backtickedNamesAreNotMutated() {
        assertTrue(descriptions("val `a + b` = 1").isEmpty());
    }
}
