package dev.codewitness.vhdl.mutation;

import dev.codewitness.support.mutation.LanguageLexis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The lexical shape of VHDL, as the shared source mutator reads it.
 *
 * <p>VHDL's shape differs from the software languages in ways that matter to a text mutator. A line
 * comment opens with two hyphens rather than two slashes, so the mutator has to consume it or an
 * operator inside prose would be mutated. {@code <=} is the signal assignment and not a comparison, so
 * it is consumed whole and deliberately left unmutated - flipping it to {@code <} would turn an
 * assignment into a dangling comparison - while {@code :=} (variable assignment) and {@code =>}
 * (association) are likewise kept whole so their {@code =} never looks like an equality site. The
 * apostrophe opens a character literal ({@code '1'}) but is also the attribute marker
 * ({@code clk'event}), so only the three-character {@code 'x'} form is treated as a literal.
 *
 * <p>The mutation table keeps to operators whose flip is a plausible logic error and whose mutant still
 * parses: arithmetic, comparison, equality and the greater/less boundaries. The word operators
 * ({@code and}, {@code or}, {@code mod}) are left alone because the mutator works on punctuation, and
 * mutating them would need a tokenizer that understands VHDL identifiers as operators.
 */
final class VhdlLexis implements LanguageLexis {

    /** The one instance: this shape carries no state. */
    static final VhdlLexis INSTANCE = new VhdlLexis();

    /** Operator text to its replacement. Only these tokens are mutated. */
    private static final Map<String, String> MUTATIONS = mutationTable();

    /** Tokens that can end a value expression; an operator must follow one to be binary. */
    private static final Set<String> VALUE_END = Set.of(")", "]");

    /** Tokens that can start a value expression; an operator must precede one to be binary. */
    private static final Set<String> VALUE_START = Set.of("(", "[", "-", "+");

    /** VHDL has no three-character operator the mutator flips; the array is empty on purpose. */
    private static final String[] THREE = {};

    /**
     * VHDL's two-character operators. {@code <=}, {@code :=} and {@code =>} are listed so the tokenizer
     * keeps them whole; only the comparison and equality operators among them are mutated.
     */
    private static final String[] TWO = {"<=", ">=", "/=", ":=", "=>", "**"};

    private VhdlLexis() {
    }

    private static Map<String, String> mutationTable() {
        Map<String, String> mutations = new LinkedHashMap<>();
        mutations.put("+", "-");
        mutations.put("-", "+");
        mutations.put("*", "/");
        mutations.put("/", "*");
        mutations.put("<", ">");
        mutations.put(">", ">=");
        mutations.put(">=", ">");
        mutations.put("=", "/=");
        mutations.put("/=", "=");
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
        return "+-*/&<>=";
    }

    @Override
    public int skipLiteral(String source, int start) {
        char c = source.charAt(start);
        if (c == '"') {
            int i = start + 1;
            while (i < source.length()) {
                char d = source.charAt(i);
                if (d == '"' && i + 1 < source.length() && source.charAt(i + 1) == '"') {
                    // A doubled quote is an escaped quote inside a VHDL string literal.
                    i += 2;
                    continue;
                }
                if (d == '"') {
                    return i + 1;
                }
                if (d == '\n') {
                    // Unterminated: stop at the newline rather than swallowing the rest of the file.
                    return i;
                }
                i++;
            }
            return source.length();
        }
        if (c == '\'' && start + 2 < source.length() && source.charAt(start + 2) == '\'') {
            return start + 3;
        }
        if (c == '-' && start + 1 < source.length() && source.charAt(start + 1) == '-') {
            int i = start;
            while (i < source.length() && source.charAt(i) != '\n') {
                i++;
            }
            return i;
        }
        return -1;
    }

    @Override
    public int skipNumber(String source, int start) {
        int i = start;
        // A decimal or based literal: 1_000, 2.5, 1.0e-3, 16#FF#, 8#17#. The mutator must consume the
        // whole token so its digits and separators never expose an operator.
        while (i < source.length()) {
            char c = source.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '#' || c == '.') {
                i++;
                continue;
            }
            if ((c == '+' || c == '-') && i > start
                    && (source.charAt(i - 1) == 'e' || source.charAt(i - 1) == 'E')) {
                i++;
                continue;
            }
            break;
        }
        return i;
    }
}
