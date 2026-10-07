package dev.codewitness.ruby.mutation;

import dev.codewitness.support.mutation.TextMutator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Ruby lexical shape, exercised through the shared mutator.
 *
 * <p>Each test states what Ruby does that a scanner written for JavaScript would not: {@code #} and
 * {@code =begin} comments, heredocs, percent literals and character literals are not code, a slash is a
 * regular expression or a division depending on what precedes it, and an identifier that merely starts
 * with a mutated word is one name and not a keyword followed by a stray {@code ?}. A mutant that does not
 * parse tells us nothing about test strength, and a mutant inside a literal or a comment would change
 * data rather than behaviour.
 */
class RubyLexisTest {

    /** The shared mutator, reading Ruby through the adapter's own lexis. */
    private final TextMutator mutator = new TextMutator(RubyLexis.INSTANCE);

    @Test
    @DisplayName("binary operators, comparison boundaries and boolean literals are sites")
    void findsMutatableOperators() {
        String source = """
                def total(a, b)
                  if a + b > 10
                    return a * b
                  end
                  a - b % 3
                end

                flag = true
                falsey = false
                """;

        List<TextMutator.Site> sites = mutator.sites(source);

        assertEquals(List.of("+", ">", "*", "-", "%", "true", "false"),
                sites.stream().map(TextMutator.Site::operator).toList(), sites.toString());
        assertEquals(List.of(2, 2, 3, 5, 5, 8, 9),
                sites.stream().map(TextMutator.Site::line).toList(), sites.toString());
    }

    @Test
    @DisplayName("a # comment and a =begin/=end block comment are not code")
    void commentsAreNotCode() {
        String source = """
                total = a + b
                # total = a + b
                =begin
                total = a * b
                =end
                other = c - d # e + f
                """;

        List<TextMutator.Site> sites = mutator.sites(source);

        assertEquals(List.of("+", "-"), sites.stream().map(TextMutator.Site::operator).toList(),
                "only the statements outside the comments are code: " + sites);
        assertEquals(List.of(1, 6), sites.stream().map(TextMutator.Site::line).toList(), sites.toString());
        assertEquals(15, RubyLexis.INSTANCE.skipLiteral("# total = a + b\n", 0),
                "a # comment is skipped as one opaque token, up to but not including its newline");
        assertEquals(18, RubyLexis.INSTANCE.skipLiteral("=begin\nsecret\n=end\n", 0),
                "=begin ... =end is a block comment and must be skipped whole, or its operators become "
                        + "mutants that change a comment");
    }

    @Test
    @DisplayName("strings, interpolating strings, backticks and heredocs are not code")
    void literalsAreNotSites() {
        String source = """
                single = 'a + b'
                double = "c * d #{e - f}"
                shell = `g / h`
                doc = <<~TXT
                i % j
                TXT
                total = a + b
                """;

        List<TextMutator.Site> sites = mutator.sites(source);

        assertEquals(List.of("+"), sites.stream().map(TextMutator.Site::operator).toList(),
                "only the statement after the literals is code: " + sites);
        assertEquals(List.of(7), sites.stream().map(TextMutator.Site::line).toList(), sites.toString());

        assertEquals(7, RubyLexis.INSTANCE.skipLiteral("'a + b'", 0),
                "a single-quoted string knows only \\\\ and \\', so it ends at its own quote");
        assertEquals(7, RubyLexis.INSTANCE.skipLiteral("\"c * d\"", 0),
                "a double-quoted string ends at its own quote");
        assertEquals(7, RubyLexis.INSTANCE.skipLiteral("`g / h`", 0),
                "a backtick command is a literal like a string, so the slash inside is not an operator");
        assertEquals(14, RubyLexis.INSTANCE.skipLiteral("\"a #{b + c} d\"", 0),
                "the #{...} hole is consumed as part of the literal: mutating inside it would change the "
                        + "string, which is the conservative direction");
        assertEquals(16, RubyLexis.INSTANCE.skipLiteral("<<~TXT\ni % j\nTXT\n", 0),
                "a heredoc is a literal that spans lines, so it must be skipped whole");
        assertEquals(-1, RubyLexis.INSTANCE.skipLiteral("<<~TXT\nno terminator\n", 0),
                "a heredoc whose label never terminates is reported as not a heredoc, so the rest of the "
                        + "file keeps being read as code rather than being hidden");
    }

    @Test
    @DisplayName("%w[...] and %q(...) are literals, while a bare % after a value is modulo")
    void percentLiteralsAreNotCode() {
        String source = """
                words = %w[a + b]
                pattern = %q(c * d)
                other = e - f
                """;

        List<TextMutator.Site> sites = mutator.sites(source);

        assertEquals(List.of("-"), sites.stream().map(TextMutator.Site::operator).toList(),
                "the operator inside a percent literal is text, not code: " + sites);
        assertEquals(List.of(3), sites.stream().map(TextMutator.Site::line).toList(), sites.toString());
        assertEquals(9, RubyLexis.INSTANCE.skipLiteral("%w[a + b]", 0),
                "a type letter followed by a delimiter is always a percent literal");
        assertEquals(-1, RubyLexis.INSTANCE.skipLiteral("a % b", 2),
                "a bare delimiter needs the expression state, so x % (y) keeps its operator");
    }

    @Test
    @DisplayName("/regex/ is a literal while / is a division operator")
    void slashIsARegexOrADivision() {
        String source = """
                pattern = /\\d+/
                match = text =~ /x + y/
                total = a / b
                """;

        List<TextMutator.Site> sites = mutator.sites(source);

        assertEquals(List.of("=~", "/"), sites.stream().map(TextMutator.Site::operator).toList(),
                "the + inside the regular expression is not a site, and the match operator is: " + sites);
        assertEquals(List.of("!~", "*"), sites.stream().map(TextMutator.Site::replacement).toList(),
                sites.toString());
        assertEquals(List.of(2, 3), sites.stream().map(TextMutator.Site::line).toList(), sites.toString());
        assertEquals(5, RubyLexis.INSTANCE.skipLiteral("/\\d+/", 0),
                "a slash after = opens a regular expression, which ends at its closing slash");
        assertEquals(-1, RubyLexis.INSTANCE.skipLiteral("a / b", 2),
                "the same slash after a value can only divide");
    }

    @Test
    @DisplayName("?a is a character literal while a ? b : c is a ternary")
    void characterLiteralVersusTernary() {
        assertEquals(2, RubyLexis.INSTANCE.skipLiteral("?a", 0),
                "?a is a character literal, so the question mark is not an operator between two values");
        assertEquals(-1, RubyLexis.INSTANCE.skipLiteral("a ? b : c", 2),
                "whitespace after the mark rules the literal out: this is the ternary's question mark");
        assertEquals(List.of(), mutator.sites("x = cond ? one : two"),
                "neither ? nor : is in the mutation table, so a ternary contributes no site");
    }

    @Test
    @DisplayName("a name that merely starts with a mutated word is one opaque name, so no x.false? mutant is produced")
    void wholeMethodNamesAreOpaque() {
        assertEquals(6, RubyLexis.INSTANCE.skipLiteral("x.nil?", 2),
                "x.nil? is one method name; the C-family tokenizer would see the keyword nil and a stray ?");
        assertEquals(List.of(), mutator.sites("value = x.nil?"),
                "a nil mutant here would be x.false?, which does not parse and therefore says nothing "
                        + "about the test suite");

        assertEquals(1, RubyLexis.INSTANCE.skipNumber("1.to_s", 0),
                "a dot is part of a number only when a digit follows it, so the method is not swallowed");
        List<TextMutator.Site> conversion = mutator.sites("label = 1.to_s\n");
        assertEquals(List.of("to_s"), conversion.stream().map(TextMutator.Site::operator).toList(),
                "the conversion pair is a deliberate site, and it survives only because the number scan "
                        + "stopped at the dot: " + conversion);
        assertEquals(List.of("to_i"), conversion.stream().map(TextMutator.Site::replacement).toList(),
                conversion.toString());
    }

    @Test
    @DisplayName("a three-character operator is its own token, never a two-character lookalike")
    void mutationPairsFireOnTheIntendedOperator() {
        String source = """
                spaceship = a <=> b
                bound = a <= b
                caseeq = a === b
                """;

        List<TextMutator.Site> sites = mutator.sites(source);

        assertEquals(List.of("<=>", "<=", "==="), sites.stream().map(TextMutator.Site::operator).toList(),
                "<=> must not be read as <= followed by =, and <= must not be read as < followed by =: "
                        + sites);
        assertEquals(List.of("==", "<", "=="), sites.stream().map(TextMutator.Site::replacement).toList(),
                "the spaceship and the case equality both map to the equality the suite can be fooled by: "
                        + sites);
    }

    @Test
    @DisplayName("unspaced, unary, splat and namespace operators are not sites")
    void unspacedAndUnaryOperatorsAreNotSites() {
        String source = """
                total=a+b
                negative = -amount
                fallback = a * *b
                namespace = Foo::Bar
                """;

        assertEquals(List.of(), mutator.sites(source).stream().map(TextMutator.Site::operator).toList(),
                "a mutant of an unspaced, unary or namespace operator is noise, not evidence: "
                        + mutator.sites(source));
    }

    @Test
    @DisplayName("__END__ and symbol literals are data, not Ruby")
    void dataSectionAndSymbolsAreNotCode() {
        assertEquals(23, RubyLexis.INSTANCE.skipLiteral("__END__\nsecret = a + b\n", 0),
                "everything after a column-zero __END__ is the DATA stream, so the mutator must not look "
                        + "for sites in it");
        assertEquals(4, RubyLexis.INSTANCE.skipLiteral(":foo", 0),
                "a symbol literal's contents are a name, and mutating inside one would change the name");
        assertEquals(8, RubyLexis.INSTANCE.skipLiteral("x = :sym", 4));
        assertEquals(-1, RubyLexis.INSTANCE.skipLiteral("Foo::Bar::Baz", 3),
                ":: is refused so that a namespace keeps its separator");
    }

    @Test
    @DisplayName("numbers are scanned as Ruby writes them")
    void numbersAreScannedAsRubyWritesThem() {
        assertEquals(3, RubyLexis.INSTANCE.skipNumber("1.5;", 0), "a fractional number is one literal");
        assertEquals(4, RubyLexis.INSTANCE.skipNumber("0xFF;", 0), "a base prefix is part of the literal");
        assertEquals(1, RubyLexis.INSTANCE.skipNumber("5 . $b", 0),
                "a dot that is not followed by a digit is not part of the number");
        assertEquals(6, RubyLexis.INSTANCE.skipNumber("1.5e-3", 0),
                "a signed exponent is part of the number, so its minus is never a site");
        assertEquals(5, RubyLexis.INSTANCE.skipNumber("1_000", 0),
                "underscore separators are part of the literal, not a token boundary");
        assertEquals(2, RubyLexis.INSTANCE.skipNumber("2r", 0),
                "a rational suffix belongs to the literal, but only when no identifier continues it");
        assertEquals(0, RubyLexis.INSTANCE.skipNumber("total", 0),
                "a word is not a number, and the lexis must say so rather than consume it");
    }
}
