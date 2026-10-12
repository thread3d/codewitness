package dev.codewitness.verilog.mutation;

import dev.codewitness.support.mutation.LanguageLexis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The lexical shape of Verilog and SystemVerilog, as the shared source mutator reads it.
 *
 * <p>Verilog's shape differs from the software languages in ways that matter to a text mutator. A
 * based literal opens with an apostrophe ({@code 4'b1010}, {@code 8'hFF}, {@code 1'bx}) rather than a
 * quote, so the apostrophe must not be treated as a literal delimiter and the literal's digits must be
 * consumed whole; {@code <=} is both a comparison and the nonblocking assignment operator, so it is a
 * legitimate site rather than one to exclude; and {@code ===}/{@code !==} are the four-state
 * case-equality operators, which are one token each, not {@code ==} followed by {@code =}.
 *
 * <p>The mutation table keeps to operators whose flip is a plausible logic error and whose mutant
 * still parses: arithmetic, comparison boundaries, equality, the boolean operators, the bitwise
 * operators and the shifts. It deliberately leaves the concatenation and replication braces, the
 * bit-select brackets and the ternary operator alone, because mutating those produces mutants that
 * do not parse and therefore tell us nothing about the tests.
 */
final class VerilogLexis implements LanguageLexis {

    /** The one instance: this shape carries no state. */
    static final VerilogLexis INSTANCE = new VerilogLexis();

    /** Operator text to its replacement. Only these tokens are mutated. */
    private static final Map<String, String> MUTATIONS = mutationTable();

    /** Tokens that can end a value expression; an operator must follow one to be binary. */
    private static final Set<String> VALUE_END = Set.of(")", "]", "}");

    /** Tokens that can start a value expression; an operator must precede one to be binary. */
    private static final Set<String> VALUE_START = Set.of("(", "[", "{", "-", "+", "!", "~", "&", "|", "^");

    /** Verilog's three-character operators. */
    private static final String[] THREE = {"<<<", ">>>", "===", "!=="};

    /**
     * Verilog's two-character operators. The ones that are also two single-character tokens
     * ({@code +:}, {@code -:}) are listed so the tokenizer keeps them whole and never exposes the
     * colon as punctuation.
     */
    private static final String[] TWO = {
        "==", "!=", "<=", ">=", "&&", "||", "<<", ">>", "~^", "^~", "**", "+:", "-:", "->", "=>"
    };

    private VerilogLexis() {
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
        mutations.put("&", "|");
        mutations.put("|", "&");
        mutations.put("^", "~^");
        mutations.put("~^", "^");
        mutations.put("<<", ">>");
        mutations.put(">>", "<<");
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
        return "+-*/%<>=&|^~!";
    }

    @Override
    public int skipLiteral(String source, int start) {
        if (source.charAt(start) != '"') {
            // The apostrophe is a based-literal delimiter, not a string opener; a Verilog source has
            // no other literal that needs its own escape handling.
            return -1;
        }
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
            if (c == '\n') {
                // Unterminated: stop at the newline rather than swallowing the rest of the file.
                return i;
            }
            i++;
        }
        return source.length();
    }

    @Override
    public int skipNumber(String source, int start) {
        int i = start;
        // A sized or unsized based literal: 4'b1010, 8'hFF, 1'bx, 16'sd42. The apostrophe and the
        // base character are consumed with their digits so the tokenizer never splits the literal into
        // an identifier that looks like a value of its own.
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
            int exponent = i + 1;
            if (exponent < source.length() && (source.charAt(exponent) == '+' || source.charAt(exponent) == '-')) {
                exponent++;
            }
            if (exponent < source.length() && Character.isDigit(source.charAt(exponent))) {
                i = exponent;
                while (i < source.length() && Character.isDigit(source.charAt(i))) {
                    i++;
                }
            }
        }
        if (i < source.length() && source.charAt(i) == '\'') {
            int base = i + 1;
            if (base < source.length() && (source.charAt(base) == 's' || source.charAt(base) == 'S')) {
                base++;
            }
            if (base < source.length() && "bodhBODH".indexOf(source.charAt(base)) >= 0) {
                i = base + 1;
                while (i < source.length() && (isBasedDigit(source.charAt(i)) || source.charAt(i) == '_')) {
                    i++;
                }
            }
        }
        return i;
    }

    private static boolean isBasedDigit(char c) {
        return Character.isLetterOrDigit(c) || c == '?' || c == 'x' || c == 'X' || c == 'z' || c == 'Z';
    }
}
