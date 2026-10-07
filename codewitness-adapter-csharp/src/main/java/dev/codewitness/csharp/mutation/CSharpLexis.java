package dev.codewitness.csharp.mutation;

import dev.codewitness.csharp.CSharpSourceScan;
import dev.codewitness.support.mutation.LanguageLexis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The lexical shape of C#, as the shared source mutator reads it.
 *
 * <p>C#'s shape is the C family's with three additions that matter to a text mutator. A verbatim string
 * ({@code @"..."}) ends at the next undoubled quote, not the next quote, so a doubled quote inside one
 * would otherwise be read as its end. A raw string ({@code """..."""}) ends at a run of at least as many
 * quotes as opened it, and may span any number of lines. And a character literal opens with the same
 * character that a Go rune does, while an interpolated string's {@code $} and a verbatim string's
 * {@code @} are prefixes rather than operators.
 *
 * <p>Literal boundaries are not re-implemented here: they come from {@link CSharpSourceScan}, the same
 * scanner the heuristic analyzer reads the source with. Two copies of "where does this literal end"
 * is exactly how the earlier per-language mutators drifted apart on the edge cases above.
 *
 * <p>An interpolated string is consumed whole, holes included, so an operator inside a hole is not
 * mutated. That is conservative by design: missing a mutation site costs one mutant, while reporting a
 * site inside what the scanner believes is string data would produce a mutant that changes text.
 *
 * <p>Block comments do not nest in C#, which is what this scanner assumes.
 */
public final class CSharpLexis implements LanguageLexis {

    /** The one instance: this shape carries no state. */
    public static final CSharpLexis INSTANCE = new CSharpLexis();

    /** Operator text to its replacement. Only these tokens are mutated. */
    private static final Map<String, String> MUTATIONS = mutationTable();

    /** Tokens that can end a value expression; an operator must follow one to be binary. */
    private static final Set<String> VALUE_END = Set.of(")", "]", "}");

    /** Tokens that can start a value expression; an operator must precede one to be binary. */
    private static final Set<String> VALUE_START = Set.of("(", "[", "{", "-", "!", "~", "&", "*");

    /** C#'s three-character punctuation, including the compounds that must not be split. */
    private static final String[] THREE = {"<<=", ">>=", "??=", ">>>"};

    /**
     * C#'s two-character punctuation.
     *
     * <p>The assignment compounds and the increment and decrement operators are here so that the
     * {@code +} in {@code +=} or {@code ++} is never read as a binary operator site. The unsigned shift
     * {@code >>>} is in the three-character table so that {@code >>>=} is not read as {@code >>} followed
     * by the comparison {@code >=}, which <em>is</em> in the mutation table.
     */
    private static final String[] TWO = {
        "==", "!=", "<=", ">=", "&&", "||", "+=", "-=", "*=", "/=", "%=",
        "<<", ">>", "&=", "|=", "^=", "??", "?.", "->", "=>", "++", "--", "::", ".."
    };

    private CSharpLexis() {
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
        return "+-*/%<>=&|!^~";
    }

    @Override
    public int skipLiteral(String source, int start) {
        int end = CSharpSourceScan.literalEnd(source, start);
        return end == start ? -1 : end;
    }

    /**
     * Skips a C# numeric literal.
     *
     * <p>The default would consume letters, which turns {@code 1.ToString()} into a single number token
     * and hides the call; it would also expose the {@code -} of an exponent such as {@code 1e-3} as a
     * binary operator site. Only the suffixes C# actually defines are consumed here, so {@code 1m} is one
     * literal and {@code 1.ToString} is a number followed by an identifier.
     */
    @Override
    public int skipNumber(String source, int start) {
        int i = start;
        if (i + 1 < source.length() && source.charAt(i) == '0') {
            char prefix = source.charAt(i + 1);
            if (prefix == 'x' || prefix == 'X' || prefix == 'b' || prefix == 'B') {
                i += 2;
                while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i))
                        || source.charAt(i) == '_')) {
                    i++;
                }
                return i;
            }
        }
        while (i < source.length() && (Character.isDigit(source.charAt(i)) || source.charAt(i) == '_')) {
            i++;
        }
        if (i < source.length() && source.charAt(i) == '.') {
            i++;
            while (i < source.length() && (Character.isDigit(source.charAt(i)) || source.charAt(i) == '_')) {
                i++;
            }
        }
        if (i < source.length() && (source.charAt(i) == 'e' || source.charAt(i) == 'E')) {
            i++;
            if (i < source.length() && (source.charAt(i) == '+' || source.charAt(i) == '-')) {
                i++;
            }
            while (i < source.length() && (Character.isDigit(source.charAt(i)) || source.charAt(i) == '_')) {
                i++;
            }
        }
        while (i < source.length() && "fFdDmMuUlL".indexOf(source.charAt(i)) >= 0) {
            i++;
        }
        return i;
    }
}
