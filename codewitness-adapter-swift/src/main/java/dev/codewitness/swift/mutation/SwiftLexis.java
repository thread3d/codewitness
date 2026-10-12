package dev.codewitness.swift.mutation;

import dev.codewitness.support.mutation.LanguageLexis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The lexical shape of Swift, as the shared source mutator reads it.
 *
 * <p>Swift looks close enough to the C family that a scanner written for JavaScript compiles against
 * it and gets the interesting cases wrong. The differences this class exists to encode are:
 *
 * <ul>
 *   <li>a string opens with {@code "} and knows backslash escapes and {@code \(...)} interpolation,
 *       a multi-line string opens with {@code """} and closes at the next unescaped {@code """}, and a
 *       raw string opens with one or more {@code #} followed by {@code "} and closes only at a
 *       {@code "} followed by the same number of {@code #}. An interpolation hole contains real code,
 *       including nested string literals, so the hole is consumed as part of the literal rather than
 *       left to be scanned as Swift - the conservative direction, because missing a site inside a hole
 *       costs one mutant while mutating inside a string changes the string;</li>
 *   <li>Swift has no character literal at all: {@code '} is not a string opener here and must be left
 *       to the ordinary tokenizer, exactly as this class does by answering {@code -1} for it and for
 *       every other opener;</li>
 *   <li>Swift's operators include the identity pair {@code ===} / {@code !==}, which C has no notion
 *       of, so both are in the mutation table as a strict pair rather than mapping one way;</li>
 *   <li>the range operators, the compound assignments, the masking shifts and the optional-chaining
 *       punctuation must each be one token, or a later site check would have to reject the pieces they
 *       leave behind - {@code ..<} must not become {@code ..} and {@code <}, and {@code <<=} must not
 *       become {@code <<} and {@code =}.</li>
 * </ul>
 *
 * <p>As in the other adapters, only an operator surrounded by whitespace and sitting between two value
 * expressions is a site. That is what keeps the unary {@code -}, the force-unwrap {@code !}, the
 * optional-chaining {@code ?} and the generic angle brackets of {@code Array<Int>} from becoming
 * mutants that cannot compile and therefore say nothing about the test suite.
 */
public final class SwiftLexis implements LanguageLexis {

    /** The one instance: this shape carries no state. */
    public static final SwiftLexis INSTANCE = new SwiftLexis();

    /** Operator text to its replacement. Only these tokens are mutated. */
    private static final Map<String, String> MUTATIONS = mutationTable();

    /** Tokens that can end a value expression; an operator must follow one to be binary. */
    private static final Set<String> VALUE_END = Set.of(")", "]", "}");

    /**
     * Tokens that can start a value expression; an operator must precede one to be binary.
     *
     * <p>Only the prefix forms TextMutator already understands are here: the opening brackets and the
     * Swift prefix operators {@code -}, {@code +}, {@code !} and {@code ~}. {@code try} and
     * {@code await} also start an expression, but they are identifiers rather than tokens this set can
     * name, and an identifier already counts as a value on its own.
     */
    private static final Set<String> VALUE_START = Set.of("(", "[", "{", "-", "+", "!", "~");

    /**
     * Swift's three-character operators.
     *
     * <p>The range operators {@code ...} and {@code ..<} are here so a range is one token rather than a
     * sequence the mutator might split, the identity pair so {@code ===} is not read as {@code ==}
     * followed by {@code =}, the shift compounds and masking shifts so no later site check has to
     * reject their pieces.
     */
    private static final String[] THREE = {"...", "..<", "===", "!==", "<<=", ">>=", "&<<", "&>>"};

    /**
     * Swift's two-character operators, including the punctuation and compounds that must not split.
     *
     * <p>{@code ??} and {@code ?.} are punctuation rather than operators a test suite can be fooled by,
     * and {@code ->} is a function arrow; leaving any of them out would expose a {@code ?} or a
     * {@code -} as a separate token for a later check to reject. The overflow operators {@code &+},
     * {@code &-} and {@code &*} are Swift's own arithmetic spellings and are tokenized whole for the
     * same reason.
     */
    private static final String[] TWO = {
        "->", "==", "!=", "<=", ">=", "&&", "||", "??", "?.", "~=", "+=", "-=", "*=", "/=", "%=",
        "&=", "|=", "^=", "<<", ">>", "&+", "&-", "&*"
    };

    private SwiftLexis() {
    }

    /**
     * @return the operator set this language is mutated over.
     *
     * <p>The arithmetic operators, the comparison boundaries, the equality pair, Swift's own identity
     * pair, the boolean operators and the boolean literals - the same shape the script languages use,
     * extended by {@code ===} / {@code !==}. The identity pair is flipped within itself rather than
     * mapped one way, because replacing reference identity with value equality (and back) changes the
     * comparison's outcome for every operand a suite tries.
     */
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
        mutations.put("===", "!==");
        mutations.put("!==", "===");
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
        return "+-*/%<>=&|!^?~";
    }

    /**
     * {@inheritDoc}
     *
     * <p>Swift's literal openers are {@code "} and {@code #} followed by one or more of itself then
     * {@code "}. A single quote does not open anything, and neither does any other character: those
     * answers are {@code -1}. An unterminated literal is reported as running to the end of the file,
     * which is the conservative direction - the file does not compile either way, and hiding the rest
     * of the file cannot corrupt it.
     */
    @Override
    public int skipLiteral(String source, int start) {
        if (source == null || start < 0 || start >= source.length()) {
            return -1;
        }
        char opener = source.charAt(start);
        if (opener == '#') {
            return rawStringEnd(source, start);
        }
        if (opener != '"') {
            // Swift has no character literal and no backtick string, so a quote of any other kind is
            // not a literal opener and is left to the ordinary tokenizer.
            return -1;
        }
        if (source.startsWith("\"\"\"", start)) {
            return multilineStringEnd(source, start + 3);
        }
        return stringEnd(source, start + 1);
    }

    /**
     * @return the offset just past the raw string that opens with the hashes at {@code start}, or
     *     {@code -1} when nothing there is a raw string.
     *
     * <p>A raw string is {@code #"}, {@code ##"}, ... : one or more hashes, a quote, and then content
     * that closes only at a quote followed by the same number of hashes. Counting the hashes is the
     * whole reason this case exists - a content quote not followed by enough hashes is ordinary text,
     * and a scanner that closed at the first quote would end the literal early and read the rest of
     * the string as code.
     */
    private static int rawStringEnd(String source, int start) {
        int hashes = 0;
        int i = start;
        while (i < source.length() && source.charAt(i) == '#') {
            hashes++;
            i++;
        }
        if (i >= source.length() || source.charAt(i) != '"') {
            // A hash used for @available, #if, #selector and the like is not a literal opener.
            return -1;
        }
        if (source.startsWith("\"\"\"", i)) {
            return rawMultilineStringEnd(source, i + 3, hashes);
        }
        return rawStringContentEnd(source, i + 1, hashes);
    }

    /** @return the offset just past a raw single-line string that closes with {@code hashes} hashes. */
    private static int rawStringContentEnd(String source, int contentStart, int hashes) {
        int i = contentStart;
        while (i < source.length()) {
            if (source.charAt(i) == '"' && hashesAt(source, i + 1, hashes)) {
                return i + 1 + hashes;
            }
            i++;
        }
        return source.length();
    }

    /** @return the offset just past a raw multi-line string that closes with {@code hashes} hashes. */
    private static int rawMultilineStringEnd(String source, int contentStart, int hashes) {
        int i = contentStart;
        while (i < source.length()) {
            if (source.startsWith("\"\"\"", i) && hashesAt(source, i + 3, hashes)) {
                return i + 3 + hashes;
            }
            i++;
        }
        return source.length();
    }

    /** @return {@code true} when exactly {@code count} hashes begin at {@code at}. */
    private static boolean hashesAt(String source, int at, int count) {
        if (at + count > source.length()) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            if (source.charAt(at + i) != '#') {
                return false;
            }
        }
        return true;
    }

    /**
     * @return the offset just past the multi-line string whose opening {@code """} ends at
     *     {@code contentStart}.
     *
     * <p>Only an unescaped {@code """} closes a multi-line string; a single or double quote inside it
     * is ordinary text, which is what lets a multi-line string hold JSON or another Swift snippet. A
     * backslash escape hides the character after it, and a {@code \(} opens an interpolation hole that
     * is consumed whole.
     */
    private static int multilineStringEnd(String source, int contentStart) {
        int i = contentStart;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                if (i + 1 < source.length() && source.charAt(i + 1) == '(') {
                    i = interpolationEnd(source, i + 2);
                    continue;
                }
                i += 2;
                continue;
            }
            if (source.startsWith("\"\"\"", i)) {
                return i + 3;
            }
            i++;
        }
        return source.length();
    }

    /**
     * @return the offset just past the double-quoted string whose content starts at {@code start}.
     *
     * <p>A backslash escape hides the character after it, so an escaped quote does not close the
     * literal, and a {@code \(} opens an interpolation hole.
     */
    private static int stringEnd(String source, int contentStart) {
        int i = contentStart;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                if (i + 1 < source.length() && source.charAt(i + 1) == '(') {
                    i = interpolationEnd(source, i + 2);
                    continue;
                }
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
     * @return the offset just past the {@code )} that closes an interpolation hole whose content
     *     begins at {@code start}.
     *
     * <p>The hole contains Swift, so its parentheses nest and its own string literals must not have
     * their quotes counted as parentheses. The rule is deliberately conservative and single-level: a
     * nested literal is consumed whole with the same scanners, but nothing inside the hole is offered
     * as a mutation site. Missing a site inside a hole costs one mutant; mutating inside a string
     * changes what the string says.
     */
    private static int interpolationEnd(String source, int start) {
        int i = start;
        int depth = 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '(') {
                depth++;
                i++;
                continue;
            }
            if (c == ')') {
                depth--;
                i++;
                if (depth == 0) {
                    return i;
                }
                continue;
            }
            if (c == '"') {
                if (source.startsWith("\"\"\"", i)) {
                    i = multilineStringEnd(source, i + 3);
                } else {
                    i = stringEnd(source, i + 1);
                }
                continue;
            }
            if (c == '#') {
                int end = rawStringEnd(source, i);
                if (end > i) {
                    i = end;
                    continue;
                }
            }
            i++;
        }
        return source.length();
    }
}
