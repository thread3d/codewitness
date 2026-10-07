package dev.codewitness.php.mutation;

import dev.codewitness.php.analysis.PhpSourceScan;
import dev.codewitness.support.mutation.LanguageLexis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The lexical shape of PHP, as the shared source mutator reads it.
 *
 * <p>PHP looks close enough to the C family that a scanner written for JavaScript compiles against it
 * and gets the interesting cases wrong. The differences this class exists to encode are:
 *
 * <ul>
 *   <li>{@code .} is string concatenation, and it is also the character a naive number scanner eats as
 *       a separator: {@code $a . 5} is an operator between two values, while {@code 1.5} is one float.
 *       {@link #skipNumber} therefore takes a dot only when a digit follows it, and {@code .} is a
 *       token of its own everywhere else;</li>
 *   <li>{@code ===} and {@code !==} are not {@code ==} and {@code !=}: they compare types as well as
 *       values, so they are tokenized in full and mutated within the strict pair. A scanner without the
 *       three-character forms would not merely pick the wrong replacement - it would read
 *       {@code $a === $b} as {@code ==} followed by {@code =} and miss the site;</li>
 *   <li>a single-quoted string, a double-quoted string, a heredoc, a nowdoc and a backtick shell string
 *       are not code. Those are PHP's own literal syntax, so their scanning is delegated to
 *       {@link PhpSourceScan}, which is the projection the rule table reads and which already gets the
 *       heredoc closing marker and the {@code {$...}} interpolation hole right. A second, subtly
 *       different implementation here is how the two halves of an adapter drift apart;</li>
 *   <li>{@code #[} opens a PHP 8 attribute, which <em>is</em> code, while a {@code #} anywhere else starts
 *       a comment. {@link dev.codewitness.support.mutation.TextMutator} knows the {@code //} and
 *       {@code /* *}{@code /} comments but not PHP's {@code #}, so this class must; it does so by
 *       reporting the comment as one opaque "literal", which the mutator skips whole.</li>
 * </ul>
 *
 * <p>As in the other adapters, only an operator surrounded by whitespace and sitting between two value
 * expressions is a site. That is what keeps {@code ->}, {@code ::}, {@code ??} and the arrow of a PHP 8
 * attribute from becoming mutants that cannot parse and therefore say nothing about the test suite.
 */
public final class PhpLexis implements LanguageLexis {

    /** The one instance: this shape carries no state. */
    public static final PhpLexis INSTANCE = new PhpLexis();

    /** Operator text to its replacement. Only these tokens are mutated. */
    private static final Map<String, String> MUTATIONS = mutationTable();

    /** Tokens that can end a value expression; an operator must follow one to be binary. */
    private static final Set<String> VALUE_END = Set.of(")", "]", "}");

    /** Tokens that can start a value expression; an operator must precede one to be binary. */
    private static final Set<String> VALUE_START = Set.of("(", "[", "{", "-", "+", "!", "~");

    /**
     * PHP's three-character punctuation.
     *
     * <p>{@code ===} and {@code !==} are here so that the strict pair is one token, {@code <=>} so that
     * the spaceship is not read as {@code <=}, {@code ?->} so that the nullsafe arrow is not read as a
     * ternary, and the compound assignments so that no mutant ever splits one in half.
     */
    private static final String[] THREE = {"===", "!==", "<=>", "**=", "<<=", ">>=", "??=", "...", "?->"};

    /**
     * PHP's two-character punctuation, including the compounds that must not be split.
     *
     * <p>{@code ->} and {@code ::} are not operators a test suite can be fooled by - a mutant in a method
     * name would not parse - but leaving them out would expose their {@code -} and {@code :} as tokens
     * that a later site check has to reject, and the mutator is easier to trust when the token stream is
     * the language's rather than a prefix of it.
     */
    private static final String[] TWO = {
        "==", "!=", "<=", ">=", "&&", "||", "??", "->", "::", "=>", ".=", "+=", "-=", "*=", "/=", "%=",
        "**", "<<", ">>", "++", "--"
    };

    private PhpLexis() {
    }

    /**
     * @return the operator set this language is mutated over.
     *
     * <p>The strict pair maps within itself rather than onto the loose pair: {@code ===} turning into
     * {@code !==} changes the comparison's outcome, while replacing it with {@code ==} would change what
     * the operator means and is a different experiment, one the C-family adapters do not attempt either.
     * {@code .} maps to {@code +} because that is the only single-character operator PHP can substitute
     * for concatenation, and the difference between joining two strings and adding two numbers is
     * exactly the kind of behaviour a test should pin down.
     */
    private static Map<String, String> mutationTable() {
        Map<String, String> mutations = new LinkedHashMap<>();
        mutations.put("+", "-");
        mutations.put("-", "+");
        mutations.put("*", "/");
        mutations.put("/", "*");
        mutations.put("%", "*");
        mutations.put(".", "+");
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
        return "+-*/%<>=&|!^~.?";
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code #} is the one thing this method has to decide for itself, because the shared mutator's
     * own comment handling is the C family's. A {@code #} followed by {@code [} starts a PHP 8 attribute,
     * which is code and must be scanned as such; any other {@code #} runs to the end of the line, and the
     * whole comment is reported as one opaque literal so that no operator inside it is ever a site.
     * Everything else - quoted strings, heredoc, nowdoc, backticks - is PHP's own literal syntax and is
     * left to {@link PhpSourceScan}, whose offsets are the ones the rule table already trusts.
     */
    @Override
    public int skipLiteral(String source, int start) {
        if (source == null || start < 0 || start >= source.length()) {
            return -1;
        }
        if (source.charAt(start) == '#') {
            if (start + 1 < source.length() && source.charAt(start + 1) == '[') {
                return -1;
            }
            return commentEnd(source, start);
        }
        return PhpSourceScan.literalEnd(source, start);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The default would consume every dot, which in PHP turns the concatenation in
     * {@code $total = $a . 5} into part of a number literal and loses the site. A dot is taken here only
     * when a digit follows it, which is the only case PHP's own grammar makes it part of a float.
     */
    @Override
    public int skipNumber(String source, int start) {
        int i = start;
        if (i + 1 < source.length() && source.charAt(i) == '0' && "xXbBoO".indexOf(source.charAt(i + 1)) >= 0) {
            // A base prefix; the digits that follow may be letters. PHP has no exponent syntax for these.
            i += 2;
            while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '_')) {
                i++;
            }
            return i;
        }
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
            if (exponent < source.length()
                    && (source.charAt(exponent) == '+' || source.charAt(exponent) == '-')) {
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

    /** @return the offset of the newline that ends a {@code #} comment, or the end of the file. */
    private static int commentEnd(String source, int start) {
        int i = start;
        while (i < source.length() && source.charAt(i) != '\n') {
            i++;
        }
        return i;
    }
}
