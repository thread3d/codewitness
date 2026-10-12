package dev.codewitness.sql;

import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.support.database.SqlDatabase;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Executes the project's SQL test scripts against a fresh database loaded with its schema.
 *
 * <p>Each script gets its own database, so one test's rows cannot decide another's result - a shared
 * database would make the suite's outcome depend on the order it ran in. The script's conventions are
 * {@link SqlTestScript}'s: a statement marked {@code -- expect: error} must be rejected, a final
 * {@code SELECT} must return no rows, and a script that does neither is recorded as skipped rather than
 * passed, because running without error is not evidence that anything held.
 *
 * <p>A failing assertion becomes an ordinary failing test case, so it flows through the same evidence
 * chain as any other language's failure: mapped to the requirement whose id its file mentions, turned
 * into a counterexample, and replayed with {@code sqlite3} when that tool is installed.
 */
public final class SqlTestProvider implements VerificationTools.TestRunner {

    @Override
    public String id() {
        return "sql-tests";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.SQL);
    }

    @Override
    public TestResult runTests(VerificationContext context) {
        List<Path> tests = SqlProjectDetector.testFiles(context.projectRoot());
        if (tests.isEmpty()) {
            return TestResult.notExecuted("no SQL test script was found: a script under a test/ or tests/ "
                    + "directory, or one named like *_test.sql, is how a SQL requirement is executed");
        }
        List<Path> schema = SqlProjectDetector.schemaFiles(context.projectRoot());
        Path testRoot = testRoot(context, tests);
        Instant start = Instant.now();
        List<TestCaseResult> cases = new ArrayList<>();
        for (Path test : tests) {
            cases.add(runOne(context, test, schema, testRoot));
        }
        context.emit("tests", cases.size() + " SQL test script(s) executed");
        return TestResult.from(cases, Duration.between(start, Instant.now()), List.of(), null);
    }

    private TestCaseResult runOne(
            VerificationContext context, Path test, List<Path> schema, Path testRoot) {
        String suite = SqlProjectDetector.suiteName(testRoot, test);
        String name = test.getFileName().toString();
        Instant start = Instant.now();
        Path database = SqlBuildProvider.databaseFile(context, "tests/" + safeName(suite) + ".db");
        SqlDatabase.Load load = SqlDatabase.load(database, schema);
        if (!load.loaded()) {
            return failure(suite, name, start, "SCHEMA",
                    "the schema did not load, so the script could not run: " + load.failure().describe());
        }
        List<SqlTestScript.Statement> statements = SqlTestScript.parse(SqlProjectDetector.readString(test));
        if (statements.isEmpty()) {
            return skipped(suite, name, start, "the script holds no statement");
        }
        boolean asserted = false;
        try (Connection connection = DriverManager.getConnection(SqlDatabase.url(database))) {
            for (int index = 0; index < statements.size(); index++) {
                SqlTestScript.Statement statement = statements.get(index);
                boolean last = index == statements.size() - 1;
                if (statement.expectError()) {
                    asserted = true;
                    if (!isRejected(connection, statement.sql())) {
                        return failure(suite, name, start, "EXPECTED_REJECTION",
                                "the statement was expected to be rejected and it succeeded: "
                                        + SqlDatabase.summarize(statement.sql()));
                    }
                    continue;
                }
                if (last && SqlTestScript.isAssertionQuery(statement.sql())) {
                    asserted = true;
                    List<String> rows = rows(connection, statement.sql());
                    if (!rows.isEmpty()) {
                        return failure(suite, name, start, "ASSERTION",
                                "the assertion query returned " + rows.size()
                                        + " row(s), so the condition it names holds somewhere: "
                                        + String.join("; ", rows.stream().limit(5).toList()));
                    }
                    continue;
                }
                try (Statement jdbc = connection.createStatement()) {
                    jdbc.execute(statement.sql());
                } catch (SQLException e) {
                    return failure(suite, name, start, "SQL", "statement " + (index + 1) + " failed: "
                            + e.getMessage() + " (" + SqlDatabase.summarize(statement.sql()) + ")");
                }
            }
        } catch (SQLException e) {
            return failure(suite, name, start, "DATABASE",
                    "the database could not be opened: " + e.getMessage());
        }
        if (!asserted) {
            return skipped(suite, name, start,
                    "the script declared no assertion: no final SELECT and no `-- expect: error` directive");
        }
        return TestCaseResult.passed(suite, name, Duration.between(start, Instant.now()));
    }

    @Override
    public List<String> replayCommand(VerificationContext context, TestCaseResult testCase) {
        // SQL runs in process, so the reproduction is the script against the database it ran on, which
        // sqlite3 can do. The command is offered only when sqlite3 is actually installed: naming a tool
        // the machine does not have would make the recipe look runnable when it is not.
        return SqlTool.resolve("sqlite3")
                .map(sqlite3 -> List.of(sqlite3,
                        SqlBuildProvider.databaseFile(context, "tests/" + safeName(testCase.suite()) + ".db")
                                .toString(),
                        ".read " + testFile(context, testCase.suite())))
                .orElse(List.of());
    }

    /**
     * @return the directory the test scripts are named relative to, which is the one the core's mapper
     *     relativises against, so a suite name here matches the identifier it derives for the same file
     */
    static Path testRoot(VerificationContext context, List<Path> tests) {
        for (Path root : context.projectInfo().testSourceDirs()) {
            if (tests.stream().anyMatch(test -> test.startsWith(root))) {
                return root;
            }
        }
        Path parent = tests.getFirst().getParent();
        return parent == null ? context.projectRoot() : parent;
    }

    private static Path testFile(VerificationContext context, String suite) {
        List<Path> tests = SqlProjectDetector.testFiles(context.projectRoot());
        Path testRoot = testRoot(context, tests);
        Path candidate = testRoot.resolve(suite.replace('.', '/') + ".sql");
        if (tests.contains(candidate)) {
            return candidate;
        }
        return tests.stream()
                .filter(test -> SqlProjectDetector.suiteName(testRoot, test).equals(suite))
                .findFirst()
                .orElse(candidate);
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

    private static TestCaseResult failure(
            String suite, String name, Instant start, String type, String message) {
        return new TestCaseResult(suite, name, TestOutcome.FAILED, Duration.between(start, Instant.now()),
                type, message, null);
    }

    private static TestCaseResult skipped(String suite, String name, Instant start, String message) {
        return new TestCaseResult(suite, name, TestOutcome.SKIPPED, Duration.between(start, Instant.now()),
                null, message, null);
    }

    static String safeName(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }
}
