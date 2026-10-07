package dev.codewitness.cpp.mutation;

import dev.codewitness.cpp.CppSourceScan;
import dev.codewitness.support.mutation.LanguageLexis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The lexical shape of C and C++, as the shared source mutator reads it.
 *
 * <p>C++'s shape is the C family's with three things a text mutator has to know. A raw string
 * ({@code R"delim(...)delim"}) ends at a closing parenthesis followed by the delimiter that opened it, and
 * neither the delimiter nor the body is constrained the way an ordinary string is. A backslash continues a
 * quoted literal across a line break, so a string is not bounded by its line. And the operator table has
 * more punctuation than most: {@code ->*}, {@code <=>} and {@code ...} are single tokens, and the
 * compound assignments and the increment and decrement operators are there so that the {@code +} in
 * {@code +=} or {@code ++} is never read as a binary operator site.
 *
 * <p>Literal boundaries are not re-implemented here: they come from {@link CppSourceScan}, the same scanner
 * the heuristic analyzer reads the source with. Two copies of "where does this literal end" is exactly how
 * the earlier per-language mutators drifted apart on the edge cases above.
 *
 * <p>Block comments do not nest in C or C++, which is what this scanner assumes.
 */
public final class CppLexis implements LanguageLexis {

    /** The one instance: this shape carries no state. */
    public static final CppLexis INSTANCE = new CppLexis();

    /** Operator text to its replacement. Only these tokens are mutated. */
    private static final Map<String, String> MUTATIONS = mutationTable();

    /** Tokens that can end a value expression; an operator must follow one to be binary. */
    private static final Set<String> VALUE_END = Set.of(")", "]", "}");

    /** Tokens that can start a value expression; an operator must precede one to be binary. */
    private static final Set<String> VALUE_START = Set.of("(", "[", "{", "-", "!", "~", "&", "*");

    /** C++'s three-character punctuation, including the compounds that must not be split. */
    private static final String[] THREE = {"<<=", ">>=", "<=>", "->*", "..."};

    /** C++'s two-character punctuation. */
    private static final String[] TWO = {
        "==", "!=", "<=", ">=", "&&", "||", "+=", "-=", "*=", "/=", "%=",
        "<<", ">>", "&=", "|=", "^=", "->", "++", "--", "::", ".*", "##"
    };

    private CppLexis() {
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
        int end = CppSourceScan.literalEnd(source, start);
        return end == start ? -1 : end;
    }

    /**
     * Skips a C or C++ numeric literal.
     *
     * <p>The default would consume letters, which turns {@code 1e-3} into a run that exposes its {@code -}
     * as a binary operator site and makes {@code 0xFF} a single opaque token. Only the forms the language
     * actually defines are consumed here: a hexadecimal or binary prefix, digit separators, a fractional
     * part, an exponent, and the suffix letters.
     */
    @Override
    public int skipNumber(String source, int start) {
        int i = start;
        if (i + 1 < source.length() && source.charAt(i) == '0') {
            char prefix = source.charAt(i + 1);
            if (prefix == 'x' || prefix == 'X') {
                i += 2;
                while (i < source.length() && (Character.digit(source.charAt(i), 16) >= 0
                        || source.charAt(i) == '\'')) {
                    i++;
                }
                // A hexadecimal floating literal has a binary exponent, whose sign is part of the literal.
                if (i < source.length() && (source.charAt(i) == 'p' || source.charAt(i) == 'P')) {
                    i++;
                    if (i < source.length() && (source.charAt(i) == '+' || source.charAt(i) == '-')) {
                        i++;
                    }
                    while (i < source.length() && Character.isDigit(source.charAt(i))) {
                        i++;
                    }
                }
                return skipSuffix(source, i);
            }
            if (prefix == 'b' || prefix == 'B') {
                i += 2;
                while (i < source.length() && (source.charAt(i) == '0' || source.charAt(i) == '1'
                        || source.charAt(i) == '\'')) {
                    i++;
                }
                return skipSuffix(source, i);
            }
        }
        while (i < source.length() && (Character.isDigit(source.charAt(i)) || source.charAt(i) == '\'')) {
            i++;
        }
        if (i < source.length() && source.charAt(i) == '.') {
            i++;
            while (i < source.length() && (Character.isDigit(source.charAt(i)) || source.charAt(i) == '\'')) {
                i++;
            }
        }
        if (i < source.length() && (source.charAt(i) == 'e' || source.charAt(i) == 'E')) {
            i++;
            if (i < source.length() && (source.charAt(i) == '+' || source.charAt(i) == '-')) {
                i++;
            }
            while (i < source.length() && (Character.isDigit(source.charAt(i)) || source.charAt(i) == '\'')) {
                i++;
            }
        }
        return skipSuffix(source, i);
    }

    /** @return the offset past the integer and floating suffix letters the language defines. */
    private static int skipSuffix(String source, int start) {
        int i = start;
        while (i < source.length() && "fFlLuUzZ".indexOf(source.charAt(i)) >= 0) {
            i++;
        }
        return i;
    }
}
