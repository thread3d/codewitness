package dev.codewitness.typescript.mutation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bounded source mutator: what it changes and, just as importantly, what it refuses to. */
class TypeScriptMutatorTest {

    @Test
    @DisplayName("spaced binary operators and boolean literals are mutatable")
    void findsBinaryOperators() {
        String source = """
                const sum = total + fee;
                const small = count < limit;
                const both = left && right;
                const flag = true;
                """;

        List<TypeScriptMutator.Site> sites = TypeScriptMutator.sites(source);

        assertEquals(4, sites.size(), sites.toString());
        assertEquals(List.of("+", "<", "&&", "true"), sites.stream().map(TypeScriptMutator.Site::operator).toList());
        assertEquals(List.of("-", "<=", "||", "false"),
                sites.stream().map(TypeScriptMutator.Site::replacement).toList());
        assertEquals(1, sites.getFirst().line());
        assertEquals(4, sites.getLast().line());
    }

    @Test
    @DisplayName("equality is mutated to its negation and back")
    void mutatesEquality() {
        String source = "const same = a === b;\nconst other = c !== d;\n";

        List<TypeScriptMutator.Site> sites = TypeScriptMutator.sites(source);

        assertEquals(List.of("===", "!=="), sites.stream().map(TypeScriptMutator.Site::operator).toList());
        assertEquals(List.of("!==", "==="), sites.stream().map(TypeScriptMutator.Site::replacement).toList());
    }

    @Test
    @DisplayName("comments, strings, unspaced operators and generic arguments are never mutated")
    void refusesToCorruptTheSource() {
        String source = """
                // const ignored = a + b;
                /* const alsoIgnored = c < d; */
                const message = "a + b and c < d";
                const tight = a+b;
                const generic = new Array<string>();
                """;

        assertTrue(TypeScriptMutator.sites(source).isEmpty(), TypeScriptMutator.sites(source).toString());
    }

    @Test
    @DisplayName("applying a site rewrites exactly the operator")
    void appliesASite() {
        String source = "const small = count <= limit;";
        TypeScriptMutator.Site site = TypeScriptMutator.sites(source).getFirst();

        assertEquals("<= -> <", site.describe());
        assertEquals("const small = count < limit;", TypeScriptMutator.apply(source, site));
    }
}
