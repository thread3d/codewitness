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
 * {@link SqlTestScript}'s, and {@link SqlScriptRunner} is the interpreter both this runner and the
 * schema-derived probe executor use: a statement marked {@code -- expect: error} must be rejected, a
 * final {@code SELECT} must return no rows, and a script that does neither is recorded as skipped rather
 * than passed, because running without error is not evidence that anything held.
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
            return result(suite, name, start, TestOutcome.FAILED, "SCHEMA",
                    "the schema did not load, so the script could not run: " + load.failure().describe());
        }
        try (Connection connection = open(database)) {
            SqlScriptRunner.Outcome outcome =
                    SqlScriptRunner.run(connection, SqlProjectDetector.readString(test));
            return result(suite, name, start, outcome.outcome(), outcome.failureType(), outcome.failureMessage());
        } catch (SQLException e) {
            return result(suite, name, start, TestOutcome.FAILED, "DATABASE",
                    "the database could not be opened: " + e.getMessage());
        }
    }

    /**
     * Opens a connection with foreign keys enforced.
     *
     * <p>SQLite does not enforce foreign keys unless the connection asks it to, which silently makes an
     * orphan insert succeed and a declared foreign key unverifiable. Every connection this adapter runs a
     * script on turns the pragma on, so a constraint the schema declares is a constraint the run tests.
     */
    static Connection open(Path database) throws SQLException {
        Connection connection = DriverManager.getConnection(SqlDatabase.url(database));
        try (Statement jdbc = connection.createStatement()) {
            jdbc.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
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

    static TestCaseResult result(
            String suite, String name, Instant start, TestOutcome outcome, String type, String message) {
        return new TestCaseResult(suite, name, outcome, Duration.between(start, Instant.now()),
                type, message, null);
    }

    static String safeName(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }
}
