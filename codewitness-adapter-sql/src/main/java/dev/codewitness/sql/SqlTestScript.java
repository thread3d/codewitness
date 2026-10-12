package dev.codewitness.sql;

import dev.codewitness.support.database.SqlScript;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A SQL test script: statements, and the few directives that say what the script asserts.
 *
 * <p>A SQL test is a script run against a fresh database loaded with the project's schema. It asserts in
 * two ways, and both are visible in the file rather than inferred:
 *
 * <ul>
 *   <li>a statement preceded by {@code -- expect: error} must be rejected by the database. This is what
 *       expresses a constraint, a rejection rule or a privilege: a row that must not be accepted, an
 *       operation that must fail;</li>
 *   <li>a final {@code SELECT} must return no rows. This is the invariant check, and it is the SQL
 *       analogue of a property test: the query names the condition that must never hold, and any row it
 *       returns is the counterexample.</li>
 * </ul>
 *
 * <p>A script with neither asserts nothing, and the runner records it as skipped rather than passed:
 * "it ran without error" is not evidence that anything held.
 */
public final class SqlTestScript {

    private SqlTestScript() {
    }

    /**
     * @param sql         one statement
     * @param expectError whether the database must reject it
     */
    public record Statement(String sql, boolean expectError) {
    }

    /**
     * @param content the text of a SQL test script
     * @return the statements in execution order, each carrying whether it must fail
     */
    public static List<Statement> parse(String content) {
        List<Statement> statements = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return statements;
        }
        StringBuilder group = new StringBuilder();
        boolean groupExpectsError = false;
        for (String line : content.split("\n", -1)) {
            if (isErrorDirective(line)) {
                flush(statements, group, groupExpectsError);
                groupExpectsError = true;
                continue;
            }
            group.append(line).append('\n');
        }
        flush(statements, group, groupExpectsError);
        return statements;
    }

    /**
     * @return {@code true} when the statement is an assertion query, which must return no rows.
     *
     * <p>{@code WITH} is included because a common table expression is how a readable assertion is
     * usually written, and it is still a query whose result the runner can count.
     */
    public static boolean isAssertionQuery(String sql) {
        String normalized = sql.stripLeading().toUpperCase(Locale.ROOT);
        return normalized.startsWith("SELECT") || normalized.startsWith("WITH") || normalized.startsWith("VALUES");
    }

    /** @return {@code true} when the line is the {@code -- expect: error} directive. */
    public static boolean isErrorDirective(String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.trim();
        if (!trimmed.startsWith("--")) {
            return false;
        }
        String directive = trimmed.substring(2).trim().toLowerCase(Locale.ROOT).replace(" ", "");
        return directive.equals("expect:error") || directive.equals("expect-error");
    }

    private static void flush(List<Statement> statements, StringBuilder group, boolean expectError) {
        List<String> split = SqlScript.statements(group.toString());
        for (int index = 0; index < split.size(); index++) {
            // The directive introduces the statement that follows it, so only the first statement of a
            // group carries it; a group with several statements is unusual, and attributing the directive
            // to all of them would silently change what the script asserts.
            statements.add(new Statement(split.get(index), expectError && index == 0));
        }
        group.setLength(0);
    }
}
