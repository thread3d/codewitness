package dev.codewitness.sql;

import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.SurvivingMutant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mutation testing judged by the project's own SQL scripts.
 *
 * <p>The property that matters is the one the whole adapter is about: a score is only meaningful when the
 * scripts actually constrain the schema, so a suite that exercises the boundary must kill the mutants and
 * a suite that ignores it must leave them surviving rather than reporting a score it did not earn.
 */
class SqlMutationTesterTest {

    @TempDir
    Path root;

    private void write(String relative, String content) throws IOException {
        SqlTestContexts.write(root, relative, content);
    }

    private MutationReport analyse() {
        return new SqlMutationTester().analyse(SqlTestContexts.context(root, root.resolve("run")));
    }

    @Test
    @DisplayName("a suite that exercises the boundary kills the boundary and removal mutants")
    void aStrongSuiteKillsEveryMutant() throws IOException {
        write("db/schema.sql", "CREATE TABLE ledger (\n  id INTEGER PRIMARY KEY,\n"
                + "  amount NUMERIC NOT NULL CHECK (amount <= 1000)\n);\n");
        write("tests/amount_test.sql", """
                INSERT INTO ledger (id, amount) VALUES (1, 1000);

                -- expect: error
                INSERT INTO ledger (id, amount) VALUES (2, 1001);
                """);

        MutationReport report = analyse();

        assertTrue(report.measured(), report.note());
        assertEquals(2, report.generated(), report.describe());
        assertEquals(2, report.killed(), report.describe());
        assertEquals(0, report.survived(), report.describe());
        assertEquals(100.0, report.scorePercent(), 0.001);
        assertTrue(report.survivors().isEmpty());
    }

    @Test
    @DisplayName("a suite that ignores the bound leaves the mutants surviving")
    void aWeakSuiteLeavesSurvivors() throws IOException {
        write("db/schema.sql", "CREATE TABLE ledger (\n  id INTEGER PRIMARY KEY,\n"
                + "  amount NUMERIC NOT NULL CHECK (amount <= 1000)\n);\n");
        write("tests/amount_test.sql", """
                INSERT INTO ledger (id, amount) VALUES (1, 500);

                SELECT id FROM ledger WHERE amount > 1000;
                """);

        MutationReport report = analyse();

        assertTrue(report.measured(), report.note());
        assertEquals(2, report.survived(), report.describe());
        assertEquals(0, report.killed(), report.describe());
        assertEquals(0.0, report.scorePercent(), 0.001);
        List<String> mutators = report.survivors().stream().map(SurvivingMutant::mutator).toList();
        assertTrue(mutators.contains("SqlConditionalBoundaryMutator"), mutators.toString());
        assertTrue(mutators.contains("SqlCheckRemovalMutator"), mutators.toString());
    }

    @Test
    @DisplayName("a mutant's schema and database are kept as artifacts, so a survivor can be inspected")
    void keepsMutantArtifacts() throws IOException {
        write("db/schema.sql", "CREATE TABLE ledger (id INTEGER PRIMARY KEY, amount NUMERIC CHECK (amount <= 1000));\n");
        write("tests/amount_test.sql", "SELECT id FROM ledger WHERE amount > 1000;\n");

        MutationReport report = analyse();

        assertFalse(report.reports().isEmpty(), "a mutated schema must be kept for a reader");
        assertTrue(report.reports().stream().anyMatch(path -> path.toString().endsWith(".sql")));
    }

    @Test
    @DisplayName("mutation is not measured without a test script to kill anything")
    void noTestsMeansNotMeasured() throws IOException {
        write("db/schema.sql", "CREATE TABLE ledger (id INTEGER PRIMARY KEY, amount NUMERIC CHECK (amount <= 1000));\n");

        MutationReport report = analyse();

        assertFalse(report.measured());
        assertTrue(report.note().contains("no SQL test script"), report.note());
    }

    @Test
    @DisplayName("a schema with no CHECK constraint has nothing to mutate")
    void noCheckMeansNotMeasured() throws IOException {
        write("db/schema.sql", "CREATE TABLE ledger (id INTEGER PRIMARY KEY, amount NUMERIC NOT NULL);\n");
        write("tests/amount_test.sql", "SELECT id FROM ledger WHERE amount > 1000;\n");

        MutationReport report = analyse();

        assertFalse(report.measured());
        assertTrue(report.note().contains("no mutatable CHECK"), report.note());
    }
}
