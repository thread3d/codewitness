package dev.codewitness.sql;

import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;

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
 * Detection of a SQL tree, and the split between the schema under test and the scripts that check it.
 *
 * <p>Getting that split wrong is the failure mode that matters: a migration counted as a test would be
 * executed as an assertion, and a test counted as schema would be loaded before the tests run and then
 * loaded again as a test file.
 */
class SqlProjectDetectorTest {

    @TempDir
    Path root;

    private void write(String relative, String content) throws IOException {
        SqlTestContexts.write(root, relative, content);
    }

    @Test
    @DisplayName("a tree of migration scripts is a SQL project")
    void detectsASqlProject() throws IOException {
        write("db/migrations/V1__create.sql", "CREATE TABLE ledger (id INTEGER PRIMARY KEY);\n");

        assertTrue(SqlProjectDetector.looksLikeSqlProject(root));
        ProjectInfo info = SqlProjectDetector.inspect(ProjectRef.of(root));

        assertEquals(LanguageId.SQL, info.language());
        assertEquals("sql", info.buildSystem().id());
        assertTrue(info.sourceExtensions().contains("sql"));
        assertEquals(List.of("db/migrations/V1__create.sql"),
                List.of(info.attributes().get(SqlProjectDetector.ATTR_SCHEMA_FILES).split(",")));
        assertEquals("db/migrations", info.attributes().get(SqlProjectDetector.ATTR_MIGRATION_DIRS));
    }

    @Test
    @DisplayName("a script under a test directory is a test, not part of the schema")
    void separatesTestsFromSchema() throws IOException {
        write("db/schema.sql", "CREATE TABLE ledger (id INTEGER PRIMARY KEY);\n");
        write("tests/ledger_test.sql", "-- R-001\nSELECT 1;\n");

        List<String> schema = SqlProjectDetector.schemaFiles(root).stream()
                .map(path -> root.relativize(path).toString()).toList();
        List<String> tests = SqlProjectDetector.testFiles(root).stream()
                .map(path -> root.relativize(path).toString()).toList();

        assertEquals(List.of("db/schema.sql"), schema);
        assertEquals(List.of("tests/ledger_test.sql"), tests);
    }

    @Test
    @DisplayName("a file named like a test is a test even outside a test directory")
    void nameMarksATest() throws IOException {
        write("sql/ledger_test.sql", "SELECT 1;\n");
        write("sql/schema.sql", "CREATE TABLE ledger (id INTEGER PRIMARY KEY);\n");

        assertTrue(SqlProjectDetector.isTestFile(root.resolve("sql/ledger_test.sql")));
        assertFalse(SqlProjectDetector.isTestFile(root.resolve("sql/schema.sql")));
    }

    @Test
    @DisplayName("test and schema directories are reported so later phases know where to look")
    void reportsLayout() throws IOException {
        write("db/schema.sql", "CREATE TABLE ledger (id INTEGER PRIMARY KEY);\n");
        write("tests/one_test.sql", "SELECT 1;\n");

        ProjectInfo info = SqlProjectDetector.inspect(ProjectRef.of(root));

        assertTrue(info.testSourceDirs().stream().anyMatch(dir -> dir.endsWith("tests")), info.testSourceDirs().toString());
        assertTrue(info.sourceDirs().stream().anyMatch(dir -> dir.endsWith("db")), info.sourceDirs().toString());
        assertEquals("true", info.attributes().get(SqlProjectDetector.ATTR_HAS_TESTS));
    }

    @Test
    @DisplayName("a suite name matches the identifier the core's mapper derives for the same file")
    void suiteNameMatchesTheMapper() throws IOException {
        write("tests/ledger_test.sql", "SELECT 1;\n");
        Path testRoot = root.resolve("tests");
        Path file = testRoot.resolve("ledger_test.sql");

        assertEquals("ledger_test", SqlProjectDetector.suiteName(testRoot, file));
    }

    @Test
    @DisplayName("nested test scripts keep their directory in the suite name")
    void nestedSuiteNamesKeepThePath() throws IOException {
        write("tests/limits/ledger_test.sql", "SELECT 1;\n");
        Path testRoot = root.resolve("tests");
        Path file = testRoot.resolve("limits/ledger_test.sql");

        assertEquals("limits.ledger_test", SqlProjectDetector.suiteName(testRoot, file));
    }

    @Test
    @DisplayName("build output and vendored trees are not mistaken for the project's own SQL")
    void ignoresBuildOutput() throws IOException {
        write("target/generated/schema.sql", "CREATE TABLE t (id INTEGER);\n");
        write("node_modules/pkg/schema.sql", "CREATE TABLE t (id INTEGER);\n");
        write("vendor/schema.sql", "CREATE TABLE t (id INTEGER);\n");

        assertTrue(SqlProjectDetector.sourceFiles(root).isEmpty(), SqlProjectDetector.sourceFiles(root).toString());
        assertFalse(SqlProjectDetector.looksLikeSqlProject(root));
    }

    @Test
    @DisplayName("a tree with test scripts but no schema is claimed and says so")
    void warnsWhenThereIsNoSchema() throws IOException {
        write("tests/ledger_test.sql", "SELECT 1;\n");

        ProjectInfo info = SqlProjectDetector.inspect(ProjectRef.of(root));

        assertTrue(info.attributes().containsKey(SqlProjectDetector.ATTR_DETECTION_WARNING),
                info.attributes().toString());
        assertTrue(info.attributes().get(SqlProjectDetector.ATTR_DETECTION_WARNING).contains("declares no schema"));
    }
}
