package dev.codewitness.sql.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.Severity;
import dev.codewitness.sql.SqlTestContexts;

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
 * The SQL correctness rule table, and the two properties that make it trustworthy: it reads what the
 * database would execute, and everything it says is advisory.
 */
class SqlHeuristicAnalyzerTest {

    @TempDir
    Path root;

    private List<Finding> findings(String schema) throws IOException {
        SqlTestContexts.write(root, "db/schema.sql", schema);
        return new SqlHeuristicAnalyzer().analyze(SqlTestContexts.context(root, root.resolve("run")));
    }

    private static List<Finding> withRule(List<Finding> findings, String rule) {
        return findings.stream().filter(finding -> rule.equals(finding.ruleId())).toList();
    }

    @Test
    @DisplayName("an UPDATE with no WHERE is reported as a correctness rule at high severity")
    void updateWithoutWhere() throws IOException {
        List<Finding> findings = findings("UPDATE ledger SET amount = 0;\n");

        List<Finding> matches = withRule(findings, "sql-update-without-where");
        assertEquals(1, matches.size(), findings.toString());
        assertEquals(Severity.HIGH, matches.getFirst().severity());
        assertEquals(1, matches.getFirst().line());
    }

    @Test
    @DisplayName("an UPDATE with a WHERE is not reported")
    void updateWithWhereIsFine() throws IOException {
        assertTrue(withRule(findings("UPDATE ledger SET amount = 0 WHERE id = 1;\n"),
                "sql-update-without-where").isEmpty());
    }

    @Test
    @DisplayName("a DELETE with no WHERE is reported")
    void deleteWithoutWhere() throws IOException {
        assertEquals(1, withRule(findings("DELETE FROM ledger;\n"), "sql-delete-without-where").size());
        assertTrue(withRule(findings("DELETE FROM ledger WHERE id = 1;\n"), "sql-delete-without-where").isEmpty());
    }

    @Test
    @DisplayName("a comparison with NULL using = is reported")
    void nullComparison() throws IOException {
        assertEquals(1, withRule(findings("SELECT id FROM ledger WHERE amount = NULL;\n"),
                "sql-null-comparison").size());
        assertTrue(withRule(findings("SELECT id FROM ledger WHERE amount IS NULL;\n"),
                "sql-null-comparison").isEmpty());
    }

    @Test
    @DisplayName("NOT IN over a subquery is reported")
    void notInSubquery() throws IOException {
        assertEquals(1, withRule(findings("SELECT id FROM ledger WHERE id NOT IN (SELECT id FROM closed);\n"),
                "sql-not-in-subquery").size());
    }

    @Test
    @DisplayName("SELECT * is reported as a maintainability rule")
    void selectStar() throws IOException {
        assertEquals(1, withRule(findings("SELECT * FROM ledger;\n"), "sql-select-star").size());
        assertTrue(withRule(findings("SELECT id FROM ledger;\n"), "sql-select-star").isEmpty());
    }

    @Test
    @DisplayName("a table declared without a primary key is reported")
    void tableWithoutPrimaryKey() throws IOException {
        assertEquals(1, withRule(findings("CREATE TABLE ledger (amount NUMERIC);\n"),
                "sql-table-without-primary-key").size());
        assertTrue(withRule(findings("CREATE TABLE ledger (id INTEGER PRIMARY KEY);\n"),
                "sql-table-without-primary-key").isEmpty());
    }

    @Test
    @DisplayName("a keyword inside a comment or a string literal is not a finding")
    void readsWhatTheDatabaseWouldExecute() throws IOException {
        // The rule reads the masked view, so a destructive statement that only appears in a comment or in
        // data is not reported - which is the difference between a usable rule table and one that cries
        // wolf on every file that explains itself.
        List<Finding> findings = findings("""
                -- DELETE FROM ledger; once, during the incident
                INSERT INTO note (body) VALUES ('DROP TABLE ledger');
                """);

        assertTrue(findings.isEmpty(), findings.toString());
    }

    @Test
    @DisplayName("every finding is advisory, so a rule match can never fail a requirement on its own")
    void findingsAreAdvisory() throws IOException {
        List<Finding> findings = findings("UPDATE ledger SET amount = 0;\nSELECT * FROM ledger;\n");

        assertFalse(findings.isEmpty());
        assertTrue(findings.stream().noneMatch(Finding::fromDeterministicTool),
                "the analyzer's id must mark its findings as heuristic: " + findings);
    }
}
