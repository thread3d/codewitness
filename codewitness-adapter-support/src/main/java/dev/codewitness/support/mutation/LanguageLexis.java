package dev.codewitness.support.mutation;

import java.util.Map;
import java.util.Set;

/**
 * The lexical shape of one C-like source language: its operators, the tokens that bound a value
 * expression, and how its literals are delimited.
 *
 * <p>{@link TextMutator} works on source text, so it has to know what that text means: which tokens
 * are operators at all ({@code ::} and {@code ?} exist in Rust but not in JavaScript), which of them
 * are worth flipping, what a literal looks like (a Rust lifetime and a Rust character literal open
 * with the same character; a Go raw string may span lines), and which tokens can end or start a value
 * expression, so a unary operator is never mistaken for a binary one.
 *
 * <p>This is a description of the language's lexis, not a strategy: an implementation answers
 * questions and holds no mutation logic of its own.
 */
public interface LanguageLexis {

    /**
     * Operator text to its replacement.
     *
     * @return the operator set this language is mutated over; only these tokens are mutated
     */
    Map<String, String> mutations();

    /**
     * Tokens that can end a value expression; an operator must follow one to be binary.
     *
     * @return the end-of-value tokens, e.g. {@code ")"}, {@code "]"}, {@code "}"}
     */
    Set<String> valueEnd();

    /**
     * Tokens that can start a value expression; an operator must precede one to be binary.
     *
     * @return the start-of-value tokens, e.g. {@code "("}, {@code "-"}, {@code "!"}
     */
    Set<String> valueStart();

    /** @return the language's three-character operators, or an empty array when it has none. */
    String[] threeCharOperators();

    /** @return the language's two-character operators. */
    String[] twoCharOperators();

    /**
     * The characters that are an operator on their own.
     *
     * @return each such character exactly once, e.g. {@code + - * &#47; %} for the script languages
     */
    String singleCharOperators();

    /**
     * Skips a literal that opens at {@code start}, respecting the language's own delimiters and
     * escape rules.
     *
     * <p>The same character means different things in different languages: {@code '} opens a string
     * in JavaScript, a character literal or a lifetime in Rust, and a rune in Go; only Go has backtick
     * raw strings.
     *
     * @param source the source text
     * @param start the offset of the candidate opener
     * @return the offset just past the literal, or {@code -1} when this language has no literal that
     *     opens with the character at {@code start}
     */
    int skipLiteral(String source, int start);

    /**
     * Skips a numeric literal.
     *
     * <p>The default consumes letters, digits, underscores and dots, which covers the languages whose
     * numeric literals have no signed exponent. Go overrides it: {@code 1e-3} would otherwise expose
     * its {@code -} as a bogus binary operator site.
     *
     * @param source the source text
     * @param start the offset of the first digit
     * @return the offset just past the literal
     */
    default int skipNumber(String source, int start) {
        int i = start;
        while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i))
                || source.charAt(i) == '_' || source.charAt(i) == '.')) {
            i++;
        }
        return i;
    }
}
