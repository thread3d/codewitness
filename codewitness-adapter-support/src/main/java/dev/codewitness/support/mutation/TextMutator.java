package dev.codewitness.support.mutation;

import java.util.ArrayList;
import java.util.List;

/**
 * A small, bounded source mutator for C-like source languages (JavaScript, TypeScript, Rust and Go
 * today).
 *
 * <p>A surviving mutant is the most direct evidence that a test suite does not constrain the behaviour
 * it appears to constrain, and it should not be unavailable just because a mutation framework is not
 * installed. This mutator therefore works on the source text itself: arithmetic operators, comparison
 * boundaries, equality, boolean operators and boolean literals are flipped one at a time, and the
 * project's own test suite decides whether each mutant survives.
 *
 * <p>Two properties keep it honest and safe:
 *
 * <ul>
 *   <li>it never touches comments or literals, so it cannot corrupt a message or a credential while
 *       looking for a mutation site;</li>
 *   <li>it only mutates an operator surrounded by whitespace and by value expressions, which is how a
 *       formatter writes a binary operator. That deliberately skips unary minus, dereference and
 *       generic type arguments ({@code Vec<String>}), which would otherwise produce mutants that do not
 *       compile and tell us nothing.</li>
 * </ul>
 *
 * <p>The operator set is deliberately small and is stated in every report. A mutant that does not
 * compile or parse is non-viable and is excluded from the score.
 *
 * <p>One copy of this class serves every adapter in the family. It used to exist as a separate
 * JavaScript, TypeScript, Rust and Go class, and the earlier splits are what let the later copies fall
 * behind on exactly the tokenizer edge cases above. What differs between the languages - which
 * operators exist, which are worth flipping, and how a literal is delimited - arrives through a
 * {@link LanguageLexis}.
 */
public final class TextMutator {

    /** The language whose source this instance reads. */
    private final LanguageLexis lexis;

    /**
     * @param lexis the language's lexical shape: its operator tables, the tokens that bound a value
     *     expression, and its literal delimiters
     */
    public TextMutator(LanguageLexis lexis) {
        this.lexis = lexis;
    }

    /** A single replaceable location: the token to change and where it is. */
    public record Site(String operator, String replacement, int offset, int line) {

        /** @return how the mutation reads in a report, for example {@code < -> <=}. */
        public String describe() {
            return operator + " -> " + replacement;
        }
    }

    private record Token(String text, Kind kind, int offset, int line) {
        enum Kind { IDENT, NUMBER, LITERAL, OPERATOR, PUNCT }
    }

    /** @return every mutatable site in the source, in source order. */
    public List<Site> sites(String source) {
        List<Token> tokens = tokenize(source);
        List<Site> sites = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            String replacement = lexis.mutations().get(token.text());
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
    public static String apply(String source, Site site) {
        return source.substring(0, site.offset()) + site.replacement()
                + source.substring(site.offset() + site.operator().length());
    }

    private boolean isSpaced(String source, Token token) {
        int before = token.offset() - 1;
        int after = token.offset() + token.text().length();
        return before >= 0 && after < source.length()
                && Character.isWhitespace(source.charAt(before))
                && Character.isWhitespace(source.charAt(after));
    }

    private boolean endsValue(Token token) {
        if (token == null) {
            return false;
        }
        return token.kind() == Token.Kind.IDENT || token.kind() == Token.Kind.NUMBER
                || token.kind() == Token.Kind.LITERAL || lexis.valueEnd().contains(token.text());
    }

    private boolean startsValue(Token token) {
        if (token == null) {
            return false;
        }
        return token.kind() == Token.Kind.IDENT || token.kind() == Token.Kind.NUMBER
                || token.kind() == Token.Kind.LITERAL || lexis.valueStart().contains(token.text());
    }

    private List<Token> tokenize(String source) {
        String[] threeChar = lexis.threeCharOperators();
        String[] twoChar = lexis.twoCharOperators();
        String singleChar = lexis.singleCharOperators();
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
            int literalEnd = lexis.skipLiteral(source, i);
            if (literalEnd >= 0) {
                int start = i;
                tokens.add(new Token(source.substring(start, literalEnd), Token.Kind.LITERAL, start, line));
                // A literal may span lines (a Go raw string, a template literal), and it is consumed
                // outside the main loop, so its newlines must be counted here or every later site would
                // report the wrong line.
                line += newlinesBetween(source, start, literalEnd);
                i = literalEnd;
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
                i = lexis.skipNumber(source, i);
                tokens.add(new Token(source.substring(start, i), Token.Kind.NUMBER, start, line));
                continue;
            }
            String operator = longestOperator(source, i, threeChar, twoChar, singleChar);
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

    private static String longestOperator(
            String source, int offset, String[] threeChar, String[] twoChar, String singleChar) {
        if (offset + 3 <= source.length()) {
            String candidate = source.substring(offset, offset + 3);
            for (String operator : threeChar) {
                if (operator.equals(candidate)) {
                    return operator;
                }
            }
        }
        if (offset + 2 <= source.length()) {
            String candidate = source.substring(offset, offset + 2);
            for (String operator : twoChar) {
                if (operator.equals(candidate)) {
                    return operator;
                }
            }
        }
        char c = source.charAt(offset);
        if (singleChar.indexOf(c) >= 0) {
            return String.valueOf(c);
        }
        return null;
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
