package dev.codewitness.pascal.mutation;

import dev.codewitness.support.mutation.LanguageLexis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The lexical shape of Pascal, as the shared text mutator reads it.
 *
 * <p>Two things are Pascal's own. Equality is {@code =} and inequality is {@code <>}, so those are what
 * a mutation flips - not {@code ==} and {@code !=}, which the language does not have. And comments open
 * with {@code {} or {@code (*}, which the shared mutator's own comment handling does not know, so
 * {@link #skipLiteral} claims them: an operator inside a comment is then part of a literal token and is
 * never mutated.
 *
 * <p>Word operators ({@code and}, {@code or}, {@code div}, {@code mod}, {@code shl}, {@code shr}) are
 * identifiers to the tokenizer and are listed in the mutation table, which is exactly right for them.
 * Assignment ({@code :=}) and the range operator ({@code ..}) are listed as two-character operators but
 * are not mutated, so neither is mistaken for a bare {@code =} or {@code .}.
 */
final class PascalLexis implements LanguageLexis {

    static final PascalLexis INSTANCE = new PascalLexis();

    private static final Map<String, String> MUTATIONS;

    static {
        Map<String, String> mutations = new LinkedHashMap<>();
        mutations.put("+", "-");
        mutations.put("-", "+");
        mutations.put("*", "/");
        mutations.put("/", "*");
        mutations.put("<", "<=");
        mutations.put("<=", "<");
        mutations.put(">", ">=");
        mutations.put(">=", ">");
        mutations.put("=", "<>");
        mutations.put("<>", "=");
        mutations.put("and", "or");
        mutations.put("or", "and");
        mutations.put("div", "mod");
        mutations.put("mod", "div");
        mutations.put("shl", "shr");
        mutations.put("shr", "shl");
        mutations.put("True", "False");
        mutations.put("False", "True");
        mutations.put("true", "false");
        mutations.put("false", "true");
        MUTATIONS = Map.copyOf(mutations);
    }

    private static final Set<String> VALUE_END = Set.of(")", "]");
    private static final Set<String> VALUE_START = Set.of("(", "[", "-", "+", "@", "^");
    private static final String[] NO_THREE_CHAR_OPERATORS = new String[0];
    private static final String[] TWO_CHAR_OPERATORS = {":=", "<>", "<=", ">=", "..", "**"};

    private PascalLexis() {
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
        return NO_THREE_CHAR_OPERATORS.clone();
    }

    @Override
    public String[] twoCharOperators() {
        return TWO_CHAR_OPERATORS.clone();
    }

    @Override
    public String singleCharOperators() {
        return "+-*/<>=^";
    }

    @Override
    public int skipLiteral(String source, int start) {
        char c = source.charAt(start);
        if (c == '\'') {
            int i = start + 1;
            while (i < source.length()) {
                if (source.charAt(i) == '\'') {
                    if (i + 1 < source.length() && source.charAt(i + 1) == '\'') {
                        i += 2;
                        continue;
                    }
                    return i + 1;
                }
                if (source.charAt(i) == '\n') {
                    return i;
                }
                i++;
            }
            return source.length();
        }
        if (c == '{') {
            return until(source, start + 1, '}');
        }
        if (c == '(' && start + 1 < source.length() && source.charAt(start + 1) == '*') {
            return untilParenComment(source, start + 2);
        }
        return -1;
    }

    @Override
    public int skipNumber(String source, int start) {
        int i = start;
        while (i < source.length() && (Character.isDigit(source.charAt(i)) || source.charAt(i) == '_')) {
            i++;
        }
        if (i < source.length() && source.charAt(i) == '.'
                && i + 1 < source.length() && Character.isDigit(source.charAt(i + 1))) {
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
                while (i < source.length() && (Character.isDigit(source.charAt(i)) || source.charAt(i) == '_')) {
                    i++;
                }
            }
        }
        return i;
    }

    private static int until(String source, int from, char closing) {
        for (int i = from; i < source.length(); i++) {
            if (source.charAt(i) == closing) {
                return i + 1;
            }
        }
        return source.length();
    }

    private static int untilParenComment(String source, int from) {
        for (int i = from; i + 1 < source.length(); i++) {
            if (source.charAt(i) == '*' && source.charAt(i + 1) == ')') {
                return i + 2;
            }
        }
        return source.length();
    }
}
