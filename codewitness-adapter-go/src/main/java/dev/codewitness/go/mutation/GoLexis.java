package dev.codewitness.go.mutation;

import dev.codewitness.support.mutation.LanguageLexis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The lexical shape of Go, as the shared source mutator reads it.
 *
 * <p>Go's shape differs from the script languages in ways that matter to a text mutator: a backtick
 * raw string has no escapes and may span any number of lines, so a {@code //} or an operator inside
 * one is data rather than code; a rune literal opens with the same character as a character literal in
 * the C-like languages; and a numeric literal may carry a base prefix, underscores and a signed
 * exponent, which would otherwise expose its {@code -} as a bogus binary operator site.
 *
 * <p>Block comments are treated as non-nesting, which is what Go's own specification says they are.
 */
final class GoLexis implements LanguageLexis {

    /** The one instance: this shape carries no state. */
    static final GoLexis INSTANCE = new GoLexis();

    /** Operator text to its replacement. Only these tokens are mutated. */
    private static final Map<String, String> MUTATIONS = mutationTable();

    /** Tokens that can end a value expression; an operator must follow one to be binary. */
    private static final Set<String> VALUE_END = Set.of(")", "]", "}");

    /** Tokens that can start a value expression; an operator must precede one to be binary. */
    private static final Set<String> VALUE_START = Set.of("(", "[", "{", "-", "!", "&", "*");

    /** Go's three-character punctuation. */
    private static final String[] THREE = {"<<=", ">>=", "&^="};

    /** Go's two-character punctuation, including the assignment compounds that must not split. */
    private static final String[] TWO = {
        "==", "!=", "<=", ">=", "&&", "||", "+=", "-=", "*=", "/=", "%=",
        "<<", ">>", "<-", "&^", ":=", "++", "--"
    };

    private GoLexis() {
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
        char c = source.charAt(start);
        if (c == '"') {
            return skipInterpretedString(source, start);
        }
        if (c == '`') {
            return skipRawString(source, start);
        }
        if (c == '\'') {
            return skipRune(source, start);
        }
        return -1;
    }

    @Override
    public int skipNumber(String source, int start) {
        int i = start;
        if (i + 1 < source.length() && source.charAt(i) == '0') {
            char prefix = source.charAt(i + 1);
            if (prefix == 'x' || prefix == 'X' || prefix == 'b' || prefix == 'B' || prefix == 'o' || prefix == 'O') {
                i += 2;
                while (i < source.length() && (isBaseDigit(source.charAt(i)) || source.charAt(i) == '_')) {
                    i++;
                }
                if (i < source.length() && (source.charAt(i) == 'p' || source.charAt(i) == 'P')) {
                    i = skipExponent(source, i);
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
            i = skipExponent(source, i);
        }
        if (i < source.length() && source.charAt(i) == 'i') {
            // The imaginary suffix is part of the literal.
            i++;
        }
        return i;
    }

    /** @return the offset just past an interpreted string, whose escapes may hide a closing quote. */
    private static int skipInterpretedString(String source, int start) {
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
     * @return the offset just past a backtick raw string, which ends at the next backtick and may span
     *     any number of lines. A raw string has no escape sequences, so the next backtick always ends
     *     it — that is the whole reason this scanner is separate from the interpreted-string one.
     */
    private static int skipRawString(String source, int start) {
        int i = start + 1;
        while (i < source.length()) {
            if (source.charAt(i) == '`') {
                return i + 1;
            }
            i++;
        }
        return source.length();
    }

    /** @return the offset just past a rune literal ({@code 'x'} or {@code '\n'}). */
    private static int skipRune(String source, int start) {
        int i = start + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '\'') {
                return i + 1;
            }
            if (c == '\n') {
                // Unterminated: stop before the newline rather than swallowing the rest of the file.
                return i;
            }
            i++;
        }
        return source.length();
    }

    private static int skipExponent(String source, int at) {
        int i = at + 1;
        if (i < source.length() && (source.charAt(i) == '+' || source.charAt(i) == '-')) {
            i++;
        }
        while (i < source.length() && (Character.isDigit(source.charAt(i)) || source.charAt(i) == '_')) {
            i++;
        }
        return i;
    }

    private static boolean isBaseDigit(char c) {
        return Character.isLetterOrDigit(c);
    }
}
