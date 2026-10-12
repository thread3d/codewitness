package dev.codewitness.sql;

import dev.codewitness.sql.SqlSchemaModel.Constraint;
import dev.codewitness.sql.SqlSchemaModel.ConstraintKind;
import dev.codewitness.sql.SqlSchemaModel.Table;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading a schema's constraints out of its own DDL.
 *
 * <p>The reader is deliberately conservative: a construct it does not recognise yields no constraint
 * rather than a guessed one, so the tests here are as much about what it declines to claim as about what
 * it finds.
 */
class SqlSchemaModelTest {

    private static final String LEDGER = """
            CREATE TABLE ledger (
              id INTEGER PRIMARY KEY,
              account_id INTEGER NOT NULL REFERENCES accounts (id),
              amount NUMERIC NOT NULL CHECK (amount >= 0),
              note TEXT UNIQUE
            );
            """;

    private static List<Table> tables(String script) {
        return SqlSchemaModel.parse(script);
    }

    private static List<Constraint> constraintsOfKind(List<Table> tables, ConstraintKind kind) {
        return tables.stream()
                .flatMap(table -> table.constraints().stream())
                .filter(constraint -> constraint.kind() == kind)
                .toList();
    }

    @Test
    @DisplayName("columns, keys, nullability, checks and references are all read")
    void readsConstraints() {
        List<Table> tables = tables(LEDGER);

        assertEquals(1, tables.size());
        Table ledger = tables.getFirst();
        assertEquals("ledger", ledger.name());
        assertEquals(4, ledger.columns().size());
        assertTrue(ledger.singlePrimaryKey().isPresent());
        assertEquals("id", ledger.singlePrimaryKey().orElseThrow().name());

        assertEquals(2, constraintsOfKind(tables, ConstraintKind.NOT_NULL).size());
        assertEquals(1, constraintsOfKind(tables, ConstraintKind.PRIMARY_KEY).size());
        assertEquals(1, constraintsOfKind(tables, ConstraintKind.UNIQUE).size());
        assertEquals(1, constraintsOfKind(tables, ConstraintKind.FOREIGN_KEY).size());

        Constraint check = constraintsOfKind(tables, ConstraintKind.CHECK).getFirst();
        assertEquals("amount >= 0", check.expression());
        assertEquals(List.of("amount"), check.columns());
        assertEquals("accounts", constraintsOfKind(tables, ConstraintKind.FOREIGN_KEY).getFirst().referenceTable());
    }

    @Test
    @DisplayName("a table-level CHECK naming one column is attributed to it")
    void attributesTableCheckToOneColumn() {
        List<Table> tables = tables("""
                CREATE TABLE ledger (
                  id INTEGER PRIMARY KEY,
                  amount NUMERIC NOT NULL,
                  CHECK (amount >= 0)
                );
                """);

        Constraint check = constraintsOfKind(tables, ConstraintKind.CHECK).getFirst();
        assertEquals(List.of("amount"), check.columns(), "a probe has to know which column to violate");
        // The expression is added to the column's own checks, so a probe against another constraint on
        // the same table builds a row that satisfies this one.
        assertTrue(tables.getFirst().column("amount").orElseThrow().checks().contains("amount >= 0"));
    }

    @Test
    @DisplayName("a table-level CHECK naming two columns is left unattributed rather than guessed")
    void leavesMultiColumnCheckUnattributed() {
        List<Table> tables = tables("""
                CREATE TABLE ledger (
                  id INTEGER PRIMARY KEY,
                  debit NUMERIC NOT NULL,
                  credit NUMERIC NOT NULL,
                  CHECK (debit >= 0 AND credit >= 0)
                );
                """);

        assertTrue(constraintsOfKind(tables, ConstraintKind.CHECK).getFirst().columns().isEmpty());
    }

    @Test
    @DisplayName("a composite primary key and an UNIQUE list are read")
    void readsTableLevelKeys() {
        List<Table> tables = tables("""
                CREATE TABLE posting (
                  account_id INTEGER NOT NULL,
                  seq INTEGER NOT NULL,
                  memo TEXT,
                  PRIMARY KEY (account_id, seq),
                  UNIQUE (account_id, memo)
                );
                """);

        assertEquals(List.of("account_id", "seq"),
                constraintsOfKind(tables, ConstraintKind.PRIMARY_KEY).getFirst().columns());
        assertEquals(List.of("account_id", "memo"), constraintsOfKind(tables, ConstraintKind.UNIQUE).getFirst().columns());
        assertTrue(tables.getFirst().singlePrimaryKey().isEmpty(), "a composite key has no single column");
    }

    @Test
    @DisplayName("quoted identifiers, IF NOT EXISTS and a schema prefix are read")
    void readsQuotedIdentifiers() {
        List<Table> tables = tables("""
                CREATE TABLE IF NOT EXISTS "public"."ledger" (
                  "id" INTEGER PRIMARY KEY,
                  "amount" NUMERIC NOT NULL
                );
                """);

        assertEquals("ledger", tables.getFirst().name());
        assertEquals(List.of("id", "amount"),
                tables.getFirst().columns().stream().map(SqlSchemaModel.Column::name).toList());
    }

    @Test
    @DisplayName("a comma inside a CHECK expression does not split the table body")
    void doesNotSplitInsideExpressions() {
        List<Table> tables = tables("""
                CREATE TABLE ledger (
                  id INTEGER PRIMARY KEY,
                  kind TEXT NOT NULL CHECK (kind IN ('debit', 'credit', 'fee'))
                );
                """);

        assertEquals(2, tables.getFirst().columns().size(), tables.getFirst().columns().toString());
        assertEquals("kind IN ('debit', 'credit', 'fee')",
                constraintsOfKind(tables, ConstraintKind.CHECK).getFirst().expression());
    }

    @Test
    @DisplayName("a script with no CREATE TABLE declares nothing")
    void ignoresNonTableStatements() {
        assertTrue(tables("CREATE INDEX idx ON ledger (id);\nINSERT INTO ledger VALUES (1);\n").isEmpty());
        assertTrue(SqlSchemaModel.parse("").isEmpty());
    }

    @Test
    @DisplayName("a model with no tables is empty, which the generator reports rather than probes")
    void emptyModel() {
        assertTrue(new SqlSchemaModel(List.of()).isEmpty());
        assertFalse(new SqlSchemaModel(SqlSchemaModel.parse(LEDGER)).isEmpty());
    }
}
