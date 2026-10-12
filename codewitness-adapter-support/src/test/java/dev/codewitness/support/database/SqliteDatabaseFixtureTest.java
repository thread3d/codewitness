package dev.codewitness.support.database;

import dev.codewitness.core.config.VerificationConfig;
import dev.codewitness.core.io.ArtifactStore;
import dev.codewitness.core.spi.DatabaseFixture;
import dev.codewitness.core.spi.PreparedDatabase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SQLite fixture, exercised against a real database rather than a mock.
 *
 * <p>The fixture exists so that a run's tests can execute against the schema the project actually
 * declares, so the tests here assert exactly that: a connection made from the reported URL sees the
 * tables and rows the declared scripts created. The failure cases matter as much: a schema that does not
 * apply must come back as an explained "not prepared", never as a half-loaded database reported as ready.
 */
class SqliteDatabaseFixtureTest {

    @TempDir
    Path root;

    private DatabaseFixture.Request request(VerificationConfig config) {
        return new DatabaseFixture.Request(config, root, root.resolve("run"), new ArtifactStore(root.resolve("run")));
    }

    private VerificationConfig config(Path schema, Path seed) {
        VerificationConfig defaults = VerificationConfig.defaults();
        return new VerificationConfig(defaults.project(), defaults.language(), defaults.limits(), defaults.tests(),
                defaults.mutation(), defaults.propertyTesting(), defaults.staticAnalysis(), defaults.security(),
                defaults.llm(), defaults.report(), defaults.sandbox(), defaults.scenarios(),
                new VerificationConfig.Database(true, "sqlite", schema, seed, null, Duration.ofSeconds(30)));
    }

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static int count(PreparedDatabase prepared, String table) throws Exception {
        try (Connection connection = DriverManager.getConnection(prepared.jdbcUrl());
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }

    @Test
    @DisplayName("the declared schema is applied and the reported URL reaches it")
    void appliesTheDeclaredSchema() throws Exception {
        write("db/schema.sql", "CREATE TABLE ledger (id INTEGER PRIMARY KEY, amount NUMERIC NOT NULL);\n"
                + "INSERT INTO ledger (id, amount) VALUES (1, 10);\n");

        PreparedDatabase prepared =
                new SqliteDatabaseFixture().prepare(request(config(Path.of("db/schema.sql"), null)));

        assertTrue(prepared.prepared(), prepared.note());
        assertEquals("sqlite", prepared.id());
        assertEquals(prepared.jdbcUrl(), prepared.environment().get(DatabaseFixture.URL_VARIABLE));
        assertEquals(1, count(prepared, "ledger"));
        assertTrue(prepared.description().contains("1 table"), prepared.description());
    }

    @Test
    @DisplayName("a directory of migrations is applied in file-name order")
    void appliesAMigrationDirectoryInOrder() throws Exception {
        // The second script alters the table the first one created, so applying them out of order fails:
        // the test would catch a fixture that listed the directory in whatever order the filesystem gave.
        write("db/migrations/V1__create.sql", "CREATE TABLE ledger (id INTEGER PRIMARY KEY);\n");
        write("db/migrations/V2__add_amount.sql", "ALTER TABLE ledger ADD COLUMN amount NUMERIC NOT NULL DEFAULT 0;\n"
                + "INSERT INTO ledger (id, amount) VALUES (7, 500);\n");

        PreparedDatabase prepared = new SqliteDatabaseFixture()
                .prepare(request(config(Path.of("db/migrations"), null)));

        assertTrue(prepared.prepared(), prepared.note());
        assertEquals(1, count(prepared, "ledger"));
        assertEquals(500, amountOf(prepared, 7));
    }

    @Test
    @DisplayName("a seed file is applied after the schema")
    void appliesTheSeedAfterTheSchema() throws Exception {
        write("db/schema.sql", "CREATE TABLE ledger (id INTEGER PRIMARY KEY, amount NUMERIC NOT NULL);\n");
        write("db/seed.sql", "INSERT INTO ledger (id, amount) VALUES (1, 25), (2, 30);\n");

        PreparedDatabase prepared = new SqliteDatabaseFixture()
                .prepare(request(config(Path.of("db/schema.sql"), Path.of("db/seed.sql"))));

        assertTrue(prepared.prepared(), prepared.note());
        assertEquals(2, count(prepared, "ledger"));
    }

    @Test
    @DisplayName("a schema that does not apply is reported, not half-loaded")
    void aBrokenSchemaIsReportedNotHalfLoaded() throws Exception {
        write("db/schema.sql", "CREATE TABLE ledger (id INTEGER PRIMARY KEY);\n"
                + "CREATE TABLE ledger (id INTEGER PRIMARY KEY);\n");

        PreparedDatabase prepared =
                new SqliteDatabaseFixture().prepare(request(config(Path.of("db/schema.sql"), null)));

        assertFalse(prepared.prepared(), "a schema that fails to apply must not be reported as ready");
        assertNull(prepared.jdbcUrl());
        assertTrue(prepared.note().contains("schema.sql"), prepared.note());
        assertTrue(prepared.limitation().contains("prepared no database"), prepared.limitation());
    }

    @Test
    @DisplayName("a declared schema that is not there is explained rather than ignored")
    void aMissingSchemaIsExplained() {
        PreparedDatabase prepared = new SqliteDatabaseFixture()
                .prepare(request(config(Path.of("db/absent.sql"), null)));

        assertFalse(prepared.prepared());
        assertTrue(prepared.note().contains("absent.sql"), prepared.note());
    }

    @Test
    @DisplayName("a database URL the project declares is used as it stands")
    void anExplicitUrlIsExported() throws Exception {
        Path database = root.resolve("project.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ledger (id INTEGER PRIMARY KEY)");
        }

        VerificationConfig defaults = VerificationConfig.defaults();
        VerificationConfig explicit = new VerificationConfig(defaults.project(), defaults.language(),
                defaults.limits(), defaults.tests(), defaults.mutation(), defaults.propertyTesting(),
                defaults.staticAnalysis(), defaults.security(), defaults.llm(), defaults.report(),
                defaults.sandbox(), defaults.scenarios(), new VerificationConfig.Database(
                        true, "sqlite", null, null, "jdbc:sqlite:" + database, Duration.ofSeconds(30)));

        PreparedDatabase prepared = new SqliteDatabaseFixture().prepare(request(explicit));

        assertTrue(prepared.prepared(), prepared.note());
        assertEquals("jdbc:sqlite:" + database, prepared.jdbcUrl());
    }

    @Test
    @DisplayName("the fixture claims SQLite and declines another engine's URL")
    void supportsOnlySqlite() {
        VerificationConfig defaults = VerificationConfig.defaults();
        SqliteDatabaseFixture fixture = new SqliteDatabaseFixture();

        VerificationConfig disabled = new VerificationConfig(defaults.project(), defaults.language(),
                defaults.limits(), defaults.tests(), defaults.mutation(), defaults.propertyTesting(),
                defaults.staticAnalysis(), defaults.security(), defaults.llm(), defaults.report(),
                defaults.sandbox(), defaults.scenarios(),
                new VerificationConfig.Database(false, "sqlite", null, null, null, Duration.ofSeconds(30)));
        assertFalse(fixture.supports(request(disabled)), "a disabled database section claims nothing");

        VerificationConfig postgres = new VerificationConfig(defaults.project(), defaults.language(),
                defaults.limits(), defaults.tests(), defaults.mutation(), defaults.propertyTesting(),
                defaults.staticAnalysis(), defaults.security(), defaults.llm(), defaults.report(),
                defaults.sandbox(), defaults.scenarios(), new VerificationConfig.Database(
                        true, "sqlite", null, null, "jdbc:postgresql://localhost/ledger", Duration.ofSeconds(30)));
        assertFalse(fixture.supports(request(postgres)),
                "a PostgreSQL URL belongs to whatever fixture speaks for PostgreSQL");
    }

    private static int amountOf(PreparedDatabase prepared, int id) throws Exception {
        try (Connection connection = DriverManager.getConnection(prepared.jdbcUrl());
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT amount FROM ledger WHERE id = " + id)) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }
}
