package dev.codewitness.kotlin.mutation;

import dev.codewitness.kotlin.analysis.KotlinSourceScan;
import dev.codewitness.support.mutation.LanguageLexis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The lexical shape of Kotlin, as the shared source mutator reads it.
 *
 * <p>Kotlin's operator set is the C family's plus a few that a text-level mutator would otherwise get
 * wrong. {@code ===} and {@code !==} are referential equality and are not the same operator as
 * {@code ==}, so they are in the three-character table and never mutated - without them, {@code a === b}
 * would offer its inner {@code ==} as a mutation site. {@code ..<} (the half-open range, which is a
 * three-character token) and {@code ..} likewise must be read whole so that the {@code <} inside is not
 * mistaken for a comparison. And {@code ?:}, {@code ?.}, {@code !!} and {@code ::} are two-character
 * operators whose parts must not be mutated separately.
 *
 * <p>Literal boundaries are not re-implemented here: they come from {@link KotlinSourceScan}, the same
 * scanner the heuristic analyzer reads the source with. Two copies of "where does this literal end" is
 * exactly how the earlier per-language mutators drifted apart. A raw string, a template hole and a
 * backtick identifier are therefore all consumed as literals, which is the conservative direction: missing
 * a site costs one mutant, while mutating inside a template would change a string.
 *
 * <p>Block comments do not nest in the shared tokenizer, so a Kotlin file with a nested block comment is
 * read as if the outer comment ended at the first {@code *}{@code /}. The effect is bounded and safe: at
 * worst a few characters of comment are treated as code and a mutant is generated there, and a mutant that
 * does not compile is excluded from the score rather than counted as killed.
 */
public final class KotlinLexis implements LanguageLexis {

    /** The one instance: this shape carries no state. */
    public static final KotlinLexis INSTANCE = new KotlinLexis();

    /** Operator text to its replacement. Only these tokens are mutated. */
    private static final Map<String, String> MUTATIONS = mutationTable();

    /** Tokens that can end a value expression; an operator must follow one to be binary. */
    private static final Set<String> VALUE_END = Set.of(")", "]", "}");

    /** Tokens that can start a value expression; an operator must precede one to be binary. */
    private static final Set<String> VALUE_START = Set.of("(", "[", "{", "-", "!", "~");

    /** Kotlin's three-character punctuation, including the compounds that must not be split. */
    private static final String[] THREE = {"===", "!==", "..<"};

    /**
     * Kotlin's two-character punctuation.
     *
     * <p>The assignment compounds and the increment and decrement operators are here so that the {@code +}
     * in {@code +=} or {@code ++} is never read as a binary operator site, and the elvis, safe-call,
     * non-null assertion and callable-reference operators are here so that their parts are never read as
     * operators at all.
     */
    private static final String[] TWO = {
        "==", "!=", "<=", ">=", "&&", "||", "+=", "-=", "*=", "/=", "%=",
        "++", "--", "::", "..", "?.", "?:", "!!", "->"
    };

    private KotlinLexis() {
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
        return KotlinSourceScan.literalEnd(source, start);
    }

    /**
     * Skips a Kotlin numeric literal.
     *
     * <p>The default would consume letters, which turns {@code 1.toString()} into a single number token and
     * hides the call, and would expose the {@code -} of an exponent such as {@code 1e-3} as a binary operator
     * site. Only the digits, the radix prefixes, one fractional part, an exponent and the suffixes Kotlin
     * actually defines are consumed here. The decimal point is taken only when a digit follows it, so that
     * {@code 1..5} is a number, a range operator and a number rather than a malformed literal.
     */
    @Override
    public int skipNumber(String source, int start) {
        int i = start;
        if (i + 1 < source.length() && source.charAt(i) == '0') {
            char prefix = source.charAt(i + 1);
            if (prefix == 'x' || prefix == 'X' || prefix == 'b' || prefix == 'B') {
                i += 2;
                while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i))
                        || source.charAt(i) == '_')) {
                    i++;
                }
                return i;
            }
        }
        while (i < source.length() && (Character.isDigit(source.charAt(i)) || source.charAt(i) == '_')) {
            i++;
        }
        if (i + 1 < source.length() && source.charAt(i) == '.' && Character.isDigit(source.charAt(i + 1))) {
            i++;
            while (i < source.length() && (Character.isDigit(source.charAt(i)) || source.charAt(i) == '_')) {
                i++;
            }
        }
        if (i < source.length() && (source.charAt(i) == 'e' || source.charAt(i) == 'E')) {
            i++;
            if (i < source.length() && (source.charAt(i) == '+' || source.charAt(i) == '-')) {
                i++;
            }
            while (i < source.length() && (Character.isDigit(source.charAt(i)) || source.charAt(i) == '_')) {
                i++;
            }
        }
        while (i < source.length() && "fFdDuUlL".indexOf(source.charAt(i)) >= 0) {
            i++;
        }
        return i;
    }
}
