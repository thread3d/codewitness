package dev.codewitness.sql;

import dev.codewitness.core.engine.CounterexampleProtocol;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.GeneratedTestExecution;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.support.database.SqlDatabase;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Executes the schema-derived constraint probes.
 *
 * <p>Each generated script runs against its own database, loaded from the project's schema, on a
 * connection with foreign keys enforced. A script that is rejected where it should not be, or accepted
 * where a constraint should have refused it, becomes a failing case for the test the generator named -
 * and the failure carries the encoded counterexample the engine reads, so the report shows the statement
 * and the constraint rather than a bare "generated test failed".
 *
 * <p>The suite a case is reported under is the generated test's class name, which is how
 * {@link dev.codewitness.core.engine.strategy.GeneratedTestsStrategy} pairs a result with the obligation
 * it was generated for. The generated sources and the databases they ran against are kept in the run's
 * artefact store, so a counterexample can be replayed by hand.
 */
public final class SqlGeneratedTestExecutor implements VerificationTools.GeneratedTestExecutor {

    @Override
    public String id() {
        return "sql-generated-tests";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.SQL);
    }

    @Override
    public GeneratedTestExecution execute(VerificationContext context, List<GeneratedTest> tests) {
        List<Path> schema = SqlProjectDetector.schemaFiles(context.projectRoot());
        Path directory = context.artifacts().artifactsDirectory().resolve("sql").resolve("generated");
        Instant start = Instant.now();
        List<TestCaseResult> cases = new ArrayList<>();
        List<Path> artifacts = new ArrayList<>();
        for (GeneratedTest test : tests) {
            String name = SqlTestProvider.safeName(test.className());
            Path script = directory.resolve(name + ".sql");
            writeScript(script, test.sourceCode());
            artifacts.add(script);

            Path database = directory.resolve(name + ".db");
            SqlDatabase.Load load = SqlDatabase.load(database, schema);
            if (!load.loaded()) {
                cases.add(SqlTestProvider.result(test.className(), test.className(), start,
                        TestOutcome.FAILED, "SCHEMA",
                        "the schema did not load, so no probe could run: " + load.failure().describe()));
                continue;
            }
            artifacts.add(database);
            try (Connection connection = SqlTestProvider.open(database)) {
                SqlScriptRunner.Outcome outcome = SqlScriptRunner.run(connection, test.sourceCode());
                cases.add(SqlTestProvider.result(test.className(), test.className(), start,
                        outcome.outcome(), outcome.failureType(),
                        outcome.outcome().isFailure()
                                ? encode(test, outcome.failureMessage())
                                : outcome.failureMessage()));
            } catch (SQLException e) {
                cases.add(SqlTestProvider.result(test.className(), test.className(), start,
                        TestOutcome.FAILED, "DATABASE", "the database could not be opened: " + e.getMessage()));
            }
        }
        context.emit("generated-tests", cases.size() + " constraint probe script(s) executed");
        TestResult result = TestResult.from(cases, Duration.between(start, Instant.now()), List.of(), null);
        return new GeneratedTestExecution(tests, result, artifacts, null);
    }

    @Override
    public List<String> reproductionCommand(
            VerificationContext context, List<GeneratedTest> tests, Long seed, Integer caseIndex) {
        if (tests.isEmpty()) {
            return List.of();
        }
        return SqlTool.resolve("sqlite3")
                .map(sqlite3 -> {
                    Path directory = context.artifacts().artifactsDirectory().resolve("sql").resolve("generated");
                    String name = SqlTestProvider.safeName(tests.getFirst().className());
                    return List.of(sqlite3,
                            directory.resolve(name + ".db").toString(),
                            ".read " + directory.resolve(name + ".sql"));
                })
                .orElse(List.of());
    }

    /**
     * Encodes the observation as the protocol line the engine reads, so the counterexample names the
     * generated test and carries the statement rather than only a message.
     */
    private static String encode(GeneratedTest test, String message) {
        return CounterexampleProtocol.encodeCounterexample(
                null,
                null,
                Map.of("generated_test", test.className()),
                "every constraint declared on the schema rejects the row that violates it",
                message == null ? "the probe did not hold" : message,
                null);
    }

    private static void writeScript(Path script, String source) {
        try {
            Files.createDirectories(script.getParent());
            Files.writeString(script, source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the generated SQL script " + script, e);
        }
    }
}
