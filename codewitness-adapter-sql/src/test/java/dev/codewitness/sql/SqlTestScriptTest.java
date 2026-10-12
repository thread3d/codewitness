package dev.codewitness.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A SQL test script's conventions: which statement must be rejected, and which query must return nothing.
 */
class SqlTestScriptTest {

    @Test
    @DisplayName("the -- expect: error directive belongs to the statement that follows it")
    void directiveAttachesToTheNextStatement() {
        List<SqlTestScript.Statement> statements = SqlTestScript.parse("""
                CREATE TABLE ledger (id INTEGER PRIMARY KEY, amount NUMERIC NOT NULL);
                -- expect: error
                INSERT INTO ledger (id, amount) VALUES (1, NULL);
                SELECT id FROM ledger WHERE amount < 0;
                """);

        assertEquals(3, statements.size(), statements.toString());
        assertFalse(statements.get(0).expectError());
        assertTrue(statements.get(1).expectError());
        assertTrue(statements.get(1).sql().startsWith("INSERT INTO ledger"));
        assertFalse(statements.get(2).expectError());
    }

    @Test
    @DisplayName("a directive's statement is still carried whole")
    void directiveDoesNotEatTheStatement() {
        List<SqlTestScript.Statement> statements =
                SqlTestScript.parse("-- expect: error\nINSERT INTO t VALUES (1);\n");

        assertEquals(1, statements.size());
        assertEquals("INSERT INTO t VALUES (1)", statements.getFirst().sql());
    }

    @Test
    @DisplayName("an assertion query is a SELECT, a WITH or a VALUES, whatever its case")
    void recognisesAssertionQueries() {
        assertTrue(SqlTestScript.isAssertionQuery("SELECT count(*) FROM ledger"));
        assertTrue(SqlTestScript.isAssertionQuery("  with bad as (select 1) select * from bad"));
        assertTrue(SqlTestScript.isAssertionQuery("VALUES (1)"));
        assertFalse(SqlTestScript.isAssertionQuery("INSERT INTO ledger VALUES (1)"));
        assertFalse(SqlTestScript.isAssertionQuery("UPDATE ledger SET amount = 1"));
    }

    @Test
    @DisplayName("only the first statement of a group carries the directive")
    void directiveAppliesToTheFirstStatementOfItsGroup() {
        // A directive is a statement-level annotation. Applying it to a whole group would silently change
        // what a script asserts, so the rest of the group is treated as ordinary statements.
        List<SqlTestScript.Statement> statements =
                SqlTestScript.parse("-- expect: error\nINSERT INTO t VALUES (1); INSERT INTO t VALUES (2);\n");

        assertEquals(2, statements.size(), statements.toString());
        assertTrue(statements.get(0).expectError());
        assertFalse(statements.get(1).expectError());
    }

    @Test
    @DisplayName("a script with no statement parses to nothing")
    void blankScriptParsesToNothing() {
        assertEquals(List.of(), SqlTestScript.parse(""));
        assertEquals(List.of(), SqlTestScript.parse("-- only a comment\n"));
    }

    @Test
    @DisplayName("the directive spelling is forgiving about spaces and case")
    void directiveSpellingIsForgiving() {
        assertTrue(SqlTestScript.isErrorDirective("-- expect: error"));
        assertTrue(SqlTestScript.isErrorDirective("  -- EXPECT:ERROR  "));
        assertTrue(SqlTestScript.isErrorDirective("-- expect-error"));
        assertFalse(SqlTestScript.isErrorDirective("-- expect: rows"));
        assertFalse(SqlTestScript.isErrorDirective("SELECT 1"));
    }
}
