package dev.codewitness.cpp.mutation;

import dev.codewitness.cpp.CppSourceScan;
import dev.codewitness.support.mutation.TextMutator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the C and C++ side of the shared source mutator.
 *
 * <p>The mutator belongs to {@code codewitness-adapter-support}; what this adapter supplies is the
 * language's lexical shape, so every test here asserts a property of the emitted sites rather than of the
 * mutator: an operator inside a literal or a comment is never a site, an operator that is not surrounded by
 * value expressions is never a site, and the C++-only punctuation is not split into the operators that make
 * it up.
 */
class CppLexisTest {

    private final TextMutator mutator = new TextMutator(CppLexis.INSTANCE);

    private List<String> descriptions(String source) {
        return mutator.sites(source).stream().map(TextMutator.Site::describe).toList();
    }

    @Test
    @DisplayName("a binary arithmetic, comparison or boolean operator is a mutation site")
    void findsBinaryOperators() {
        assertEquals(List.of("+ -> -"), descriptions("int total = a + b;\n"));
        assertEquals(List.of("<= -> <"), descriptions("if (a <= b) {}\n"));
        assertEquals(List.of("== -> !="), descriptions("if (a == b) {}\n"));
        assertEquals(List.of("&& -> ||"), descriptions("if (a && b) {}\n"));
    }

    @Test
    @DisplayName("an operator inside a literal or a comment is not a site")
    void neverMutatesTextThatIsNotCode() {
        assertEquals(List.of(), descriptions("const char* s = \"a + b\";\n"));
        assertEquals(List.of(), descriptions("// a + b\n"));
        assertEquals(List.of(), descriptions("/* a + b */\n"));
        assertEquals(List.of(), descriptions("const char* s = R\"(a + b)\";\n"));
        assertEquals(List.of(), descriptions("const char* s = \"a \\\" + \\\" b\";\n"));
        assertEquals(List.of(), descriptions("char c = '+';\n"));
    }

    @Test
    @DisplayName("a unary operator, an increment and a compound assignment are not binary sites")
    void neverMutatesAnOperatorThatIsNotBinary() {
        assertEquals(List.of(), descriptions("int a = -b;\n"));
        assertEquals(List.of(), descriptions("a++;\n"));
        assertEquals(List.of(), descriptions("a += b;\n"));
        assertEquals(List.of(), descriptions("int* p = &a;\n"));
        // Whitespace on both sides is the rule that keeps a formatter's output the only thing mutated.
        assertEquals(List.of(), descriptions("int a = 1+2;\n"));
    }

    @Test
    @DisplayName("the operator's own punctuation is not split into the operators that make it up")
    void doesNotSplitCompoundPunctuation() {
        assertEquals(List.of(), descriptions("auto x = a-> b;\n"));
        assertEquals(List.of(), descriptions("auto x = std::move(a);\n"));
        assertEquals(List.of(), descriptions("auto x = a <=> b;\n"));
        assertEquals(List.of(), descriptions("template <typename T> void f();\n"));
    }

    @Test
    @DisplayName("a boolean literal is a site wherever it appears")
    void flipsBooleanLiterals() {
        assertEquals(List.of("true -> false"), descriptions("bool flag = true;\n"));
        assertEquals(List.of("false -> true"), descriptions("return false;\n"));
    }

    @Test
    @DisplayName("an exponent's sign is part of the number, not a binary operator")
    void numericLiteralsAreConsumedWhole() {
        assertEquals(List.of(), descriptions("double x = 1e-3;\n"));
        assertEquals(List.of(), descriptions("int mask = 0xFF;\n"));
        assertEquals(List.of(), descriptions("long x = 1'000'000;\n"));
        assertEquals(List.of("+ -> -"), descriptions("double x = 1 + 2;\n"),
                "a real binary operator is still found beside a numeric literal");
    }

    @Test
    @DisplayName("the lexis reports the shapes the shared mutator asks it for")
    void reportsItsShape() {
        assertEquals("+", CppLexis.INSTANCE.mutations().get("-"));
        assertEquals("-", CppLexis.INSTANCE.mutations().get("+"));
        assertTrue(CppLexis.INSTANCE.mutations().containsKey("true"));
        assertTrue(CppLexis.INSTANCE.valueEnd().contains(")"));
        assertTrue(CppLexis.INSTANCE.valueStart().contains("("));
        assertTrue(List.of(CppLexis.INSTANCE.threeCharOperators()).contains("<=>"));
        assertTrue(List.of(CppLexis.INSTANCE.twoCharOperators()).contains("->"));
        assertTrue(CppLexis.INSTANCE.singleCharOperators().contains("+"));
    }

    @Test
    @DisplayName("the lexis reads literals through the same scanner the rules use")
    void sharesTheScannerWithTheRuleTable() {
        String source = "R\"delim(anything)delim\"";
        int end = CppLexis.INSTANCE.skipLiteral(source, 0);

        assertEquals(source.length(), end);
        assertEquals(CppSourceScan.literalEnd(source, 0), end);

        // Not a literal at all, which the mutator reads as -1 and then scans as ordinary code.
        assertEquals(-1, CppLexis.INSTANCE.skipLiteral("int x = 1;", 0));
    }

    @Test
    @DisplayName("mutating a site changes exactly the operator and nothing else")
    void appliesOneSite() {
        String source = "if (a <= b) { return a + b; }\n";
        TextMutator.Site site = mutator.sites(source).getFirst();

        String mutated = TextMutator.apply(source, site);

        assertEquals("if (a < b) { return a + b; }\n", mutated);
        assertFalse(mutated.equals(source));
    }

    @Test
    @DisplayName("every site carries the line it is on, so a survivor can be pointed at")
    void reportsLineNumbers() {
        String source = "int a = 1;\nint b = a + 1;\n";

        TextMutator.Site site = mutator.sites(source).getFirst();

        assertEquals(2, site.line());
        assertTrue(source.substring(site.offset()).startsWith("+"), source);
    }
}
