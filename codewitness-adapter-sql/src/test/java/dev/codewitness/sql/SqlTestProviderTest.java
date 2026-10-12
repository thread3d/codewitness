package dev.codewitness.sql;

import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.VerificationContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SQL test runner: assertion queries, expected rejections, and the scripts that assert nothing.
 */
class SqlTestProviderTest {

    @TempDir
    Path root;

    private static final String LEDGER = """
            CREATE TABLE ledger (id INTEGER PRIMARY KEY, amount NUMERIC NOT NULL);
            """;

    private static final String LEDGER_WITH_CONSTRAINT = """
            CREATE TABLE ledger (id INTEGER PRIMARY KEY, amount NUMERIC NOT NULL CHECK (amount >= 0));
            """;

    private VerificationContext context() {
        return SqlTestContexts.context(root, root.resolve("run"));
    }

    private void write(String relative, String content) throws IOException {
        SqlTestContexts.write(root, relative, content);
    }

    private TestResult runWith(String schema, String test) throws IOException {
        write("db/schema.sql", schema);
        write("tests/ledger_test.sql", test);
        return new SqlTestProvider().runTests(context());
    }

    @Test
    @DisplayName("an assertion query that returns no row passes")
    void assertionWithNoRowsPasses() throws IOException {
        TestResult result = runWith(LEDGER, """
                INSERT INTO ledger (id, amount) VALUES (1, 10);
                SELECT id, amount FROM ledger WHERE amount < 0;
                """);

        assertTrue(result.executed());
        assertEquals(1, result.cases().size());
        assertEquals(TestOutcome.PASSED, result.cases().getFirst().outcome(),
                result.cases().getFirst().failureMessage());
    }

    @Test
    @DisplayName("an assertion query that returns rows fails, and names the rows")
    void assertionWithRowsFails() throws IOException {
        TestResult result = runWith(LEDGER, """
                INSERT INTO ledger (id, amount) VALUES (1, -5);
                SELECT id, amount FROM ledger WHERE amount < 0;
                """);

        TestCaseResult failing = result.cases().getFirst();
        assertEquals(TestOutcome.FAILED, failing.outcome());
        assertEquals("ASSERTION", failing.failureType());
        assertTrue(failing.failureMessage().contains("1 row(s)"), failing.failureMessage());
        assertTrue(failing.failureMessage().contains("amount=-5"), failing.failureMessage());
        assertEquals(1, result.failed());
    }

    @Test
    @DisplayName("a statement marked -- expect: error passes when the database rejects it")
    void anExpectedRejectionPasses() throws IOException {
        TestResult result = runWith(LEDGER_WITH_CONSTRAINT, """
                -- expect: error
                INSERT INTO ledger (id, amount) VALUES (1, -5);
                """);

        assertEquals(TestOutcome.PASSED, result.cases().getFirst().outcome(),
                result.cases().getFirst().failureMessage());
    }

    @Test
    @DisplayName("a statement marked -- expect: error fails when it is accepted")
    void anUnexpectedSuccessFails() throws IOException {
        TestResult result = runWith(LEDGER_WITH_CONSTRAINT, """
                -- expect: error
                INSERT INTO ledger (id, amount) VALUES (1, 5);
                """);

        TestCaseResult failing = result.cases().getFirst();
        assertEquals(TestOutcome.FAILED, failing.outcome());
        assertEquals("EXPECTED_REJECTION", failing.failureType());
        assertTrue(failing.failureMessage().contains("expected to be rejected"), failing.failureMessage());
    }

    @Test
    @DisplayName("a script that asserts nothing is skipped, not passed")
    void aScriptWithoutAnAssertionIsSkipped() throws IOException {
        TestResult result = runWith(LEDGER, "INSERT INTO ledger (id, amount) VALUES (1, 10);\n");

        TestCaseResult skipped = result.cases().getFirst();
        assertEquals(TestOutcome.SKIPPED, skipped.outcome());
        assertTrue(skipped.failureMessage().contains("declared no assertion"), skipped.failureMessage());
    }

    @Test
    @DisplayName("a schema that does not load fails the script rather than passing it")
    void aBrokenSchemaFailsTheScript() throws IOException {
        TestResult result = runWith("CREATE TABLE ledger (id INTEGER);\nCREATE TABLE ledger (id INTEGER);\n",
                "SELECT id FROM ledger WHERE id < 0;\n");

        TestCaseResult failing = result.cases().getFirst();
        assertEquals(TestOutcome.FAILED, failing.outcome());
        assertEquals("SCHEMA", failing.failureType());
        assertTrue(failing.failureMessage().contains("did not load"), failing.failureMessage());
    }

    @Test
    @DisplayName("a tree with no test script is not executed, and says how to write one")
    void noTestScriptsIsNotExecuted() throws IOException {
        write("db/schema.sql", LEDGER);

        TestResult result = new SqlTestProvider().runTests(context());

        assertFalse(result.executed());
        assertTrue(result.note().contains("test script"), result.note());
    }

    @Test
    @DisplayName("each script gets its own database, so one test cannot decide another's result")
    void eachScriptRunsAgainstAFreshDatabase() throws IOException {
        write("db/schema.sql", LEDGER);
        write("tests/first_test.sql", "INSERT INTO ledger (id, amount) VALUES (1, 10);\n"
                + "SELECT id FROM ledger WHERE id = 1 AND amount < 0;\n");
        write("tests/second_test.sql", "SELECT id FROM ledger WHERE amount < 0;\n");

        TestResult result = new SqlTestProvider().runTests(context());

        assertEquals(2, result.cases().size(), result.cases().toString());
        assertTrue(result.cases().stream().allMatch(testCase -> testCase.outcome() == TestOutcome.PASSED),
                result.cases().toString());
    }
}
