package dev.codewitness.swift.mutation;

import dev.codewitness.support.mutation.TextMutator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Swift lexical shape, exercised through the shared mutator.
 *
 * <p>Each test states what Swift does that a scanner written for the C family would get wrong: a raw
 * string closes only at a quote followed by the same number of hashes, an interpolation hole holds real
 * Swift, an apostrophe opens nothing because there is no character literal, and the identity pair
 * {@code ===} / {@code !==} is not the equality pair. A mutant that does not compile or that changes a
 * literal tells us nothing about test strength.
 */
class SwiftLexisTest {

    /** The shared mutator, reading Swift through the adapter's own lexis. */
    private final TextMutator mutator = new TextMutator(SwiftLexis.INSTANCE);

    @Test
    @DisplayName("the operator table is Swift's own, with the identity pair flipped within itself")
    void theOperatorTableIsSwiftsOwn() {
        Map<String, String> mutations = SwiftLexis.INSTANCE.mutations();

        assertEquals("-", mutations.get("+"));
        assertEquals("+", mutations.get("-"));
        assertEquals("/", mutations.get("*"));
        assertEquals("*", mutations.get("/"));
        assertEquals("*", mutations.get("%"));
        assertEquals("<=", mutations.get("<"));
        assertEquals("<", mutations.get("<="));
        assertEquals(">=", mutations.get(">"));
        assertEquals(">", mutations.get(">="));
        assertEquals("!=", mutations.get("=="));
        assertEquals("==", mutations.get("!="));
        assertEquals("!==", mutations.get("==="), "the identity pair is not mapped to value equality");
        assertEquals("===", mutations.get("!=="));
        assertEquals("||", mutations.get("&&"));
        assertEquals("&&", mutations.get("||"));
        assertEquals("false", mutations.get("true"));
        assertEquals("true", mutations.get("false"));

        assertEquals(17, mutations.size(), mutations.toString());
        assertEquals(java.util.Set.of(")", "]", "}"), SwiftLexis.INSTANCE.valueEnd());
        assertEquals(java.util.Set.of("(", "[", "{", "-", "+", "!", "~"), SwiftLexis.INSTANCE.valueStart());
        assertEquals("+-*/%<>=&|!^?~", SwiftLexis.INSTANCE.singleCharOperators());
        assertEquals(List.of("...", "..<", "===", "!==", "<<=", ">>=", "&<<", "&>>"),
                List.of(SwiftLexis.INSTANCE.threeCharOperators()));
        assertEquals(List.of("->", "==", "!=", "<=", ">=", "&&", "||", "??", "?.", "~=", "+=", "-=", "*=",
                        "/=", "%=", "&=", "|=", "^=", "<<", ">>", "&+", "&-", "&*"),
                List.of(SwiftLexis.INSTANCE.twoCharOperators()));
    }

    @Test
    @DisplayName("an ordinary string takes escapes and an interpolation hole is consumed whole")
    void ordinaryStringsTakeEscapesAndHoles() {
        assertEquals(7, SwiftLexis.INSTANCE.skipLiteral("\"a + b\"", 0));
        assertEquals(8, SwiftLexis.INSTANCE.skipLiteral("\"a \\\" b\"", 0),
                "an escaped quote does not close the literal");
        assertEquals(14, SwiftLexis.INSTANCE.skipLiteral("\"a \\(b + c) d\"", 0),
                "the \\(... ) hole is consumed as part of the literal");
        assertEquals(17, SwiftLexis.INSTANCE.skipLiteral("\"x \\(row[\"k\"]) y\"", 0),
                "a nested string inside the hole must not have its quote counted as the closer");
        assertEquals(13, SwiftLexis.INSTANCE.skipLiteral("\"never closed", 0),
                "an unterminated string runs to the end of the file, the conservative direction");
    }

    @Test
    @DisplayName("a \"\"\" multi-line string is one literal, and #-quoted raw strings close at their width")
    void multilineAndRawStrings() {
        String multiline = "\"\"\"\nbody + more\n\"\"\"";
        assertEquals(multiline.length(), SwiftLexis.INSTANCE.skipLiteral(multiline, 0));
        assertEquals(15, SwiftLexis.INSTANCE.skipLiteral("\"\"\"\na \"b\" c\n\"\"\"", 0),
                "a single or double quote inside a multi-line string is ordinary text");

        assertEquals(15, SwiftLexis.INSTANCE.skipLiteral("#\"a + \\#(b) c\"#", 0),
                "a raw string closes at a quote followed by the same number of hashes");
        assertEquals(11, SwiftLexis.INSTANCE.skipLiteral("##\"a \" b\"##", 0),
                "a quote inside a ## string is content until two hashes follow it");
        assertEquals(7, SwiftLexis.INSTANCE.skipLiteral("#\"never", 0),
                "an unterminated raw string runs to the end of the file");

        assertEquals(-1, SwiftLexis.INSTANCE.skipLiteral("#if DEBUG", 0),
                "#if is a directive, not a raw string opener");
    }

    @Test
    @DisplayName("an apostrophe opens nothing, because Swift has no character literal")
    void apostrophesOpenNothing() {
        assertEquals(-1, SwiftLexis.INSTANCE.skipLiteral("'a'", 0));
        assertEquals(-1, SwiftLexis.INSTANCE.skipLiteral("it's a test", 2),
                "a scanner that treated ' as an opener would swallow the rest of the line");
        assertTrue(mutator.sites("let label = 'x'\nlet total = a - b\n")
                        .stream().map(TextMutator.Site::operator).toList().contains("-"),
                "the tokens after an apostrophe are still read as code");
    }

    @Test
    @DisplayName("=== and !== are single tokens, never == followed by =")
    void identityOperatorsAreSingleTokens() {
        String source = "let a = x === y\nlet b = x !== y\nlet c = x == y\nlet d = x != y\n";

        List<TextMutator.Site> sites = mutator.sites(source);

        assertEquals(List.of("===", "!==", "==", "!="),
                sites.stream().map(TextMutator.Site::operator).toList(), sites.toString());
        assertEquals(List.of("!==", "===", "!=", "=="),
                sites.stream().map(TextMutator.Site::replacement).toList(), sites.toString());
        assertEquals(List.of(1, 2, 3, 4), sites.stream().map(TextMutator.Site::line).toList());
    }

    @Test
    @DisplayName("a literal, in every form, is never a mutation site")
    void literalsAreNotSites() {
        String source = "let s = \"a + b\"\n"
                + "let m = \"\"\"\nc - d\n\"\"\"\n"
                + "let r = #\"e * f\"#\n"
                + "let total = g / h\n";

        List<TextMutator.Site> sites = mutator.sites(source);

        assertEquals(List.of("/"), sites.stream().map(TextMutator.Site::operator).toList(),
                "only the statement after the literals is code: " + sites);
        assertEquals(List.of(6), sites.stream().map(TextMutator.Site::line).toList(), sites.toString());

        assertEquals(List.of(), mutator.sites("// a + b\n/* c * d */\n").stream()
                .map(TextMutator.Site::operator).toList(),
                "a comment is not code either");
    }

    @Test
    @DisplayName("range, optional and compound tokens are one token, so nothing behind them becomes a site")
    void multiCharacterPunctuationDoesNotSplit() {
        assertEquals(List.of(), operators("for i in 0 ..< n {\n    let r = 0 ... 10\n}\n"));
        assertEquals(List.of(), operators("let fallback = a ?? b\n"),
                "?? is punctuation, not two question marks");
        assertEquals(List.of(), operators("let chained = a?.b\n"));
        assertEquals(List.of(), operators("let shifted = total <<= 1\n"),
                "<<= must not become << followed by =");
        assertEquals(List.of(), operators("let mapped = values.map { $0 -> Int in $0 }\n"),
                "a function arrow must not expose its - as an operator");
        assertEquals(List.of(), operators("let masked = a &+ b\n"),
                "the overflow operator is tokenized whole, and it is not in the mutation table");
    }

    @Test
    @DisplayName("unspaced, unary and boolean sites follow the shared mutator's rules")
    void spacedBinaryOperatorsAreTheSites() {
        assertEquals(List.of(), operators("total=a+b\n"), "an unspaced operator is not a binary site");
        assertEquals(List.of(), operators("let negative = -amount\n"), "a unary minus is not a site");
        assertEquals(List.of(), operators("let generic: Array<Int> = []\n"),
                "generic angle brackets must not be read as comparison");
        assertEquals(List.of("true", "false"), operators("let flag = true\nlet other = false\n"));
        assertEquals(List.of("+", "-"), operators("let total = a + b - c\n"));
    }

    @Test
    @DisplayName("a range operator is a site only when it is in the table")
    void aRangeOperatorIsNotInTheTable() {
        assertTrue(SwiftLexis.INSTANCE.mutations().get("..<") == null,
                "a range is punctuation, so flipping it would not describe behaviour a test can constrain");
        assertTrue(SwiftLexis.INSTANCE.mutations().get("...") == null);
    }

    private List<String> operators(String source) {
        return mutator.sites(source).stream().map(TextMutator.Site::operator).toList();
    }
}
