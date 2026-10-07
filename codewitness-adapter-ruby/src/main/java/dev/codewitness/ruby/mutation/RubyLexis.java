package dev.codewitness.ruby.mutation;

import dev.codewitness.support.mutation.LanguageLexis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The lexical shape of Ruby, as the shared source mutator reads it.
 *
 * <p>Ruby looks close enough to the C family that a scanner written for JavaScript compiles against it
 * and gets the interesting cases wrong. The differences this class exists to encode are:
 *
 * <ul>
 *   <li>{@code #} runs to the end of the line and {@code =begin} ... {@code =end} is a block comment
 *       that opens only in column zero; {@code __END__} in column zero ends the program and everything
 *       after it is data. {@link dev.codewitness.support.mutation.TextMutator} knows the {@code //} and
 *       {@code /* *}{@code /} comments of the C family but none of Ruby's, so each of these is reported
 *       as one opaque "literal" that the mutator skips whole;</li>
 *   <li>{@code /} opens a regular expression or divides, {@code <<IDENT} opens a heredoc or shifts,
 *       {@code %} opens a percent literal or takes a modulo, and {@code ?c} is a character literal or
 *       the ternary's question mark. Ruby decides all four by the state of the expression before them,
 *       and the shared mutator hands the lexis only an offset, so this class reads backwards:</li>
 *   <li>a quoted string and a backtick shell command interpolate {@code #{...}} holes that contain real
 *       code, so the hole is consumed as part of the literal rather than left to be scanned as Ruby.
 *       That is the conservative direction: missing a site inside a hole costs one mutant, while
 *       mutating inside a string changes the string;</li>
 *   <li>a numeric literal carries {@code _} separators, a base prefix, an exponent and one of the
 *       {@code r} / {@code i} suffixes, and it does <em>not</em> continue through a dot unless a digit
 *       follows it: {@code total.to_s} is a method call on a number, not one long number.</li>
 * </ul>
 *
 * <p>Two consequences of the last point are stated rather than hidden. The sign of a numeric literal is
 * not a token of its own, so this table cannot express the {@code +1} / {@code -1} flip the sibling
 * adapters express as an operator pair; a sign that is already a binary operator is covered by the
 * {@code +} / {@code -} entry, and a sign glued to a literal is not a site the shared tokenizer can
 * see. And {@code x.nil?} is one method name, not the keyword {@code nil} followed by a stray
 * question mark, so a name that merely starts with a mutated word is reported as a whole opaque token
 * - see {@link #methodNameEnd} - or the mutator would rewrite it into {@code x.false?}, which does not
 * parse.
 *
 * <p>As in the other adapters, only an operator surrounded by whitespace and sitting between two value
 * expressions is a site. That is what keeps the unary {@code -}, the splat, the namespace separator and
 * the {@code ::} of {@code Foo::Bar} from becoming mutants that cannot parse and therefore say nothing
 * about the test suite.
 */
public final class RubyLexis implements LanguageLexis {

    /** The one instance: this shape carries no state. */
    public static final RubyLexis INSTANCE = new RubyLexis();

    /** Operator text to its replacement. Only these tokens are mutated. */
    private static final Map<String, String> MUTATIONS = mutationTable();

    /** Tokens that can end a value expression; an operator must follow one to be binary. */
    private static final Set<String> VALUE_END = Set.of(")", "]", "}");

    /**
     * Tokens that can start a value expression; an operator must precede one to be binary.
     *
     * <p>Only the unary forms Ruby shares with the C family are here. The splat {@code *args} and the
     * block pass {@code &blk} are deliberately absent: they start a value too, but admitting them would
     * turn {@code a * *b} into a site whose mutant cannot parse, and a mutant that does not parse tells
     * us nothing about the tests.
     */
    private static final Set<String> VALUE_START = Set.of("(", "[", "{", "-", "+", "!", "~");

    /**
     * Ruby's three-character punctuation.
     *
     * <p>{@code ===} is here so that case equality is one token rather than {@code ==} followed by
     * {@code =}, {@code <=>} so that the spaceship is not read as {@code <=}, and the compound
     * assignments and the endless range so that no mutant ever splits one in half.
     */
    private static final String[] THREE = {"**=", "<<=", ">>=", "&&=", "||=", "===", "<=>", "..."};

    /**
     * Ruby's two-character punctuation, including the compounds that must not be split.
     *
     * <p>{@code ::}, {@code &amp;.} and {@code ->} are not operators a test suite can be fooled by - a
     * mutant in a namespace or in a lambda arrow would not parse - but leaving them out would expose
     * their {@code :}, {@code &} and {@code -} as tokens that a later site check has to reject, and the
     * mutator is easier to trust when the token stream is the language's rather than a prefix of it.
     */
    private static final String[] TWO = {
        "=>", "==", "!=", "<=", ">=", "&&", "||", "=~", "!~", "+=", "-=", "*=", "/=", "%=", "|=", "&=",
        "^=", "**", "<<", ">>", "..", "::", "&.", "->"
    };

    /**
     * The words before which a literal may begin, for {@link #opensExpression}.
     *
     * <p>These are Ruby's expression-opening keywords plus the handful of command-call methods
     * ({@code puts}, {@code raise}, {@code expect}) that are followed by an argument rather than by an
     * operator. The list is what makes {@code puts <<~SQL} a heredoc while {@code list << item} stays a
     * shift: the mutator cannot ask the parser, so the words that can only be followed by an expression
     * are named here.
     *
     * <p>{@code class} is deliberately <em>not</em> one of them. After {@code class} the only thing that
     * begins with {@code <<} is the singleton-class arrow, {@code class << self}, which must not be read
     * as a heredoc named {@code self}; a literal never follows {@code class} directly.
     */
    private static final Set<String> EXPRESSION_OPENERS = Set.of(
            "return", "when", "then", "if", "unless", "elsif", "else", "case", "in", "do", "begin",
            "rescue", "ensure", "while", "until", "and", "or", "not", "yield", "break", "next", "redo",
            "retry", "super", "defined?", "puts", "print", "warn", "raise", "fail", "require",
            "require_relative", "load", "include", "extend", "prepend", "attr_reader", "attr_writer",
            "attr_accessor", "expect");

    /**
     * The operator symbols that may follow the colon of a symbol literal.
     *
     * <p>Ordered longest first, because {@code :<=>} is one symbol and {@code :<=} is another.
     */
    private static final String[] SYMBOL_OPERATORS = {
        "<=>", "===", "<<=", ">>=", "[]=", "==", "!=", "<=", ">=", "=~", "!~", "<<", ">>", "**", "[]",
        "+", "-", "*", "/", "%", "<", ">", "!", "~", "&", "|", "^", "`", "="
    };

    private RubyLexis() {
    }

    /**
     * @return the operator set this language is mutated over.
     *
     * <p>The comparison boundaries, the equality pair and the boolean operators are the shared C-family
     * set. Ruby's own additions are the exponent {@code **}, the modulo {@code %}, the spaceship
     * {@code <=>} and the match pair {@code =~} / {@code !~}, the keyword operators {@code and} /
     * {@code or}, the {@code nil} literal - which is the Ruby spelling of "no value" and falsifies a
     * test differently from {@code false} - and the {@code to_s} / {@code to_i} conversion pair, whose
     * two members agree for the values a test often happens to use and therefore produce survivors that
     * are worth knowing about.
     *
     * <p>{@code ===} maps to {@code ==} rather than within a strict pair, because Ruby has no
     * {@code !==} to map it back to; replacing case equality with equality changes the comparison's
     * outcome for every operand the suite tries, which is the experiment being run. {@code nil} maps to
     * {@code false} in one direction only: {@code false} is already spoken for by the boolean literal
     * pair, and two replacements for one token would be two operators wearing one name.
     */
    private static Map<String, String> mutationTable() {
        Map<String, String> mutations = new LinkedHashMap<>();
        mutations.put("+", "-");
        mutations.put("-", "+");
        mutations.put("*", "/");
        mutations.put("/", "*");
        mutations.put("%", "*");
        mutations.put("**", "*");
        mutations.put("<", "<=");
        mutations.put("<=", "<");
        mutations.put(">", ">=");
        mutations.put(">=", ">");
        mutations.put("==", "!=");
        mutations.put("!=", "==");
        mutations.put("===", "==");
        mutations.put("<=>", "==");
        mutations.put("=~", "!~");
        mutations.put("!~", "=~");
        mutations.put("&&", "||");
        mutations.put("||", "&&");
        mutations.put("and", "or");
        mutations.put("or", "and");
        mutations.put("true", "false");
        mutations.put("false", "true");
        mutations.put("nil", "false");
        mutations.put("to_s", "to_i");
        mutations.put("to_i", "to_s");
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
     * <p>The order of the cases below is the order in which Ruby's own lexer would answer them, and each
     * one has to be decided here because the shared mutator's comment handling is the C family's. The
     * two comment forms and the data section are reported as opaque literals; every other form is
     * consumed to its own closing delimiter, with interpolation holes consumed whole.
     */
    @Override
    public int skipLiteral(String source, int start) {
        return literalEnd(source, start);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The default would consume every letter after a digit, which in Ruby would swallow the method in
     * {@code 1.to_s} and hide both the {@code to_s} site and anything after it. A dot is taken here only
     * when a digit follows it, which is the only case Ruby's grammar makes part of a number.
     */
    @Override
    public int skipNumber(String source, int start) {
        int i = start;
        if (i + 1 < source.length() && source.charAt(i) == '0'
                && "xXbBoOdD".indexOf(source.charAt(i + 1)) >= 0) {
            // A base prefix; the digits that follow may be letters. Ruby has no exponent syntax for these.
            i += 2;
            while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i))
                    || source.charAt(i) == '_')) {
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
                while (i < source.length() && (Character.isDigit(source.charAt(i))
                        || source.charAt(i) == '_')) {
                    i++;
                }
            }
        }
        // The rational and imaginary suffixes belong to the literal, but only when they are not the first
        // letter of an identifier that happens to follow a number.
        if (i < source.length() && (source.charAt(i) == 'r' || source.charAt(i) == 'i')
                && (i + 1 >= source.length() || !isIdentifierChar(source.charAt(i + 1)))) {
            i++;
        }
        return i;
    }

    /**
     * @return the offset just past the literal, comment or data section that opens at {@code start}, or
     *     {@code -1} when nothing opens there.
     *
     * <p>A single {@code /}, {@code %}, {@code ?} or {@code <<} is only a literal when the text before it
     * says so, which is why each of those cases asks {@link #opensExpression} first. The answer is biased
     * towards "not a literal": reading a division as a regular expression hides every site on the line,
     * while reading a regular expression as a division at worst produces a mutant that does not parse -
     * and a mutant that does not parse is excluded from the score rather than mistaken for evidence.
     */
    private static int literalEnd(String source, int start) {
        if (source == null || start < 0 || start >= source.length()) {
            return -1;
        }
        char c = source.charAt(start);
        if (c == '#') {
            return commentEnd(source, start);
        }
        if (c == '=') {
            return blockCommentEnd(source, start);
        }
        if (c == '\'' || c == '"') {
            return quotedEnd(source, start, c);
        }
        if (c == '`') {
            return quotedEnd(source, start, c);
        }
        if (c == ':') {
            return symbolEnd(source, start);
        }
        if (c == '?') {
            return characterLiteralEnd(source, start);
        }
        if (c == '%') {
            return percentLiteralEnd(source, start);
        }
        if (c == '<') {
            return heredocEnd(source, start);
        }
        if (c == '/') {
            return regexEnd(source, start);
        }
        if (c == '_') {
            return dataSectionEnd(source, start);
        }
        return methodNameEnd(source, start);
    }

    /** @return the offset of the newline that ends a {@code #} comment, or the end of the file. */
    private static int commentEnd(String source, int start) {
        return lineEnd(source, start);
    }

    /**
     * @return the offset just past the {@code =end} line of a block comment that opens at {@code start},
     *     or {@code -1} when {@code =begin} does not open there.
     *
     * <p>{@code =begin} is only a comment in column zero: the same text indented is a syntax error, and
     * a scanner that accepted it would hide the code after it. The same column rule ends the comment,
     * and an unterminated comment is reported as running to the end of the file, which is what the
     * interpreter reports too.
     */
    private static int blockCommentEnd(String source, int start) {
        if (start > 0 && source.charAt(start - 1) != '\n' && source.charAt(start - 1) != '\r') {
            return -1;
        }
        int after = start + "=begin".length();
        if (!source.startsWith("=begin", start) || after >= source.length()
                || !Character.isWhitespace(source.charAt(after))) {
            return -1;
        }
        int lineStart = after;
        while (lineStart <= source.length()) {
            int end = lineEnd(source, lineStart);
            if (source.startsWith("=end", lineStart)
                    && (lineStart + "=end".length() >= end
                    || Character.isWhitespace(source.charAt(lineStart + "=end".length())))) {
                return end;
            }
            if (end >= source.length()) {
                break;
            }
            lineStart = end + 1;
        }
        return source.length();
    }

    /**
     * @return the end of the file when {@code __END__} sits in column zero at {@code start}, otherwise
     *     {@code -1}.
     *
     * <p>Everything after the marker is the {@code DATA} stream and not Ruby, so the mutator must not
     * look for sites in it. The marker is reached before the identifier path because the shared
     * tokenizer asks for a literal at every offset, letters included, which is also how this case can be
     * handled at all: an identifier-shaped word would otherwise be claimed first.
     */
    private static int dataSectionEnd(String source, int start) {
        if (start > 0 && source.charAt(start - 1) != '\n' && source.charAt(start - 1) != '\r') {
            return -1;
        }
        if (!source.startsWith("__END__", start)) {
            return -1;
        }
        int after = start + "__END__".length();
        if (after < source.length() && source.charAt(after) != '\n' && source.charAt(after) != '\r') {
            return -1;
        }
        return source.length();
    }

    /**
     * @return the offset just past the quoted or backtick literal that opens at {@code start}.
     *
     * <p>A single-quoted string knows only {@code \\} and {@code \'}; a backslash before anything else is
     * literal text there, and treating it as an escape would hide the string's own closing quote. A
     * double-quoted string and a backtick command interpolate, so their {@code #{...}} holes and
     * {@code #@name} / {@code #$name} shorthands are consumed as part of the literal.
     *
     * <p>An unterminated literal is reported as running to the end of the file rather than stopping at
     * the newline the way the single-line languages' lexers do, because a Ruby string legitimately spans
     * lines. A file in that state does not parse, so nothing is lost that the interpreter would not
     * report first.
     */
    private static int quotedEnd(String source, int start, char quote) {
        boolean interpolates = quote != '\'';
        int i = start + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                if (quote == '\'' && (i + 1 >= source.length()
                        || (source.charAt(i + 1) != '\\' && source.charAt(i + 1) != '\''))) {
                    i++;
                    continue;
                }
                i += 2;
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            if (interpolates && c == '#' && i + 1 < source.length()) {
                char next = source.charAt(i + 1);
                if (next == '{') {
                    i = interpolationHoleEnd(source, i + 2);
                    continue;
                }
                if (next == '@' || next == '$') {
                    i = interpolationNameEnd(source, i + 1);
                    continue;
                }
            }
            i++;
        }
        return source.length();
    }

    /**
     * @return the offset just past the {@code }} that closes a {@code #{...}} hole opened before it.
     *
     * <p>The hole contains Ruby, so its braces nest and it may hold further literals; those are consumed
     * with the offsets this class would give them, which keeps the recursion in step with the text.
     */
    private static int interpolationHoleEnd(String source, int start) {
        int i = start;
        int depth = 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
                i++;
                continue;
            }
            if (c == '}') {
                depth--;
                i++;
                if (depth == 0) {
                    return i;
                }
                continue;
            }
            int end = literalEnd(source, i);
            if (end > i) {
                i = end;
                continue;
            }
            i++;
        }
        return source.length();
    }

    /** @return the offset just past the {@code #@name} or {@code #$name} shorthand that starts at the sigil. */
    private static int interpolationNameEnd(String source, int at) {
        int i = at + 1;
        if (i < source.length() && source.charAt(i) == '@') {
            i++;
        }
        while (i < source.length() && isIdentifierChar(source.charAt(i))) {
            i++;
        }
        return i;
    }

    /**
     * @return the offset just past the percent literal that opens at {@code start}, or {@code -1} when
     *     the {@code %} takes a modulo there.
     *
     * <p>A type letter followed by a delimiter is always a literal: {@code a %w[x]} is not a way to write
     * a modulo, and the common spellings ({@code %w[...]}, {@code %q(...)}, {@code %i{...}}) must be
     * recognized wherever they appear, {@code puts %w[a b]} included. A bare delimiter needs the
     * expression state, so that {@code x % (y)} keeps its operator. {@code =} is never a delimiter
     * because {@code %=} is the compound assignment.
     *
     * <p>Paired delimiters nest and the rest close at the next occurrence of themselves, both with
     * backslash escapes, and both may span lines.
     */
    private static int percentLiteralEnd(String source, int start) {
        int i = start + 1;
        if (i >= source.length()) {
            return -1;
        }
        char type = 0;
        if ("qQwWirsxI".indexOf(source.charAt(i)) >= 0) {
            type = source.charAt(i);
            i++;
        }
        if (i >= source.length()) {
            return -1;
        }
        char open = source.charAt(i);
        if (open == '=' || Character.isLetterOrDigit(open) || Character.isWhitespace(open) || open == '\\') {
            return -1;
        }
        if (type == 0 && !opensExpression(source, start)) {
            return -1;
        }
        char close = closingDelimiter(open);
        boolean paired = close != open;
        boolean interpolates = type == 0
                || "QWxrI".indexOf(type) >= 0;
        int depth = 1;
        int j = i + 1;
        while (j < source.length()) {
            char c = source.charAt(j);
            if (c == '\\') {
                j += 2;
                continue;
            }
            if (interpolates && c == '#' && j + 1 < source.length() && source.charAt(j + 1) == '{') {
                j = interpolationHoleEnd(source, j + 2);
                continue;
            }
            if (paired && c == open) {
                depth++;
                j++;
                continue;
            }
            if (c == close) {
                depth--;
                j++;
                if (depth == 0) {
                    return j;
                }
                continue;
            }
            j++;
        }
        return source.length();
    }

    /** @return the delimiter that closes {@code open}, itself when the delimiter is not a paired one. */
    private static char closingDelimiter(char open) {
        return switch (open) {
            case '(' -> ')';
            case '[' -> ']';
            case '{' -> '}';
            case '<' -> '>';
            default -> open;
        };
    }

    /**
     * @return the offset just past the regular expression that opens at {@code start}, or {@code -1}
     *     when the slash is a division.
     *
     * <p>This is Ruby's own ambiguity and it is resolved the way the PHP lexis resolves its own: by
     * asking what the text before the slash is rather than by guessing from the slash. A regular
     * expression may follow an operator, an opening bracket, a comma or an expression-opening keyword; a
     * value, a closing bracket or another literal can only be divided. A slash with no closing slash on
     * its own line is a division - a regular expression may not span lines in Ruby - which is the
     * conservative answer, because a division mistaken for a regular expression would hide every site
     * after it. Character classes are tracked so that the {@code /} of {@code /[/]/} does not close the
     * literal, and the trailing flags are left to be scanned as an identifier.
     */
    private static int regexEnd(String source, int start) {
        if (start + 1 >= source.length() || !opensExpression(source, start)) {
            return -1;
        }
        int i = start + 1;
        boolean inClass = false;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '\n') {
                return -1;
            }
            if (inClass) {
                if (c == ']') {
                    inClass = false;
                }
                i++;
                continue;
            }
            if (c == '[') {
                inClass = true;
                i++;
                continue;
            }
            if (c == '/') {
                return i + 1;
            }
            i++;
        }
        return -1;
    }

    /**
     * @return the offset just past the character literal that opens at {@code start}, or {@code -1} when
     *     the question mark is the ternary's.
     *
     * <p>{@code ?a} is Ruby's character literal, and the shared tokenizer would otherwise see a question
     * mark between two values. Whitespace after the mark rules the literal out immediately - that is
     * {@code a ? b : c} - and {@link #opensExpression} rules it out after a value, which is where Ruby's
     * own lexer puts a ternary. An escape is consumed by its own length rather than interpreted, so the
     * multi-character forms {@code ?\u00e9}, {@code ?\M-a} and {@code ?\123} end where the interpreter
     * says they do.
     */
    private static int characterLiteralEnd(String source, int start) {
        int i = start + 1;
        if (i >= source.length() || !opensExpression(source, start)) {
            return -1;
        }
        char c = source.charAt(i);
        if (Character.isWhitespace(c)) {
            return -1;
        }
        if (c == '\\') {
            return escapeEnd(source, i);
        }
        return i + (Character.isHighSurrogate(c) && i + 1 < source.length() ? 2 : 1);
    }

    /** @return the offset just past the escape sequence whose backslash is at {@code backslash}. */
    private static int escapeEnd(String source, int backslash) {
        int i = backslash + 1;
        if (i >= source.length()) {
            return i;
        }
        char c = source.charAt(i);
        if (c == 'u') {
            i++;
            if (i < source.length() && source.charAt(i) == '{') {
                int close = source.indexOf('}', i);
                return close < 0 ? source.length() : close + 1;
            }
            return skipDigits(source, i, 4, 16);
        }
        if (c == 'x') {
            return skipDigits(source, i + 1, 2, 16);
        }
        if ((c == 'M' || c == 'C') && i + 1 < source.length() && source.charAt(i + 1) == '-') {
            int operand = i + 2;
            if (operand < source.length() && source.charAt(operand) == '\\') {
                return escapeEnd(source, operand);
            }
            return operand + 1;
        }
        if (Character.digit(c, 8) >= 0) {
            // A leading zero is an octal escape of up to three digits, not the digit itself.
            return skipDigits(source, i, 3, 8);
        }
        return i + 1;
    }

    /** @return the offset after at most {@code limit} digits of the given radix, or {@code from}. */
    private static int skipDigits(String source, int from, int limit, int radix) {
        int i = from;
        int digits = 0;
        while (i < source.length() && digits < limit && Character.digit(source.charAt(i), radix) >= 0) {
            i++;
            digits++;
        }
        return digits == 0 ? from : i;
    }

    /**
     * @return the offset just past the symbol literal that opens at {@code start}, or {@code -1} when the
     *     colon is a ternary's.
     *
     * <p>{@code :foo}, {@code :foo?}, {@code :@ivar}, {@code :@@cvar}, {@code :$global}, {@code :"quoted"}
     * and the operator symbols {@code :+}, {@code :[]=} are all one literal whose contents are names
     * rather than code; mutating inside one would change the name. {@code ::} is refused so that
     * {@code Foo::Bar} keeps its namespace separator, and a colon followed by a space or a value stays
     * the punctuation of a ternary or a hash key.
     */
    private static int symbolEnd(String source, int start) {
        int i = start + 1;
        if (i >= source.length()) {
            return -1;
        }
        char c = source.charAt(i);
        if (c == '"' || c == '\'') {
            return quotedEnd(source, i, c);
        }
        if (c == '@' || c == '$') {
            i++;
            if (i < source.length() && source.charAt(i) == '@') {
                i++;
            }
            int nameStart = i;
            while (i < source.length() && isIdentifierChar(source.charAt(i))) {
                i++;
            }
            return i > nameStart ? i : -1;
        }
        if (c == ':') {
            return -1;
        }
        if (isIdentifierStart(c)) {
            i++;
            while (i < source.length() && isIdentifierChar(source.charAt(i))) {
                i++;
            }
            if (i < source.length() && (source.charAt(i) == '?' || source.charAt(i) == '!')) {
                i++;
            } else if (i < source.length() && source.charAt(i) == '=' && !isOperatorAfter(i + 1, source)) {
                i++;
            }
            return i;
        }
        for (String operator : SYMBOL_OPERATORS) {
            if (source.startsWith(operator, i)) {
                return i + operator.length();
            }
        }
        return -1;
    }

    /** @return {@code true} when the text at {@code at} continues an {@code ==}, {@code =~} or {@code =>}. */
    private static boolean isOperatorAfter(int at, String source) {
        return at < source.length() && (source.charAt(at) == '=' || source.charAt(at) == '~'
                || source.charAt(at) == '>');
    }

    /**
     * @return the offset just past the heredoc whose opener begins at {@code start}, or {@code -1} when
     *     the {@code <<} is a shift or an append.
     *
     * <p>Two things make an opener: the expression state says a value may begin there, and a line later
     * in the file is exactly the label. The second test is what separates {@code list &lt;&lt; item}
     * from {@code puts &lt;&lt;~SQL}: a shift has no terminator line, and searching for one costs a file
     * scan while a shift mistaken for a heredoc would hide every site between the two. An opener whose
     * line carries anything but whitespace after the label is rejected, which keeps
     * {@code File.open(<<~SQL, "r")} working while {@code x << "suffix"} stays an append.
     *
     * <p>The body is consumed to the terminator without parsing interpolation: a hole in a heredoc is
     * text the interpreter would substitute, and the conservative answer is to leave the whole body
     * alone.
     */
    private static int heredocEnd(String source, int start) {
        if (!source.startsWith("<<", start) || !opensExpression(source, start)) {
            return -1;
        }
        int i = start + 2;
        boolean indented = i < source.length() && (source.charAt(i) == '-' || source.charAt(i) == '~');
        if (indented) {
            i++;
        }
        char quote = 0;
        if (i < source.length() && (source.charAt(i) == '\'' || source.charAt(i) == '"')) {
            quote = source.charAt(i);
            i++;
        }
        if (i >= source.length() || !isIdentifierStart(source.charAt(i))) {
            return -1;
        }
        int labelStart = i;
        i++;
        while (i < source.length() && isIdentifierChar(source.charAt(i))) {
            i++;
        }
        String label = source.substring(labelStart, i);
        if (quote != 0) {
            if (i >= source.length() || source.charAt(i) != quote) {
                return -1;
            }
            i++;
        }
        int openingEnd = lineEnd(source, i);
        if (!source.substring(i, openingEnd).isBlank()) {
            return -1;
        }
        return terminatorLine(source, openingEnd, label, indented);
    }

    /**
     * @return the offset just past the label on the line that terminates a heredoc, or {@code -1} when
     *     no line holds it.
     *
     * <p>A plain {@code <<ID} requires its terminator in column zero; the {@code <<-} and {@code <<~}
     * forms allow it to be indented, and this is the difference the two characters exist for. A file
     * whose terminator is missing is a file that does not parse, and answering "not a heredoc" there
     * keeps the mutator reading the rest of the file as code rather than hiding it.
     */
    private static int terminatorLine(String source, int openingEnd, String label, boolean indented) {
        int lineStart = openingEnd < source.length() ? openingEnd + 1 : source.length();
        while (lineStart <= source.length()) {
            int end = lineEnd(source, lineStart);
            int first = lineStart;
            if (indented) {
                while (first < end && (source.charAt(first) == ' ' || source.charAt(first) == '\t')) {
                    first++;
                }
            }
            if (first + label.length() <= end && source.startsWith(label, first)
                    && (first + label.length() == end
                    || !isIdentifierChar(source.charAt(first + label.length())))) {
                return first + label.length();
            }
            if (end >= source.length()) {
                break;
            }
            lineStart = end + 1;
        }
        return -1;
    }

    /**
     * @return the offset just past the method name that begins at {@code start} when that name merely
     *     starts with a word this language mutates, otherwise {@code -1}.
     *
     * <p>{@code x.nil?} is one name, but the tokenizer reads Ruby identifiers the C way: {@code ?} is not
     * an identifier character, so the word {@code nil} would be a token of its own and the mutator would
     * rewrite the call into {@code x.false?}, which does not parse. Reporting the whole name as an
     * opaque token suppresses a site that was never there, which is the same reasoning the PHP lexis
     * applies to {@code #[}. The setter form {@code to_s=} is handled by the same rule, and the
     * {@code =} is only taken when it is not the start of {@code ==}, {@code =~} or {@code =>}.
     */
    private static int methodNameEnd(String source, int start) {
        if (!Character.isJavaIdentifierStart(source.charAt(start))) {
            return -1;
        }
        int i = start;
        while (i < source.length() && Character.isJavaIdentifierPart(source.charAt(i))) {
            i++;
        }
        if (!MUTATIONS.containsKey(source.substring(start, i)) || i >= source.length()) {
            return -1;
        }
        char suffix = source.charAt(i);
        if (suffix == '?' || suffix == '!') {
            return i + 1;
        }
        if (suffix == '=' && !isOperatorAfter(i + 1, source)) {
            return i + 1;
        }
        return -1;
    }

    /**
     * @return {@code true} when an expression, rather than a value, may begin at {@code start}.
     *
     * <p>This is the one question Ruby's {@code /}, {@code <<}, {@code %} and {@code ?c} forms all turn
     * on, asked of the text rather than of the token stream because the shared mutator hands this class
     * only an offset. Whitespace before the offset is skipped; a character that can only end a value - a
     * closing or opening quote, a backtick, a closing bracket or a digit - answers no, and so does an
     * identifier, unless that identifier is one of the {@link #EXPRESSION_OPENERS} that can only be
     * followed by an expression. An identifier that is not on that list is a value, so {@code list <<
     * item} and {@code a / b} keep their operators.
     */
    private static boolean opensExpression(String source, int start) {
        int i = start - 1;
        while (i >= 0 && Character.isWhitespace(source.charAt(i))) {
            i--;
        }
        if (i < 0) {
            return true;
        }
        char c = source.charAt(i);
        if (c == ')' || c == ']' || c == '}' || c == '"' || c == '\'' || c == '`' || Character.isDigit(c)) {
            return false;
        }
        if (isIdentifierChar(c)) {
            int end = i + 1;
            while (i >= 0 && isIdentifierChar(source.charAt(i))) {
                i--;
            }
            return EXPRESSION_OPENERS.contains(source.substring(i + 1, end));
        }
        return true;
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_' || c >= 0x80;
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c >= 0x80;
    }

    /** @return the offset of the newline that ends the line holding {@code start}, or the end of the file. */
    private static int lineEnd(String source, int start) {
        int i = start;
        while (i < source.length() && source.charAt(i) != '\n') {
            i++;
        }
        return i;
    }
}
