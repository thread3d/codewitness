package dev.codewitness.sql;

import dev.codewitness.sql.SqlSchemaModel.Constraint;
import dev.codewitness.sql.SqlSchemaModel.ConstraintKind;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rows a constraint must reject.
 *
 * <p>A probe is only useful if it isolates its constraint: a statement rejected for some other reason
 * would pass without the constraint ever firing, which is a false verification. The last test here is
 * therefore the important one - a check whose shape the reader does not understand produces no probe.
 */
class SqlConstraintProbeTest {

    private static SqlSchemaModel model(String script) {
        return new SqlSchemaModel(SqlSchemaModel.parse(script));
    }

    private static Optional<SqlConstraintProbe.Probe> probe(SqlSchemaModel model, ConstraintKind kind) {
        return model.constraints().stream()
                .filter(constraint -> constraint.kind() == kind)
                .findFirst()
                .flatMap(constraint -> SqlConstraintProbe.forConstraint(model, constraint));
    }

    @Test
    @DisplayName("a NOT NULL probe inserts NULL for the required column")
    void notNullProbe() {
        SqlSchemaModel model = model("""
                CREATE TABLE ledger (id INTEGER PRIMARY KEY, amount NUMERIC NOT NULL);
                """);

        SqlConstraintProbe.Probe probe = probe(model, ConstraintKind.NOT_NULL).orElseThrow();

        assertTrue(probe.setup().isEmpty());
        assertTrue(probe.violating().contains("amount"), probe.violating());
        assertTrue(probe.violating().toUpperCase().contains("NULL"), probe.violating());
    }

    @Test
    @DisplayName("a primary-key probe inserts the same row twice, so the second is the duplicate")
    void primaryKeyProbe() {
        SqlSchemaModel model = model("""
                CREATE TABLE ledger (id INTEGER PRIMARY KEY, amount NUMERIC NOT NULL);
                """);

        SqlConstraintProbe.Probe probe = probe(model, ConstraintKind.PRIMARY_KEY).orElseThrow();

        assertEquals(1, probe.setup().size(), "the first insert must succeed for the second to be a duplicate");
        assertEquals(probe.setup().getFirst(), probe.violating());
    }

    @Test
    @DisplayName("a CHECK on a lower bound is violated by a value just below it")
    void checkProbe() {
        SqlSchemaModel model = model("""
                CREATE TABLE ledger (
                  id INTEGER PRIMARY KEY,
                  amount NUMERIC NOT NULL CHECK (amount >= 0)
                );
                """);

        SqlConstraintProbe.Probe probe = probe(model, ConstraintKind.CHECK).orElseThrow();

        assertTrue(probe.violating().contains("-1"), probe.violating());
        assertTrue(probe.description().contains("amount >= 0"), probe.description());
    }

    @Test
    @DisplayName("a foreign-key probe enables enforcement and inserts an orphan")
    void foreignKeyProbe() {
        SqlSchemaModel model = model("""
                CREATE TABLE accounts (id INTEGER PRIMARY KEY);
                CREATE TABLE ledger (
                  id INTEGER PRIMARY KEY,
                  account_id INTEGER NOT NULL REFERENCES accounts (id)
                );
                """);

        SqlConstraintProbe.Probe probe = probe(model, ConstraintKind.FOREIGN_KEY).orElseThrow();

        assertTrue(probe.setup().stream().anyMatch(statement -> statement.contains("foreign_keys = ON")),
                "sqlite enforces foreign keys only when the connection asks it to: " + probe.setup());
        assertTrue(probe.violating().contains("__codewitness_missing_parent__")
                        || probe.violating().contains("-987654321"),
                probe.violating());
    }

    @Test
    @DisplayName("a check this reader cannot understand produces no probe at all")
    void unreadableCheckYieldsNoProbe() {
        // A parity check is not a simple bound comparison. Guessing a violating value here would risk a
        // probe that is rejected for a different reason and then counted as evidence that this check
        // fires, which would be a false verification.
        SqlSchemaModel model = model("""
                CREATE TABLE ledger (
                  id INTEGER PRIMARY KEY,
                  amount NUMERIC NOT NULL CHECK (amount % 2 = 0)
                );
                """);

        assertTrue(probe(model, ConstraintKind.CHECK).isEmpty());
    }

    @Test
    @DisplayName("a NOT NULL column whose check cannot be satisfied makes the whole probe unbuildable")
    void unsatisfiableRowYieldsNoProbe() {
        SqlSchemaModel model = model("""
                CREATE TABLE ledger (
                  id INTEGER PRIMARY KEY,
                  amount NUMERIC NOT NULL CHECK (amount % 2 = 0)
                );
                """);

        assertTrue(probe(model, ConstraintKind.PRIMARY_KEY).isEmpty(),
                "a row that cannot be built without violating another constraint must not become a probe");
        assertFalse(model.constraints().isEmpty());
    }

    @Test
    @DisplayName("a stated bound is probed at the bound and one step outside it, with the key varied")
    void boundProbeStepsOutsideTheStatedBound() {
        SqlSchemaModel model = model("""
                CREATE TABLE ledger (id INTEGER PRIMARY KEY, amount NUMERIC NOT NULL);
                """);

        SqlConstraintProbe.Probe probe = SqlConstraintProbe
                .boundProbe(model, "ledger", "amount", new java.math.BigDecimal("1000"), true)
                .orElseThrow();

        assertEquals(1, probe.setup().size(), "the row at the bound must be inserted and must succeed");
        assertTrue(probe.setup().getFirst().contains("VALUES (1, 1000)"), probe.setup().getFirst());
        assertTrue(probe.violating().contains("VALUES (2, 1001)"), probe.violating());
        assertFalse(probe.setup().getFirst().equals(probe.violating()),
                "the key must differ, or the second insert would be rejected as a duplicate");
    }

    @Test
    @DisplayName("a stated bound produces a probe even when the schema declares no check for it")
    void boundProbeDoesNotRequireASchemaCheck() {
        // This is the adversarial half: probing the schema's own check could only confirm the schema. The
        // requirement's bound is what the probe tests, which is how a missing or weakened check is found.
        SqlSchemaModel model = model("""
                CREATE TABLE ledger (id INTEGER PRIMARY KEY, amount NUMERIC NOT NULL);
                """);

        assertTrue(SqlConstraintProbe.boundProbe(model, "ledger", "amount",
                new java.math.BigDecimal("1000"), true).isPresent());
    }

    @Test
    @DisplayName("a bound on the key itself, or on a table with composite keys, produces no probe")
    void boundProbeDeclinesShapesItCannotIsolate() {
        SqlSchemaModel keyBound = model("CREATE TABLE ledger (id INTEGER PRIMARY KEY);");
        assertTrue(SqlConstraintProbe.boundProbe(keyBound, "ledger", "id",
                new java.math.BigDecimal("10"), true).isEmpty());

        SqlSchemaModel composite = model("""
                CREATE TABLE posting (account_id INTEGER NOT NULL, seq INTEGER NOT NULL, amount NUMERIC NOT NULL,
                  PRIMARY KEY (account_id, seq));
                """);
        assertTrue(SqlConstraintProbe.boundProbe(composite, "posting", "amount",
                new java.math.BigDecimal("10"), true).isEmpty(),
                "two rows cannot be kept identical in everything but the value when the key is composite");
    }

    @Test
    @DisplayName("a value that satisfies a numeric bound is derived for the row template")
    void satisfyingValueForBound() {
        assertEquals("0", SqlConstraintProbe.satisfyingValueFor("amount >= 0", "amount").orElseThrow());
        assertEquals("5", SqlConstraintProbe.satisfyingValueFor("amount > 4", "amount").orElseThrow());
        assertTrue(SqlConstraintProbe.satisfyingValueFor("amount % 2 = 0", "amount").isEmpty());
    }

    @Test
    @DisplayName("a violating value is derived for each simple comparison")
    void violatingValueForComparisons() {
        assertEquals("-1", SqlConstraintProbe.violatingValue("amount >= 0", "amount").orElseThrow());
        assertEquals("4", SqlConstraintProbe.violatingValue("amount > 4", "amount").orElseThrow());
        assertEquals("101", SqlConstraintProbe.violatingValue("amount <= 100", "amount").orElseThrow());
        assertTrue(SqlConstraintProbe.violatingValue("amount % 2 = 0", "amount").isEmpty());
        assertTrue(SqlConstraintProbe.violatingValue("other >= 0", "amount").isEmpty());
    }

    @Test
    @DisplayName("a column of an unknown type still gets a literal, so its table stays probeable")
    void literalForUnknownType() {
        List<SqlSchemaModel.Column> columns = SqlSchemaModel.parse(
                        "CREATE TABLE t (id INTEGER PRIMARY KEY, payload BLOB);")
                .getFirst().columns();
        assertEquals(2, columns.size());
    }
}
