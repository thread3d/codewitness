package dev.codewitness.vhdl.mutation;

import dev.codewitness.support.mutation.TextMutator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The lexical shape of VHDL as the shared mutator reads it. */
class VhdlLexisTest {

    private final TextMutator mutator = new TextMutator(VhdlLexis.INSTANCE);

    private List<TextMutator.Site> sites(String source) {
        return mutator.sites(source);
    }

    @Test
    @DisplayName("a comparison is a site and can be flipped")
    void comparisonIsASite() {
        List<TextMutator.Site> sites = sites("if amount < balance then");
        assertEquals(1, sites.size(), sites.toString());
        assertEquals("<", sites.getFirst().operator());
        assertEquals(">", sites.getFirst().replacement());
    }

    @Test
    @DisplayName("the signal assignment <= is consumed whole and never mutated")
    void signalAssignmentIsNotASite() {
        assertTrue(sites("y <= a and b;").isEmpty(), "<= must not expose < or =");
        assertTrue(sites("q <= to_unsigned(1, 32);").isEmpty());
    }

    @Test
    @DisplayName("the variable assignment := is consumed whole and never mutated")
    void variableAssignmentIsNotASite() {
        assertTrue(sites("x := 3;").isEmpty());
    }

    @Test
    @DisplayName("equality and inequality are flipped into one another")
    void equalityFlips() {
        assertEquals("=", sites("if x /= y then").getFirst().replacement());
        assertEquals("/=", sites("if x = y then").getFirst().replacement());
    }

    @Test
    @DisplayName("operators inside comments and strings are not sites")
    void commentsAndStringsAreIgnored() {
        assertTrue(sites("-- amount < balance\n").isEmpty());
        assertTrue(sites("report \"amount < balance\";").isEmpty());
        assertTrue(sites("/* amount < balance */").isEmpty());
    }

    @Test
    @DisplayName("a character literal is a literal, not an operator")
    void characterLiteralIsIgnored() {
        assertTrue(sites("if c = '<' then").stream().noneMatch(site -> site.operator().equals("<")));
    }

    @Test
    @DisplayName("a based literal is consumed whole and exposes no operator")
    void basedLiteralIsWhole() {
        assertTrue(sites("x <= 16#FF#;").isEmpty());
        List<TextMutator.Site> sites = sites("if x = 16#FF# then");
        assertEquals(1, sites.size(), sites.toString());
        assertEquals("=", sites.getFirst().operator());
    }

    @Test
    @DisplayName("an attribute mark is not read as the start of a character literal")
    void attributeIsNotALiteral() {
        // `clk'event`: the apostrophe is an attribute mark, so it is not a literal opener and the word
        // after it is a token of its own.
        assertEquals(-1, VhdlLexis.INSTANCE.skipLiteral("'event", 0));
        assertEquals(3, VhdlLexis.INSTANCE.skipLiteral("'<'", 0));
        // The comparison after the attribute is still a site.
        assertEquals("<", sites("if clk'event and x < y then").getFirst().operator());
    }

    @Test
    @DisplayName("a string with a doubled quote is consumed as one literal")
    void doubledQuoteString() {
        String source = "report \"a \"\"b\"\" c < d\";\nif x < y then";
        List<TextMutator.Site> sites = sites(source);
        assertEquals(1, sites.size(), sites.toString());
        assertEquals("<", sites.getFirst().operator());
    }
}
