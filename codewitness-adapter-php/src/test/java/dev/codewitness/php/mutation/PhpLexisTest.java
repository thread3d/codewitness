package dev.codewitness.php.mutation;

import dev.codewitness.support.mutation.TextMutator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The PHP lexical shape, exercised through the shared mutator.
 *
 * <p>Each test states what PHP does that a scanner written for JavaScript would not: it must find the
 * operator sites without corrupting a comment or a literal, and it must not lose a site to a rule that is
 * true of another language. A mutant that does not parse tells us nothing about test strength, and a
 * mutant inside a literal or a comment would change data rather than behaviour.
 */
class PhpLexisTest {

    /** The shared mutator, reading PHP through the adapter's own lexis. */
    private final TextMutator mutator = new TextMutator(PhpLexis.INSTANCE);

    @Test
    @DisplayName("binary operators, comparison boundaries and boolean literals are sites")
    void findsMutatableOperators() {
        String source = """
                <?php

                final class Ledger
                {
                    public function total(int $a, int $b): int
                    {
                        if ($a + $b > 10) {
                            return $a * $b;
                        }
                        return $a - $b % 3;
                    }

                    public function flag(): bool { return true; }
                }
                """;

        List<TextMutator.Site> sites = mutator.sites(source);

        assertEquals(List.of("+", ">", "*", "-", "%", "true"),
                sites.stream().map(TextMutator.Site::operator).toList(), sites.toString());
        assertEquals(List.of(7, 7, 8, 10, 10, 13),
                sites.stream().map(TextMutator.Site::line).toList(), sites.toString());
    }

    @Test
    @DisplayName("=== and !== are their own pair, not == and !=")
    void strictEqualityIsItsOwnPair() {
        String source = """
                <?php

                function compare(int $a, int $b): bool
                {
                    return $a === $b || $a !== $b;
                }
                """;

        List<TextMutator.Site> sites = mutator.sites(source);

        assertEquals(List.of("===", "||", "!=="),
                sites.stream().map(TextMutator.Site::operator).toList(), sites.toString());
        assertEquals(List.of("!==", "&&", "==="),
                sites.stream().map(TextMutator.Site::replacement).toList(),
                "a strict comparison must stay strict: " + sites);
    }

    @Test
    @DisplayName(". is string concatenation and a site, while a float is one number")
    void concatenationIsAnOperatorAndNotANumberSeparator() {
        String source = """
                <?php

                $total = $a . 5;
                $price = 1.5;
                $label = 2 . 3;
                """;

        List<TextMutator.Site> sites = mutator.sites(source);

        assertEquals(List.of(".", "."), sites.stream().map(TextMutator.Site::operator).toList(),
                "the concatenation operator must survive as a token of its own: " + sites);
        assertEquals(List.of("+", "+"), sites.stream().map(TextMutator.Site::replacement).toList(),
                sites.toString());
        assertEquals(List.of(3, 5), sites.stream().map(TextMutator.Site::line).toList(), sites.toString());
    }

    @Test
    @DisplayName("a # comment is not code, and #[ is an attribute, which is")
    void hashCommentsAndAttributes() {
        String source = """
                <?php

                // $total = $a + $b;
                # $total = $a + $b;
                /* $total = $a * $b */
                $total = $a + $b;
                """;
        String attribute = """
                <?php

                final class Marked
                {
                    #[Attr] public function flag(): bool { return $a + $b > 0; }
                }
                """;

        assertEquals(List.of("+"), mutator.sites(source).stream().map(TextMutator.Site::operator).toList(),
                "only the statement outside the comments is code: " + mutator.sites(source));
        assertEquals(List.of("+", ">"),
                mutator.sites(attribute).stream().map(TextMutator.Site::operator).toList(),
                "a PHP 8 attribute is code, so the line it shares is scanned: " + mutator.sites(attribute));
    }

    @Test
    @DisplayName("single quotes, double quotes, heredoc, nowdoc and backticks are not code")
    void literalsAreNotSites() {
        String source = """
                <?php

                $single = 'a + b';
                $double = "c * d {$value}";
                $doc = <<<TXT
                e - f
                TXT;
                $now = <<<'TXT'
                g % h
                TXT;
                $shell = `i / j`;
                $total = $a . $b;
                """;

        List<TextMutator.Site> sites = mutator.sites(source);

        assertEquals(List.of("."), sites.stream().map(TextMutator.Site::operator).toList(),
                "only the statement after the literals is code: " + sites);
        assertEquals(12, sites.getFirst().line(), sites.toString());
    }

    @Test
    @DisplayName("unspaced and non-mutatable operators are not sites")
    void unspacedAndNonMutatableOperatorsAreNotSites() {
        String source = """
                <?php

                $total=$a+$b;
                $negative = -$amount;
                $fallback = $a ?? $b;
                $value = $object?->withdraw();
                $limit = Ledger::LIMIT;
                """;

        assertEquals(List.of(), mutator.sites(source).stream().map(TextMutator.Site::operator).toList(),
                "a mutant of an unspaced or non-mutatable operator is noise, not evidence");
    }

    @Test
    @DisplayName("literals are skipped by the same scanner the rule table reads")
    void literalSkippingFollowsTheSourceScanner() {
        assertEquals(3, PhpLexis.INSTANCE.skipLiteral("'x'", 0), "a single-quoted string ends at its quote");
        assertEquals(3, PhpLexis.INSTANCE.skipLiteral("\"a\"", 0), "a double-quoted string ends at its quote");
        assertEquals(-1, PhpLexis.INSTANCE.skipLiteral("$x", 0), "a variable is not a literal");
        assertEquals(-1, PhpLexis.INSTANCE.skipLiteral("#[Attr]", 0), "#[ opens an attribute, which is code");
        assertEquals(9, PhpLexis.INSTANCE.skipLiteral("# $a + $b\n", 0),
                "a # comment is skipped as one opaque token, up to but not including its newline");
        assertTrue(PhpLexis.INSTANCE.skipLiteral("<<<TXT\na + b\nTXT;\n", 0) > 0,
                "a heredoc is a literal that spans lines, so it must be skipped whole");
    }

    @Test
    @DisplayName("numbers are scanned as PHP writes them")
    void numbersAreScannedAsPhpWritesThem() {
        assertEquals(3, PhpLexis.INSTANCE.skipNumber("1.5;", 0), "a fractional number is one literal");
        assertEquals(4, PhpLexis.INSTANCE.skipNumber("0xFF;", 0), "a base prefix is part of the literal");
        assertEquals(1, PhpLexis.INSTANCE.skipNumber("5 . $b", 0),
                "the dot after a space is the concatenation operator, not part of the number");
        assertEquals(6, PhpLexis.INSTANCE.skipNumber("1.5e-3", 0),
                "a signed exponent is part of the number, so its minus is never a site");
    }
}
