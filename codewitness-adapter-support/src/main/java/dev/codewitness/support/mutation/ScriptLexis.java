package dev.codewitness.support.mutation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The lexical shape JavaScript and TypeScript share.
 *
 * <p>Both are mutated over the same operator set, including the strict-equality pair, and both
 * delimit literals the same way: single quotes, double quotes, and backticks for template literals.
 * Template literals are skipped whole rather than parsed for {@code ${...}} interpolation, which is
 * the conservative choice: an expression inside one is left unmutated rather than risk corrupting the
 * literal.
 *
 * <p>TypeScript brings no operator of its own to this set; the two languages differ in their type
 * syntax, not in the operators a test suite can be fooled by.
 */
public final class ScriptLexis implements LanguageLexis {

    /** The one instance: this shape carries no state. */
    public static final ScriptLexis INSTANCE = new ScriptLexis();

    /** Operator text to its replacement. Only these tokens are mutated. */
    private static final Map<String, String> MUTATIONS = mutationTable();

    /** Tokens that can end a value expression; an operator must follow one to be binary. */
    private static final Set<String> VALUE_END = Set.of(")", "]", "}");

    /** Tokens that can start a value expression; an operator must precede one to be binary. */
    private static final Set<String> VALUE_START = Set.of("(", "[", "{", "-", "+", "!", "~");

    private static final String[] THREE = {"===", "!==", "**=", "<<=", ">>=", "...", ">>>"};

    private static final String[] TWO = {
        "=>", "==", "!=", "<=", ">=", "&&", "||", "??", "?.", "+=", "-=", "*=", "/=", "%=",
        "**", "<<", ">>", "++", "--"
    };

    private ScriptLexis() {
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

    @Override
    public int skipLiteral(String source, int start) {
        char opener = source.charAt(start);
        if (opener != '"' && opener != '\'' && opener != '`') {
            return -1;
        }
        int i = start + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == opener) {
                return i + 1;
            }
            i++;
        }
        return source.length();
    }
}
