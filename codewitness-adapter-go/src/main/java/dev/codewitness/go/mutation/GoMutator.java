package dev.codewitness.go.mutation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A small, bounded source mutator for Go.
 *
 * <p>A surviving mutant is the most direct evidence that a test suite does not constrain the
 * behaviour it appears to constrain, and it should not be unavailable just because no external Go
 * mutation tool is installed. This mutator therefore works on the source text itself: arithmetic
 * operators, comparison boundaries, boolean operators and the predeclared boolean constants are
 * flipped one at a time, and the project's own {@code go test} decides whether each mutant survives.
 *
 * <p>Three properties keep it honest and safe:
 *
 * <ul>
 *   <li>it never touches comments, interpreted strings, raw strings or rune literals, so it cannot
 *       corrupt a message, a format string or a credential while looking for a mutation site;</li>
 *   <li>it only mutates an operator that is surrounded by whitespace and by value expressions, which
 *       is how {@code gofmt} writes a binary operator. That deliberately skips unary minus, address-of
 *       {@code &x} and dereference {@code *p}, which would otherwise produce mutants that do not
 *       compile and tell us nothing;</li>
 *   <li>it recognises Go's own lexical shape — line and block comments, backtick raw strings that may
 *       span lines, rune literals and underscore-separated or hexadecimal numbers — so a {@code //}
 *       inside a string is not mistaken for a comment and an operator inside a raw string is not
 *       mistaken for code.</li>
 * </ul>
 *
 * <p>The operator set is deliberately small and is stated in every report. A mutant that does not
 * compile is non-viable and is excluded from the score, because compilation failure is a fact about
 * the mutation rather than evidence about the test suite.
 */
final class GoMutator {

    /** Operator text to its replacement. Only these tokens are mutated. */
    private static final Map<String, String> MUTATIONS = mutationTable();

    /** Tokens that can end a value expression; an operator must follow one to be binary. */
    private static final Set<String> VALUE_END = Set.of(")", "]", "}");

    /** Tokens that can start a value expression; an operator must precede one to be binary. */
    private static final Set<String> VALUE_START = Set.of("(", "[", "{", "-", "!", "&", "*");

    private GoMutator() {
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

    /** A single replaceable location: the token to change and where it is. */
    record Site(String operator, String replacement, int offset, int line) {

        String describe() {
            return operator + " -> " + replacement;
        }
    }

    private record Token(String text, Kind kind, int offset, int line) {
        enum Kind { IDENT, NUMBER, LITERAL, OPERATOR, PUNCT }
    }

    /** @return every mutatable site in the source, in source order. */
    static List<Site> sites(String source) {
        List<Token> tokens = tokenize(source);
        List<Site> sites = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            String replacement = MUTATIONS.get(token.text());
            if (replacement == null) {
                continue;
            }
            if (token.kind() == Token.Kind.IDENT) {
                // true / false are predeclared identifiers, so a whole-identifier match is always a
                // boolean constant and always safe to flip.
                sites.add(new Site(token.text(), replacement, token.offset(), token.line()));
                continue;
            }
            if (token.kind() != Token.Kind.OPERATOR) {
                continue;
            }
            if (!isSpaced(source, token)) {
                continue;
            }
            Token previous = i > 0 ? tokens.get(i - 1) : null;
            Token next = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
            if (endsValue(previous) && startsValue(next)) {
                sites.add(new Site(token.text(), replacement, token.offset(), token.line()));
            }
        }
        return sites;
    }

    /** @return the source with one site's operator replaced. */
    static String apply(String source, Site site) {
        return source.substring(0, site.offset()) + site.replacement()
                + source.substring(site.offset() + site.operator().length());
    }

    private static boolean isSpaced(String source, Token token) {
        int before = token.offset() - 1;
        int after = token.offset() + token.text().length();
        return before >= 0 && after < source.length()
                && Character.isWhitespace(source.charAt(before))
                && Character.isWhitespace(source.charAt(after));
    }

    private static boolean endsValue(Token token) {
        if (token == null) {
            return false;
        }
        return token.kind() == Token.Kind.IDENT || token.kind() == Token.Kind.NUMBER
                || token.kind() == Token.Kind.LITERAL || VALUE_END.contains(token.text());
    }

    private static boolean startsValue(Token token) {
        if (token == null) {
            return false;
        }
        return token.kind() == Token.Kind.IDENT || token.kind() == Token.Kind.NUMBER
                || token.kind() == Token.Kind.LITERAL || VALUE_START.contains(token.text());
    }

    private static List<Token> tokenize(String source) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int line = 1;
        boolean inBlockComment = false;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\n') {
                line++;
                i++;
                continue;
            }
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (inBlockComment) {
                // Go block comments do not nest, so the first */ closes the comment.
                if (c == '*' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                    inBlockComment = false;
                    i += 2;
                } else {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                inBlockComment = true;
                i += 2;
                continue;
            }
            if (c == '"') {
                int start = i;
                i = skipInterpretedString(source, i);
                tokens.add(new Token(source.substring(start, i), Token.Kind.LITERAL, start, line));
                continue;
            }
            if (c == '`') {
                int start = i;
                i = skipRawString(source, i);
                tokens.add(new Token(source.substring(start, i), Token.Kind.LITERAL, start, line));
                // A raw string is the one literal that may span lines, and it is consumed outside the
                // main loop, so its newlines must be counted here or every later site would report the
                // wrong line.
                line += newlinesBetween(source, start, i);
                continue;
            }
            if (c == '\'') {
                int start = i;
                i = skipRune(source, i);
                tokens.add(new Token(source.substring(start, i), Token.Kind.LITERAL, start, line));
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                while (i < source.length() && Character.isJavaIdentifierPart(source.charAt(i))) {
                    i++;
                }
                tokens.add(new Token(source.substring(start, i), Token.Kind.IDENT, start, line));
                continue;
            }
            if (Character.isDigit(c)) {
                int start = i;
                i = skipNumber(source, i);
                tokens.add(new Token(source.substring(start, i), Token.Kind.NUMBER, start, line));
                continue;
            }
            String operator = longestOperator(source, i);
            if (operator != null) {
                tokens.add(new Token(operator, Token.Kind.OPERATOR, i, line));
                i += operator.length();
                continue;
            }
            tokens.add(new Token(String.valueOf(c), Token.Kind.PUNCT, i, line));
            i++;
        }
        return tokens;
    }

    /** Go's three-character punctuation. */
    private static final String[] THREE = {"<<=", ">>=", "&^="};

    /** Go's two-character punctuation, including the assignment compounds that must not split. */
    private static final String[] TWO = {
        "==", "!=", "<=", ">=", "&&", "||", "+=", "-=", "*=", "/=", "%=",
        "<<", ">>", "<-", "&^", ":=", "++", "--"
    };

    private static String longestOperator(String source, int offset) {
        if (offset + 3 <= source.length()) {
            String candidate = source.substring(offset, offset + 3);
            for (String operator : THREE) {
                if (operator.equals(candidate)) {
                    return operator;
                }
            }
        }
        if (offset + 2 <= source.length()) {
            String candidate = source.substring(offset, offset + 2);
            for (String operator : TWO) {
                if (operator.equals(candidate)) {
                    return operator;
                }
            }
        }
        char c = source.charAt(offset);
        if ("+-*/%<>=&|!^~".indexOf(c) >= 0) {
            return String.valueOf(c);
        }
        return null;
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

    /**
     * @return the offset just past a Go number literal, including {@code 0x}, {@code 0b} and
     *     {@code 0o} prefixes, underscore separators, fractional and exponent parts. Consuming the
     *     whole literal matters because a signed exponent such as {@code 1e-3} would otherwise expose
     *     the {@code -} as a bogus binary operator site.
     */
    private static int skipNumber(String source, int start) {
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

    private static int newlinesBetween(String source, int start, int end) {
        int count = 0;
        for (int i = start; i < end && i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }
}
