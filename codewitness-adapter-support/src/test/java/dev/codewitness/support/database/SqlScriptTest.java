package dev.codewitness.support.database;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Separating a schema into executable statements.
 *
 * <p>This is worth its own tests because the alternative is silent: the SQLite driver runs only the
 * first statement of a multi-statement string and reports success, so a splitter that missed a case
 * would load part of a schema and every later observation would be made against it.
 */
class SqlScriptTest {

    @Test
    @DisplayName("statements are separated on their own semicolons")
    void splitsStatements() {
        assertEquals(List.of("CREATE TABLE t (id INTEGER)", "INSERT INTO t VALUES (1)"),
                SqlScript.statements("CREATE TABLE t (id INTEGER);\nINSERT INTO t VALUES (1);\n"));
    }

    @Test
    @DisplayName("a semicolon inside a string literal is part of the value")
    void keepsSemicolonsInsideStrings() {
        assertEquals(List.of("INSERT INTO t VALUES ('a;b')"),
                SqlScript.statements("INSERT INTO t VALUES ('a;b');"));
    }

    @Test
    @DisplayName("a doubled quote inside a literal does not close it")
    void handlesEscapedQuotes() {
        assertEquals(List.of("INSERT INTO t VALUES ('it''s; fine')"),
                SqlScript.statements("INSERT INTO t VALUES ('it''s; fine');"));
    }

    @Test
    @DisplayName("comments are removed, and their semicolons separate nothing")
    void removesComments() {
        String script = """
                -- a comment; with a semicolon
                CREATE TABLE t (id INTEGER); /* another; one */
                INSERT INTO t VALUES (1);
                """;

        assertEquals(List.of("CREATE TABLE t (id INTEGER)", "INSERT INTO t VALUES (1)"),
                SqlScript.statements(script));
    }

    @Test
    @DisplayName("a trigger body is one statement, semicolons and all")
    void keepsTriggerBodiesWhole() {
        String script = "CREATE TRIGGER trg AFTER INSERT ON t BEGIN UPDATE t SET n = 1; DELETE FROM u; END;\n"
                + "INSERT INTO t VALUES (1);";

        List<String> statements = SqlScript.statements(script);

        assertEquals(2, statements.size(), statements.toString());
        assertEquals("CREATE TRIGGER trg AFTER INSERT ON t BEGIN UPDATE t SET n = 1; DELETE FROM u; END",
                statements.get(0));
    }

    @Test
    @DisplayName("a CASE inside a trigger body has its own END and does not end the body")
    void countsNestedEndKeywords() {
        String script = "CREATE TRIGGER trg AFTER INSERT ON t "
                + "BEGIN SELECT CASE WHEN 1 THEN 2 ELSE 3 END; END;";

        assertEquals(1, SqlScript.statements(script).size(), SqlScript.statements(script).toString());
    }

    @Test
    @DisplayName("a trailing statement without a semicolon is still returned")
    void keepsTrailingStatement() {
        assertEquals(List.of("SELECT 1"), SqlScript.statements("SELECT 1"));
    }

    @Test
    @DisplayName("a script that holds only comments yields no statements")
    void blankScriptYieldsNothing() {
        assertEquals(List.of(), SqlScript.statements("   \n-- nothing here\n"));
        assertEquals(List.of(), SqlScript.statements(null));
    }
}
