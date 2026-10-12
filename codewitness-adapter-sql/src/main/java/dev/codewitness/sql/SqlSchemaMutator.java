package dev.codewitness.sql;

import dev.codewitness.sql.analysis.SqlSourceScan;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mutation sites in a SQL schema, over a bounded and documented operator set.
 *
 * <p>A SQL schema's testable claims are its constraints, so the mutators act on the {@code CHECK}
 * clauses and nothing else:
 *
 * <ul>
 *   <li><b>boundary</b> - {@code <=} becomes {@code <} and {@code >=} becomes {@code >}, and the reverse.
 *       This is the classic off-by-one in a data rule: a value exactly at the bound starts being
 *       refused (or starts being accepted) and only a test that exercises the bound itself can tell;</li>
 *   <li><b>removal</b> - the whole {@code CHECK (expr)} becomes {@code CHECK (1)}, which accepts every
 *       row. This is the constraint that was quietly dropped or weakened, and any test that relies on
 *       the rule refusing a value kills it.</li>
 * </ul>
 *
 * <p>{@code NOT NULL}, {@code UNIQUE} and key mutators are deliberately not part of the set yet: they are
 * a different kind of claim, and a small operator set whose every member is understood is worth more
 * than a large one whose survivors are ambiguous. The set is stated here so a reader can judge a score
 * against exactly what was mutated.
 *
 * <p>Sites are located through a literal-aware view of the schema, so a {@code CHECK} inside a comment or
 * a string is not a site, and the offsets slice the original text so the mutant reads as it was written.
 */
final class SqlSchemaMutator {

    /** One place a mutation can be applied. */
    record Site(String mutator, String description, int line, int start, int end, String replacement) {
    }

    private static final Pattern CHECK_CLAUSE = Pattern.compile("(?is)\\bcheck\\s*\\(");
    /**
     * Comparison operators, longest first so that {@code <>} and {@code !=} are matched as one token:
     * with {@code <} earlier in the alternation, {@code <>} would be split into a {@code <} and a
     * {@code >} and mutate into {@code kind < > 'x'}, which is not a boundary at all.
     */
    private static final Pattern COMPARISON = Pattern.compile("<=|>=|<>|!=|<|>");

    private SqlSchemaMutator() {
    }

    /** @return every mutation site in the schema, in the order the constraints appear. */
    static List<Site> sites(String schema) {
        List<Site> sites = new ArrayList<>();
        if (schema == null || schema.isBlank()) {
            return sites;
        }
        SqlSourceScan scan = SqlSourceScan.of(schema);
        Matcher clause = CHECK_CLAUSE.matcher(scan.masked());
        while (clause.find()) {
            int open = clause.end() - 1;
            int close = matchingParen(scan.masked(), open);
            if (close < 0) {
                continue;
            }
            String expression = schema.substring(open + 1, close).trim();
            int line = scan.lineAt(clause.start());
            Matcher operator = COMPARISON.matcher(scan.masked().substring(open + 1, close));
            while (operator.find()) {
                String boundary = boundaryOf(operator.group());
                if (boundary == null) {
                    continue;
                }
                sites.add(new Site("SqlConditionalBoundaryMutator",
                        "CHECK (" + expression + ") with " + operator.group() + " replaced by " + boundary,
                        line, open + 1 + operator.start(), open + 1 + operator.end(), boundary));
            }
            sites.add(new Site("SqlCheckRemovalMutator",
                    "CHECK (" + expression + ") replaced by CHECK (1), which accepts every row",
                    line, clause.start(), close + 1, "CHECK (1)"));
        }
        return sites;
    }

    /** @return the operator one step across the boundary, or {@code null} for an operator not mutated. */
    private static String boundaryOf(String operator) {
        return switch (operator) {
            case "<=" -> "<";
            case "<" -> "<=";
            case ">=" -> ">";
            case ">" -> ">=";
            default -> null;
        };
    }

    /** @return the index of the parenthesis matching the one at {@code open}, or {@code -1}. */
    private static int matchingParen(String masked, int open) {
        int depth = 0;
        for (int index = open; index < masked.length(); index++) {
            char c = masked.charAt(index);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }
}
