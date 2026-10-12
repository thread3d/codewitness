package dev.codewitness.verilog.mutation;

import dev.codewitness.support.mutation.TextMutator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Verilog lexical shape the shared mutator reads. */
class VerilogLexisTest {

    private static final TextMutator MUTATOR = new TextMutator(VerilogLexis.INSTANCE);

    private static List<String> operators(String source) {
        return MUTATOR.sites(source).stream().map(TextMutator.Site::describe).toList();
    }

    @Test
    @DisplayName("a spaced binary operator is a site")
    void binaryOperator() {
        List<TextMutator.Site> sites = MUTATOR.sites("assign y = a + b;\n");
        assertEquals(1, sites.size());
        assertEquals("+ -> -", sites.getFirst().describe());
        assertEquals(1, sites.getFirst().line());
    }

    @Test
    @DisplayName("a nonblocking assignment is a site, because <= is also a comparison")
    void nonblockingIsASite() {
        List<TextMutator.Site> sites = MUTATOR.sites("always @(posedge clk) q <= d;\n");
        assertTrue(sites.stream().anyMatch(site -> site.operator().equals("<=")), sites.toString());
    }

    @Test
    @DisplayName("a based literal is consumed whole and exposes no operator")
    void basedLiteralIsWhole() {
        assertTrue(MUTATOR.sites("assign y = 4'b1010;\n").isEmpty(),
                "a based literal has no operator site");
        // A real operator next to a based literal is still found.
        assertTrue(MUTATOR.sites("assign y = 4'b1010 + 8'h0F;\n").stream()
                .anyMatch(site -> site.operator().equals("+")));
    }

    @Test
    @DisplayName("an exponent sign is not a bogus subtraction")
    void exponentIsNotAnOperator() {
        assertTrue(MUTATOR.sites("real r = 1.5e-3;\n").isEmpty(), "the exponent sign is part of the literal");
    }

    @Test
    @DisplayName("operators inside comments and strings are not sites")
    void commentsAndStringsAreIgnored() {
        assertTrue(MUTATOR.sites("// a + b\nassign y = \"x + y\";\n").isEmpty(),
                "neither a comment nor a string is code");
    }

    @Test
    @DisplayName("the case-equality operators are one token each")
    void caseEqualityIsOneToken() {
        List<String> rendered = operators("assign y = (a === b) ? 1'b1 : 1'b0;\n");
        assertFalse(rendered.contains("== -> !="), rendered.toString());
    }

    @Test
    @DisplayName("the operator table covers the arithmetic, comparison and boolean flips")
    void operatorTable() {
        var mutations = VerilogLexis.INSTANCE.mutations();
        assertEquals("-", mutations.get("+"));
        assertEquals("<=", mutations.get("<"));
        assertFalse(mutations.containsKey("<<<"), "the arithmetic shift is not in the mutation set");
        assertTrue(VerilogLexis.INSTANCE.twoCharOperators().length > 0);
    }
}
