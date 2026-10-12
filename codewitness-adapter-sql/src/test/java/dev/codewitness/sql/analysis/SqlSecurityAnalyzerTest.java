package dev.codewitness.sql.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
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
 * The SQL security rule table.
 */
class SqlSecurityAnalyzerTest {

    @TempDir
    Path root;

    private List<Finding> findings(String schema) throws IOException {
        SqlTestContexts.write(root, "db/schema.sql", schema);
        return new SqlSecurityAnalyzer().analyze(SqlTestContexts.context(root, root.resolve("run")));
    }

    private static List<Finding> withRule(List<Finding> findings, String rule) {
        return findings.stream().filter(finding -> rule.equals(finding.ruleId())).toList();
    }

    @Test
    @DisplayName("dynamic SQL executed from a string is reported")
    void dynamicSql() throws IOException {
        List<Finding> matches = withRule(findings("EXECUTE IMMEDIATE 'DROP TABLE ledger';\n"),
                "sql-execute-immediate");

        assertEquals(1, matches.size());
        assertEquals(Severity.HIGH, matches.getFirst().severity());
        assertEquals(FindingCategory.SECURITY, matches.getFirst().category());
    }

    @Test
    @DisplayName("an operating-system command channel is reported as critical")
    void osCommandChannel() throws IOException {
        List<Finding> matches = withRule(findings("EXEC xp_cmdshell 'dir';\n"), "sql-os-command");

        assertEquals(1, matches.size());
        assertEquals(Severity.CRITICAL, matches.getFirst().severity());
    }

    @Test
    @DisplayName("a privilege granted to every user is reported")
    void grantToPublic() throws IOException {
        assertEquals(1, withRule(findings("GRANT ALL ON ledger TO PUBLIC;\n"), "sql-grant-public").size());
        assertTrue(withRule(findings("GRANT SELECT ON ledger TO reporting;\n"), "sql-grant-public").isEmpty());
    }

    @Test
    @DisplayName("a native extension load and an attached database are reported")
    void extensionAndAttach() throws IOException {
        assertEquals(1, withRule(findings("SELECT load_extension('evil.so');\n"), "sql-load-extension").size());
        assertEquals(1, withRule(findings("ATTACH DATABASE 'other.db' AS other;\n"), "sql-attach-database").size());
    }

    @Test
    @DisplayName("dynamic SQL assembled from a value is reported")
    void dynamicSqlFromConcatenation() throws IOException {
        // The SQL side of the rule every language adapter carries for a query built by concatenation: the
        // assembled statement is visible in the SQL itself rather than in the code that sends it.
        assertEquals(1, withRule(findings("EXECUTE 'SELECT * FROM ' || table_name;\n"),
                "sql-dynamic-sql-concatenation").size());
        assertEquals(1, withRule(findings("EXECUTE format('SELECT * FROM %I', tbl);\n"),
                "sql-dynamic-sql-concatenation").size());
        assertTrue(withRule(findings("EXECUTE 'SELECT 1';\n"), "sql-dynamic-sql-concatenation").isEmpty(),
                "a literal statement with nothing assembled into it is not dynamic SQL");
    }

    @Test
    @DisplayName("security findings are advisory, because a regular expression is not a demonstration")
    void findingsAreAdvisory() throws IOException {
        List<Finding> findings = findings("EXECUTE IMMEDIATE 'DROP TABLE ledger';\n");

        assertFalse(findings.isEmpty());
        assertTrue(findings.stream().noneMatch(Finding::fromDeterministicTool),
                "a regex that recognises a dangerous statement has not demonstrated an exploitable one: "
                        + findings);
    }
}
