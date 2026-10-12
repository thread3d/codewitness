package dev.codewitness.sql;

import dev.codewitness.core.engine.CounterexampleProtocol;
import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.requirements.ObligationKind;
import dev.codewitness.core.requirements.ObligationOrigin;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.GeneratedTestExecution;
import dev.codewitness.core.spi.VerificationContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema-derived probes, from the constraint to an executed experiment.
 *
 * <p>This is the chain that makes a SQL requirement verifiable rather than only refutable: the schema
 * declares a constraint, the generator turns it into a script, and the executor runs that script against
 * a database loaded from the same schema. A constraint that fires discharges the obligation; one that
 * does not becomes a counterexample.
 */
class SqlGeneratedTestsTest {

    @TempDir
    Path root;

    private static final String SCHEMA_WITH_CONSTRAINT = """
            CREATE TABLE ledger (
              id INTEGER PRIMARY KEY,
              amount NUMERIC NOT NULL CHECK (amount >= 0)
            );
            """;

    private static final Requirement REQUIREMENT =
            Requirement.builder("R-002", "A negative amount is rejected.").title("Rejection").build();

    private static VerificationObligation obligation(ObligationKind kind) {
        return new VerificationObligation("OB-1", "R-002", "the rejection must hold", kind,
                ObligationOrigin.USER_DECLARED, null, Map.of(), false);
    }

    private void write(String relative, String content) throws IOException {
        SqlTestContexts.write(root, relative, content);
    }

    private VerificationContext context() {
        return SqlTestContexts.context(root, root.resolve("run"));
    }

    @Test
    @DisplayName("a rejection obligation becomes one script per constraint, each asserting a rejection")
    void generatesProbesForRejection() throws IOException {
        write("db/schema.sql", SCHEMA_WITH_CONSTRAINT);

        List<GeneratedTest> tests = new SqlTestGenerator()
                .generate(context(), REQUIREMENT, List.of(obligation(ObligationKind.REJECTION)));

        assertFalse(tests.isEmpty());
        GeneratedTest test = tests.getFirst();
        assertTrue(test.checksInvariant(), "a probe asserts, so its passing must be allowed to verify");
        assertEquals(List.of("OB-1"), test.obligationIds());
        assertEquals(List.of("R-002"), test.requirementIds());
        assertTrue(test.sourceCode().contains("-- expect: error"), test.sourceCode());
    }

    @Test
    @DisplayName("an obligation no constraint bears on produces no script")
    void noProbesForUnrelatedObligation() throws IOException {
        write("db/schema.sql", SCHEMA_WITH_CONSTRAINT);

        List<GeneratedTest> tests = new SqlTestGenerator()
                .generate(context(), REQUIREMENT, List.of(obligation(ObligationKind.CONCURRENCY)));

        assertTrue(tests.isEmpty(),
                "a single-connection constraint probe is not evidence about concurrency: " + tests);
    }

    @Test
    @DisplayName("a generated probe passes when the schema's constraint actually fires")
    void probePassesWhenConstraintFires() throws IOException {
        write("db/schema.sql", SCHEMA_WITH_CONSTRAINT);
        VerificationContext context = context();
        List<GeneratedTest> tests = new SqlTestGenerator()
                .generate(context, REQUIREMENT, List.of(obligation(ObligationKind.REJECTION)));

        GeneratedTestExecution execution = new SqlGeneratedTestExecutor().execute(context, tests);

        assertTrue(execution.result().executed());
        assertFalse(execution.result().anyFailure(), execution.result().failures().toString());
        assertTrue(execution.result().cases().stream()
                        .allMatch(testCase -> testCase.outcome() == TestOutcome.PASSED),
                execution.result().cases().toString());
    }

    @Test
    @DisplayName("a probe fails, with a counterexample, when the constraint does not fire")
    void probeFailsWhenConstraintIsMissing() throws IOException {
        // The schema declares the column but not the check, so the probe's violating insert is accepted -
        // which is exactly the defect the probe exists to find. The script is the one the generator would
        // write for a declared check, run against a schema that does not declare it.
        write("db/schema.sql", "CREATE TABLE ledger (id INTEGER PRIMARY KEY, amount NUMERIC NOT NULL);\n");
        VerificationContext context = context();
        GeneratedTest probe = new GeneratedTest("cw_r_002_1",
                "-- check (amount >= 0)\n-- expect: error\n"
                        + "INSERT INTO ledger (id, amount) VALUES (1, -1);\n",
                null, List.of("R-002"), List.of("OB-1"), "a probe", null, null, true);

        GeneratedTestExecution execution = new SqlGeneratedTestExecutor().execute(context, List.of(probe));

        assertEquals(TestOutcome.FAILED, execution.result().cases().getFirst().outcome());
        String message = execution.result().cases().getFirst().failureMessage();
        assertTrue(message.startsWith(CounterexampleProtocol.MARKER),
                "a generated failure must carry the counterexample the engine reads: " + message);
        assertTrue(CounterexampleProtocol.decode(message).isPresent(), message);
    }

    @Test
    @DisplayName("a case is reported under the generated test's class name, which is how it pairs to an obligation")
    void caseSuiteMatchesClassName() throws IOException {
        write("db/schema.sql", SCHEMA_WITH_CONSTRAINT);
        VerificationContext context = context();
        GeneratedTest test = new GeneratedTest("cw_r_002_1",
                "-- expect: error\nINSERT INTO ledger (id, amount) VALUES (1, -5);\n",
                null, List.of("R-002"), List.of("OB-1"), "a probe", null, null, true);

        GeneratedTestExecution execution = new SqlGeneratedTestExecutor().execute(context, List.of(test));

        assertEquals("cw_r_002_1", execution.result().cases().getFirst().suite());
        assertEquals(TestOutcome.PASSED, execution.result().cases().getFirst().outcome());
    }

    @Test
    @DisplayName("the generated script and the database it ran on are kept as artifacts")
    void keepsScriptAndDatabase() throws IOException {
        write("db/schema.sql", SCHEMA_WITH_CONSTRAINT);
        VerificationContext context = context();
        List<GeneratedTest> tests = new SqlTestGenerator()
                .generate(context, REQUIREMENT, List.of(obligation(ObligationKind.REJECTION)));

        GeneratedTestExecution execution = new SqlGeneratedTestExecutor().execute(context, tests);

        assertTrue(execution.artifacts().stream().anyMatch(path -> path.toString().endsWith(".sql")));
        assertTrue(execution.artifacts().stream().anyMatch(path -> path.toString().endsWith(".db")));
    }

    @Test
    @DisplayName("a rejection requirement naming one column is not discharged by a check on another")
    void ignoresChecksOnOtherColumns() throws IOException {
        // R-002 is about the amount. A check on the transaction's kind is a real constraint, but it says
        // nothing about whether a negative amount is rejected, so crediting it would verify the wrong
        // claim. No probe is generated, and the obligation stays UNVERIFIED.
        write("db/schema.sql", """
                CREATE TABLE ledger (
                  id INTEGER PRIMARY KEY,
                  amount NUMERIC NOT NULL,
                  kind TEXT NOT NULL CHECK (kind IN ('debit', 'credit'))
                );
                """);

        List<GeneratedTest> tests = new SqlTestGenerator()
                .generate(context(), REQUIREMENT, List.of(obligation(ObligationKind.REJECTION)));

        assertTrue(tests.isEmpty(), "a check on another column is not evidence for this requirement: " + tests);
    }

    @Test
    @DisplayName("a rejection requirement is not discharged by a primary key alone")
    void structuralConstraintsDoNotDischargeRejection() throws IOException {
        // With no CHECK there is no rule that refuses a value, so a key or a NOT NULL says nothing about
        // the rejection the requirement claims.
        write("db/schema.sql", "CREATE TABLE ledger (id INTEGER PRIMARY KEY, amount NUMERIC NOT NULL);\n");

        List<GeneratedTest> tests = new SqlTestGenerator()
                .generate(context(), REQUIREMENT, List.of(obligation(ObligationKind.REJECTION)));

        assertTrue(tests.isEmpty(), tests.toString());
    }

    @Test
    @DisplayName("a NULL-handling requirement is tested by the NOT NULL constraints")
    void nullHandlingUsesNotNullProbes() throws IOException {
        write("db/schema.sql", "CREATE TABLE ledger (id INTEGER PRIMARY KEY, amount NUMERIC NOT NULL);\n");

        List<GeneratedTest> tests = new SqlTestGenerator()
                .generate(context(), REQUIREMENT, List.of(obligation(ObligationKind.NULL_HANDLING)));

        assertFalse(tests.isEmpty());
        assertTrue(tests.getFirst().sourceCode().contains("NULL"), tests.getFirst().sourceCode());
    }

    @Test
    @DisplayName("a tree with no CREATE TABLE yields no probes and says so rather than guessing")
    void noSchemaYieldsNoProbes() throws IOException {
        write("db/query.sql", "SELECT 1;\n");

        List<GeneratedTest> tests = new SqlTestGenerator()
                .generate(context(), REQUIREMENT, List.of(obligation(ObligationKind.REJECTION)));

        assertTrue(tests.isEmpty());
    }
}
