package dev.codewitness.rust.mutation;

import dev.codewitness.support.mutation.LanguageLexis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The lexical shape of Rust, as the shared source mutator reads it.
 *
 * <p>Rust's shape differs from the script languages in ways that matter to a text mutator: a lifetime
 * ({@code 'a}) and a character literal ({@code 'x'}) open with the same character and must not be
 * confused, a question mark can close a value expression ({@code result?}), and the language has
 * neither strict equality nor template literals. The operator tables below are what the mutator may
 * flip; everything else it leaves alone.
 *
 * <p>Block comments are treated as non-nesting. Rust actually nests them, so a nested comment could in
 * principle expose code the mutator should have skipped; the mutator's rule has always been the
 * conservative one of never mutating inside a comment it can see, and this keeps that rule unchanged.
 */
final class RustLexis implements LanguageLexis {

    /** The one instance: this shape carries no state. */
    static final RustLexis INSTANCE = new RustLexis();

    /** Operator text to its replacement. Only these tokens are mutated. */
    private static final Map<String, String> MUTATIONS = mutationTable();

    /** Tokens that can end a value expression; an operator must follow one to be binary. */
    private static final Set<String> VALUE_END = Set.of(")", "]", "}", "?");

    /** Tokens that can start a value expression; an operator must precede one to be binary. */
    private static final Set<String> VALUE_START = Set.of("(", "[", "{", "-", "!", "&", "*");

    /** Rust's three-character punctuation. */
    private static final String[] THREE = {"..=", "<<=", ">>="};

    /** Rust's two-character punctuation. */
    private static final String[] TWO = {
        "->", "=>", "::", "==", "!=", "<=", ">=", "&&", "||", "+=", "-=", "*=", "/=", "%=",
        "<<", ">>", ".."
    };

    private RustLexis() {
    }

    private static Map<String, String> mutationTable() {
        Map<String, String> mutations = new LinkedHashMap<>();
        mutations.put("+", "-");
        mutations.put("-", "+");
        mutations.put("*", "/");
        mutations.put("/", "*");
        mutations.put("%", "*");
        mutations.put("<", "<=");
        mutations.put("<=", "<");
        mutations.put(">", ">=");
        mutations.put(">=", ">");
        mutations.put("==", "!=");
        mutations.put("!=", "==");
        mutations.put("&&", "||");
        mutations.put("||", "&&");
        mutations.put("true", "false");
        mutations.put("false", "true");
        return Map.copyOf(mutations);
    }

    @Override
    public Map<String, String> mutations() {
        return MUTATIONS;
    }

    @Override
    public Set<String> valueEnd() {
        return VALUE_END;
    }

    @Override
    public Set<String> valueStart() {
        return VALUE_START;
    }

    @Override
    public String[] threeCharOperators() {
        return THREE.clone();
    }

    @Override
    public String[] twoCharOperators() {
        return TWO.clone();
    }

    @Override
    public String singleCharOperators() {
        return "+-*/%<>=&|!^?~";
    }

    @Override
    public int skipLiteral(String source, int start) {
        char c = source.charAt(start);
        if (c == '"') {
            return skipString(source, start);
        }
        if (c == '\'') {
            return skipCharOrLifetime(source, start);
        }
        return -1;
    }

    /** @return the offset just past a string literal, whose escapes may hide a closing quote. */
    private static int skipString(String source, int start) {
        int i = start + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '"') {
                return i + 1;
            }
            i++;
        }
        return source.length();
    }

    /**
     * Skips a character literal ({@code 'x'}) or a lifetime ({@code 'a}), which look alike.
     *
     * @return the offset just past the literal or lifetime
     */
    private static int skipCharOrLifetime(String source, int start) {
        int i = start + 1;
        if (i < source.length() && source.charAt(i) == '\\') {
            i += 2;
            while (i < source.length() && source.charAt(i) != '\'') {
                i++;
            }
            return Math.min(source.length(), i + 1);
        }
        if (i + 1 < source.length() && source.charAt(i + 1) == '\'') {
            return i + 2;
        }
        // A lifetime: consume the identifier so it is not mistaken for a value.
        while (i < source.length() && Character.isJavaIdentifierPart(source.charAt(i))) {
            i++;
        }
        return i;
    }
}
