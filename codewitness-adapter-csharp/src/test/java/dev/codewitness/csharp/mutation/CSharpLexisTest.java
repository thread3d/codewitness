package dev.codewitness.csharp.mutation;

import dev.codewitness.support.mutation.TextMutator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for C#'s lexical shape as the shared mutator reads it.
 *
 * <p>The interesting cases are the ones where C# differs from the languages the mutator was first
 * written for: verbatim and raw strings, an interpolation prefix, a signed exponent, and the unsigned
 * shift whose first two characters would otherwise look like a comparison.
 */
class CSharpLexisTest {

    private final TextMutator mutator = new TextMutator(CSharpLexis.INSTANCE);

    private List<String> descriptions(String source) {
        return mutator.sites(source).stream().map(TextMutator.Site::describe).toList();
    }

    @Test
    @DisplayName("only a spaced, binary operator is a mutation site")
    void findsBinaryOperators() {
        assertEquals(List.of("+ -> -"), descriptions("var a = b + c;"));
        assertEquals(List.of(), descriptions("var a = b+c;"),
                "an unspaced operator is how a unary or generic use is written, and mutating it would not compile");
        assertEquals(List.of("<= -> <"), descriptions("var a = b <= c;"));
        assertEquals(List.of("== -> !=", "!= -> =="), descriptions("var a = b == c && d != e;")
                .stream().filter(site -> site.contains("=")).toList());
        assertEquals(List.of("== -> !=", "true -> false"), descriptions("var a = (b == true);"));
    }

    @Test
    @DisplayName("nothing inside a string, a verbatim string or a comment is a site")
    void neverTouchesLiteralsOrComments() {
        assertEquals(List.of(), descriptions("var s = \"a + b\";"));
        assertEquals(List.of(), descriptions("var s = @\"a + b\";"));
        assertEquals(List.of(), descriptions("var s = $\"a + {b}\";"),
                "an interpolated string is consumed whole, holes included");
        assertEquals(List.of(), descriptions("// a + b"));
        assertEquals(List.of(), descriptions("/* a + b */"));
        assertEquals(List.of("+ -> -"), descriptions("var s = \"a + b\"; var t = x + y;"));
    }

    @Test
    @DisplayName("a raw string may span lines and is not read as code")
    void skipsRawStrings() {
        String source = "var s = \"\"\"\na + b\n\"\"\";\nvar t = x + y;\n";
        assertEquals(List.of("+ -> -"), descriptions(source));
    }

    @Test
    @DisplayName("a signed exponent is part of the number, not a binary operator")
    void skipsSignedExponents() {
        assertEquals(List.of("+ -> -"), descriptions("var a = 1e-3 + b;"));
        assertEquals(List.of(), descriptions("var a = 1e-3;"));
        assertEquals(List.of("+ -> -"), descriptions("var a = 0x1F + 1;"),
                "the hexadecimal literal is one token, and the operator beside it is still a site");
    }

    @Test
    @DisplayName("assignment compounds, increments and the unsigned shift are not split")
    void keepsCompoundsTogether() {
        assertEquals(List.of(), descriptions("a += b;"));
        assertEquals(List.of(), descriptions("i++;"));
        assertEquals(List.of(), descriptions("a >>= b;"));
        assertEquals(List.of(), descriptions("a >>>= b;"),
                "the three-character shift must win over >>, or its > would be read as >= and mutated");
        assertEquals(List.of(), descriptions("a ??= b;"));
        assertEquals(List.of("+ -> -"), descriptions("a = b + c;"));
    }

    @Test
    @DisplayName("a boolean literal is flipped wherever it appears")
    void flipsBooleanLiterals() {
        assertEquals(List.of("false -> true"), descriptions("var a = false;"));
        assertTrue(descriptions("var a = ffalse;").isEmpty(),
                "an identifier that merely contains the word is not the literal");
    }
}
