package dev.codewitness.sql;

import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.support.database.SqlDatabase;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Executes one SQL script against an open database and says what it observed.
 *
 * <p>Both the project's own test scripts and the schema-derived probes are scripts with the same
 * conventions, so they share one interpreter: a statement marked {@code -- expect: error} must be
 * rejected, a final {@code SELECT} must return no rows, and a script that asserts neither observed
 * nothing worth reporting. Sharing it is what keeps a generated probe and a hand-written test from
 * drifting apart in what they mean.
 */
final class SqlScriptRunner {

    /**
     * @param outcome       what the script's execution amounted to
     * @param failureType   a short category for a failure, such as {@code ASSERTION}, or {@code null}
     * @param failureMessage what was observed, or why nothing was asserted
     */
    record Outcome(TestOutcome outcome, String failureType, String failureMessage) {

        static Outcome passed() {
            return new Outcome(TestOutcome.PASSED, null, null);
        }

        static Outcome failed(String type, String message) {
            return new Outcome(TestOutcome.FAILED, type, message);
        }

        static Outcome skipped(String message) {
            return new Outcome(TestOutcome.SKIPPED, null, message);
        }
    }

    private SqlScriptRunner() {
    }

    /** @return the outcome of running the script on the connection. */
    static Outcome run(Connection connection, String script) {
        List<SqlTestScript.Statement> statements = SqlTestScript.parse(script);
        if (statements.isEmpty()) {
            return Outcome.skipped("the script holds no statement");
        }
        boolean asserted = false;
        for (int index = 0; index < statements.size(); index++) {
            SqlTestScript.Statement statement = statements.get(index);
            boolean last = index == statements.size() - 1;
            if (statement.expectError()) {
                asserted = true;
                if (!isRejected(connection, statement.sql())) {
                    return Outcome.failed("EXPECTED_REJECTION",
                            "the statement was expected to be rejected and it succeeded: "
                                    + SqlDatabase.summarize(statement.sql()));
                }
                continue;
            }
            if (last && SqlTestScript.isAssertionQuery(statement.sql())) {
                asserted = true;
                List<String> rows = rows(connection, statement.sql());
                if (!rows.isEmpty()) {
                    return Outcome.failed("ASSERTION",
                            "the assertion query returned " + rows.size()
                                    + " row(s), so the condition it names holds somewhere: "
                                    + String.join("; ", rows.stream().limit(5).toList()));
                }
                continue;
            }
            try (Statement jdbc = connection.createStatement()) {
                jdbc.execute(statement.sql());
            } catch (SQLException e) {
                return Outcome.failed("SQL", "statement " + (index + 1) + " failed: " + e.getMessage()
                        + " (" + SqlDatabase.summarize(statement.sql()) + ")");
            }
        }
        if (!asserted) {
            return Outcome.skipped(
                    "the script declared no assertion: no final SELECT and no `-- expect: error` directive");
        }
        return Outcome.passed();
    }

    /** @return {@code true} when the database rejects the statement. */
    private static boolean isRejected(Connection connection, String sql) {
        try (Statement jdbc = connection.createStatement()) {
            jdbc.execute(sql);
            return false;
        } catch (SQLException _) {
            return true;
        }
    }

    /** @return a readable preview of the rows a query returned, at most five of them. */
    private static List<String> rows(Connection connection, String sql) {
        List<String> preview = new ArrayList<>();
        try (Statement jdbc = connection.createStatement();
                ResultSet result = jdbc.executeQuery(sql)) {
            ResultSetMetaData meta = result.getMetaData();
            while (result.next() && preview.size() < 5) {
                StringBuilder row = new StringBuilder("{");
                for (int column = 1; column <= meta.getColumnCount(); column++) {
                    if (column > 1) {
                        row.append(", ");
                    }
                    row.append(meta.getColumnLabel(column)).append('=').append(result.getString(column));
                }
                preview.add(row.append('}').toString());
            }
        } catch (SQLException e) {
            preview.add("the assertion query could not be run: " + e.getMessage());
        }
        return preview;
    }
}
