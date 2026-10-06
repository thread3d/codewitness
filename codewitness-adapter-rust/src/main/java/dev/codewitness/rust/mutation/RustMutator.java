package dev.codewitness.rust.mutation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A small, bounded source mutator for Rust.
 *
 * <p>A surviving mutant is the most direct evidence that a test suite does not constrain the
 * behaviour it appears to constrain, and it should not be unavailable just because
 * {@code cargo-mutants} is not installed. This mutator therefore works on the source text itself:
 * arithmetic operators, comparison boundaries, boolean operators and boolean literals are flipped
 * one at a time, and the project's own test suite decides whether each mutant survives.
 *
 * <p>Two properties keep it honest and safe:
 *
 * <ul>
 *   <li>it never touches comments or string literals, so it cannot corrupt a message or a credential
 *       while looking for a mutation site;</li>
 *   <li>it only mutates an operator that is surrounded by whitespace and by value expressions, which
 *       is how rustfmt writes a binary operator. That deliberately skips unary minus, dereference and
 *       generic type arguments ({@code Vec<String>}), which would otherwise produce mutants that do
 *       not compile and tell us nothing.</li>
 * </ul>
 *
 * <p>The operator set is deliberately small and is stated in every report. A mutant that does not
 * compile is non-viable and is excluded from the score.
 */
final class RustMutator {

    /** Operator text to its replacement. Only these tokens are mutated. */
    private static final Map<String, String> MUTATIONS = mutationTable();

    /** Tokens that can end a value expression; an operator must follow one to be binary. */
    private static final Set<String> VALUE_END = Set.of(")", "]", "}", "?");

    /** Tokens that can start a value expression; an operator must precede one to be binary. */
    private static final Set<String> VALUE_START = Set.of("(", "[", "{", "-", "!", "&", "*");

    private RustMutator() {
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
                // true / false: a boolean literal is always safe to flip.
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
                i = skipString(source, i);
                tokens.add(new Token(source.substring(start, i), Token.Kind.LITERAL, start, line));
                continue;
            }
            if (c == '\'') {
                int start = i;
                i = skipCharOrLifetime(source, i);
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
                while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i))
                        || source.charAt(i) == '_' || source.charAt(i) == '.')) {
                    i++;
                }
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

    private static final String[] THREE = {"..=", "<<=", ">>="};
    private static final String[] TWO = {
        "->", "=>", "::", "==", "!=", "<=", ">=", "&&", "||", "+=", "-=", "*=", "/=", "%=",
        "<<", ">>", ".."
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
        if ("+-*/%<>=&|!^?~".indexOf(c) >= 0) {
            return String.valueOf(c);
        }
        return null;
    }

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
