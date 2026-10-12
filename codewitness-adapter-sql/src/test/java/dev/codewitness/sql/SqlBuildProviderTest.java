package dev.codewitness.sql;

import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.DiagnosticSeverity;
import dev.codewitness.core.spi.VerificationContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The schema loader, which is SQL's first failure: applying the declared scripts to a fresh database.
 */
class SqlBuildProviderTest {

    @TempDir
    Path root;

    private VerificationContext context() {
        return SqlTestContexts.context(root, root.resolve("run"));
    }

    private void write(String relative, String content) throws IOException {
        SqlTestContexts.write(root, relative, content);
    }

    @Test
    @DisplayName("a schema that applies is a successful build, and the database is kept as an artefact")
    void loadsAValidSchema() throws IOException {
        write("db/migrations/V1__create.sql", "CREATE TABLE ledger (id INTEGER PRIMARY KEY, amount NUMERIC);\n");
        write("db/migrations/V2__seed.sql", "INSERT INTO ledger (id, amount) VALUES (1, 10);\n");
        VerificationContext context = context();

        BuildResult result = new SqlBuildProvider().build(context);

        assertTrue(result.attempted());
        assertTrue(result.success(), result.output());
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        assertFalse(result.artifacts().isEmpty(), "the loaded database should be kept with the run");
        assertTrue(Files.isRegularFile(result.artifacts().getFirst()));
    }

    @Test
    @DisplayName("a schema a migration cannot apply is a failed build with a diagnostic naming the file")
    void failsOnABrokenSchema() throws IOException {
        write("db/migrations/V1__create.sql", "CREATE TABLE ledger (id INTEGER PRIMARY KEY);\n");
        write("db/migrations/V2__duplicate.sql", "CREATE TABLE ledger (id INTEGER PRIMARY KEY);\n");

        BuildResult result = new SqlBuildProvider().build(context());

        assertTrue(result.attempted());
        assertFalse(result.success(), "a schema that does not apply must not be reported as loaded");
        assertEquals(1, result.diagnostics().size(), result.diagnostics().toString());
        assertEquals(DiagnosticSeverity.ERROR, result.diagnostics().getFirst().severity());
        assertEquals("db/migrations/V2__duplicate.sql", result.diagnostics().getFirst().file());
        assertTrue(result.diagnostics().getFirst().message().contains("V2__duplicate.sql"),
                result.diagnostics().getFirst().message());
    }

    @Test
    @DisplayName("a tree with no schema script is not attempted, and says why")
    void noSchemaIsNotAttempted() throws IOException {
        write("tests/ledger_test.sql", "SELECT 1;\n");

        BuildResult result = new SqlBuildProvider().build(context());

        assertFalse(result.attempted());
        assertTrue(result.failureReason().contains("no schema"), result.failureReason());
    }

    @Test
    @DisplayName("the schema under test is the project's, not a database the run happens to have")
    void loadsTheProjectSchema() throws IOException {
        // A migration that alters a table created by an earlier one: applying them out of order fails, so
        // this asserts the loader's order and its choice of scripts at the same time.
        write("db/migrations/V1__create.sql", "CREATE TABLE ledger (id INTEGER PRIMARY KEY);\n");
        write("db/migrations/V2__alter.sql", "ALTER TABLE ledger ADD COLUMN amount NUMERIC NOT NULL DEFAULT 0;\n");

        assertTrue(new SqlBuildProvider().build(context()).success());
    }
}
