package dev.codewitness.support.mutation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bounded source mutator: what it changes and, just as importantly, what it refuses to. */
class TextMutatorTest {

    /** The mutator over the shape JavaScript and TypeScript share. */
    private final TextMutator mutator = new TextMutator(ScriptLexis.INSTANCE);

    @Test
    @DisplayName("spaced binary operators and boolean literals are mutatable")
    void findsBinaryOperators() {
        String source = """
                const sum = total + fee;
                const small = count < limit;
                const both = left && right;
                const flag = true;
                """;

        List<TextMutator.Site> sites = mutator.sites(source);

        assertEquals(4, sites.size(), sites.toString());
        assertEquals(List.of("+", "<", "&&", "true"), sites.stream().map(TextMutator.Site::operator).toList());
        assertEquals(List.of("-", "<=", "||", "false"),
                sites.stream().map(TextMutator.Site::replacement).toList());
        assertEquals(1, sites.getFirst().line());
        assertEquals(4, sites.getLast().line());
    }

    @Test
    @DisplayName("equality is mutated to its negation and back")
    void mutatesEquality() {
        String source = "const same = a === b;\nconst other = c !== d;\n";

        List<TextMutator.Site> sites = mutator.sites(source);

        assertEquals(List.of("===", "!=="), sites.stream().map(TextMutator.Site::operator).toList());
        assertEquals(List.of("!==", "==="), sites.stream().map(TextMutator.Site::replacement).toList());
    }

    @Test
    @DisplayName("comments, strings, template literals, generic arguments and unspaced operators are never mutated")
    void refusesToCorruptTheSource() {
        String source = """
                // const ignored = a + b;
                /* const alsoIgnored = c < d; */
                const message = "a + b and c < d";
                const tight = a+b;
                const tightComparison = a<b;
                const template = `sum is ${a + b}`;
                const generic = new Array<string>();
                """;

        assertTrue(mutator.sites(source).isEmpty(), mutator.sites(source).toString());
    }

    @Test
    @DisplayName("applying a site rewrites exactly the operator")
    void appliesASite() {
        String source = "const small = count <= limit;";
        TextMutator.Site site = mutator.sites(source).getFirst();

        assertEquals("<= -> <", site.describe());
        assertEquals("const small = count < limit;", TextMutator.apply(source, site));
    }

    @Test
    @DisplayName("a literal that spans lines does not shift the line numbers reported after it")
    void countsLinesInsideMultilineLiterals() {
        String source = """
                const banner = `first
                second
                third`;
                const small = count < limit;
                """;

        List<TextMutator.Site> sites = mutator.sites(source);

        assertEquals(1, sites.size(), sites.toString());
        assertEquals(4, sites.getFirst().line(), sites.toString());
    }
}
