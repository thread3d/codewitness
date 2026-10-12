package dev.codewitness.languages;

import dev.codewitness.core.spi.LanguageDetectorRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recognition of SQL, the language whose files are conventionally the deepest in the tree.
 *
 * <p>The interesting case is depth: a migration under {@code src/main/resources/db/migration} is the
 * normal place for SQL in a JVM project, and it is one level past the scan the other detectors use. A
 * detector that only saw a root-level {@code schema.sql} would be silent about most SQL trees, which is
 * the exact failure this detector exists to prevent.
 */
class SqlLanguageDetectorTest {

    @TempDir
    Path root;

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, content);
    }

    @Test
    @DisplayName("a migration under a build's resource directory is recognised, though it sits past the default depth")
    void readsDeepMigrationTrees() throws IOException {
        write("src/main/resources/db/migration/V1__create_ledger.sql", "CREATE TABLE ledger (id INTEGER);\n");

        assertEquals("src/main/resources/db/migration/V1__create_ledger.sql",
                new SqlLanguageDetector().evidence(root).orElseThrow());
    }

    @Test
    @DisplayName("a migration tool's own configuration names the language when the migrations are out of reach")
    void readsToolDescriptors() throws IOException {
        write("flyway.conf", "flyway.locations=filesystem:sql\n");

        assertEquals("flyway.conf", new SqlLanguageDetector().evidence(root).orElseThrow());
    }

    @Test
    @DisplayName("a project with no SQL in it is not claimed from a name it merely shares")
    void doesNotClaimWhatIsNotThere() throws IOException {
        write("README.md", "A project with no SQL in it.\n");
        write("src/main/java/Ledger.java", "class Ledger {}\n");

        assertTrue(new SqlLanguageDetector().evidence(root).isEmpty());
    }

    @Test
    @DisplayName("dependencies, build output and sample trees are not mistaken for the project's own SQL")
    void ignoresVendoredAndSampleTrees() throws IOException {
        write("node_modules/some-package/schema.sql", "CREATE TABLE t (id INTEGER);\n");
        write("target/generated/db/V1.sql", "CREATE TABLE t (id INTEGER);\n");
        write("vendor/migrations/V1.sql", "CREATE TABLE t (id INTEGER);\n");
        write("fixtures/sample/schema.sql", "CREATE TABLE t (id INTEGER);\n");
        write("testdata/seed.sql", "INSERT INTO t VALUES (1);\n");

        assertTrue(new SqlLanguageDetector().evidence(root).isEmpty());
    }

    @Test
    @DisplayName("the detector is discovered through ServiceLoader, as every other one is")
    void discoveryFindsTheSqlDetector() {
        List<String> ids = new LanguageDetectorRegistry().discover().ids();

        assertTrue(ids.contains("sql"), ids.toString());
        assertFalse(ids.contains("java"), "a language with an adapter does not need a detector here: " + ids);
    }
}
