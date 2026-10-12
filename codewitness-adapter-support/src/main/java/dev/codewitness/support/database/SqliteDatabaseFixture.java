package dev.codewitness.support.database;

import dev.codewitness.core.spi.DatabaseFixture;
import dev.codewitness.core.spi.PreparedDatabase;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Prepares a SQLite database for a run.
 *
 * <p>SQLite is the first fixture for a practical reason rather than a preference: the driver is already a
 * dependency of the run store, and a database that is one file needs no server, no container and no
 * network, so a run can exercise real SQL without asking the machine for anything. A server-backed engine
 * is another {@link DatabaseFixture}, not a change here.
 *
 * <p>The declared schema is the source of truth. A {@code schema:} path is either one {@code .sql} file or
 * a directory of them applied in file-name order, which is what makes a migration directory work without
 * this class knowing the migration tool that produced it: Flyway's {@code V1__...}, Rails' timestamps and
 * a hand-rolled numbering all sort correctly by name. A {@code seed:} file is applied afterwards.
 *
 * <p>The database is created under the run directory, so it lives exactly as long as the run's evidence
 * does and a counterexample that names a table can be replayed against the schema that was actually
 * loaded.
 */
public final class SqliteDatabaseFixture implements DatabaseFixture {

    /** Where the prepared database and its preparation log are kept inside the run directory. */
    private static final String DIRECTORY = "database";

    /** The fixture's identifier, as a constant because the static preparation path reports it too. */
    private static final String ID = "sqlite";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean supports(Request request) {
        var database = request.config().database();
        if (!database.enabled()) {
            return false;
        }
        String url = database.url();
        if (url != null && !url.isBlank()) {
            // An explicit URL is only this fixture's business when it is actually SQLite; a PostgreSQL URL
            // belongs to whatever fixture speaks for PostgreSQL.
            return url.startsWith("jdbc:sqlite:");
        }
        return "sqlite".equalsIgnoreCase(database.engine());
    }

    @Override
    public PreparedDatabase prepare(Request request) {
        return prepareWith(request);
    }

    private static PreparedDatabase prepareWith(Request request) {
        var database = request.config().database();
        List<String> log = new ArrayList<>();
        String explicit = database.url();
        if (explicit != null && !explicit.isBlank()) {
            log.add("using the URL declared by the project: " + explicit);
            return connectAndDescribe(request, explicit, log);
        }

        Path schema = resolve(request.projectRoot(), database.schema());
        Path seed = resolve(request.projectRoot(), database.seed());
        if (database.schema() != null && (schema == null || !Files.exists(schema))) {
            return PreparedDatabase.notPrepared(ID,
                    "the declared schema \"" + database.schema() + "\" does not exist under " + request.projectRoot());
        }
        if (database.seed() != null && (seed == null || !Files.isRegularFile(seed))) {
            return PreparedDatabase.notPrepared(ID,
                    "the declared seed \"" + database.seed() + "\" is not a file under " + request.projectRoot());
        }

        List<Path> scripts;
        try {
            scripts = schemaScripts(schema);
        } catch (IOException e) {
            return PreparedDatabase.notPrepared(ID, "the schema could not be read: " + e.getMessage());
        }
        if (database.schema() != null && scripts.isEmpty()) {
            return PreparedDatabase.notPrepared(ID,
                    "the declared schema \"" + database.schema() + "\" holds no .sql file");
        }

        Path databaseFile = request.runDirectory().resolve(DIRECTORY).resolve("codewitness.db");
        try {
            Files.createDirectories(databaseFile.getParent());
            Files.deleteIfExists(databaseFile);
        } catch (IOException e) {
            return PreparedDatabase.notPrepared(ID, "the database file could not be created: " + e.getMessage());
        }
        // A deleted SQLite file can leave its journal behind; a stale one would silently replay a rolled
        // back statement into the fresh database.
        deleteQuietly(databaseFile.resolveSibling(databaseFile.getFileName() + "-journal"));
        deleteQuietly(databaseFile.resolveSibling(databaseFile.getFileName() + "-wal"));
        deleteQuietly(databaseFile.resolveSibling(databaseFile.getFileName() + "-shm"));

        String url = "jdbc:sqlite:" + databaseFile.toAbsolutePath();
        try (Connection connection = DriverManager.getConnection(url)) {
            for (Path script : scripts) {
                PreparedDatabase failure = applyScript(connection, script, log);
                if (failure != null) {
                    writeLog(request, log);
                    return failure;
                }
            }
            if (seed != null) {
                PreparedDatabase failure = applyScript(connection, seed, log);
                if (failure != null) {
                    writeLog(request, log);
                    return failure;
                }
            }
            int tables = countTables(connection);
            writeLog(request, log);
            String description = "SQLite database at " + databaseFile + " with " + tables + " table(s)"
                    + (scripts.isEmpty() ? " (no schema was declared, so it is empty)"
                            : " loaded from " + scripts.size() + " script(s)");
            return PreparedDatabase.prepared(ID, url, environment(url), description);
        } catch (SQLException e) {
            writeLog(request, log);
            return PreparedDatabase.notPrepared(ID, "the database could not be opened: " + e.getMessage());
        }
    }

    /**
     * @return a failure when one of the script's statements failed, or {@code null} when it all applied
     */
    private static PreparedDatabase applyScript(Connection connection, Path script, List<String> log) {
        String content;
        try {
            content = Files.readString(script, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return PreparedDatabase.notPrepared("sqlite",
                    "the script " + script.getFileName() + " could not be read: " + e.getMessage());
        }
        List<String> statements = SqlScript.statements(content);
        int applied = 0;
        for (String statement : statements) {
            try (Statement jdbc = connection.createStatement()) {
                jdbc.execute(statement);
                applied++;
            } catch (SQLException e) {
                log.add("FAILED in " + script.getFileName() + " after " + applied + " statement(s): "
                        + summarize(statement) + " -> " + e.getMessage());
                // A schema that does not apply is not evidence about the project's behaviour: the database
                // is reported as unavailable, with the failing statement, rather than half-loaded.
                return PreparedDatabase.notPrepared("sqlite", "applying " + script.getFileName() + " failed at "
                        + "statement " + (applied + 1) + " of " + statements.size() + ": " + e.getMessage()
                        + " (statement: " + summarize(statement) + ")");
            }
        }
        log.add("applied " + applied + " statement(s) from " + script.getFileName());
        return null;
    }

    /** @return the statements that make up the schema, in the order they must be applied. */
    private static List<Path> schemaScripts(Path schema) throws IOException {
        if (schema == null) {
            return List.of();
        }
        if (Files.isRegularFile(schema)) {
            return List.of(schema);
        }
        if (!Files.isDirectory(schema)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(schema)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sql"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private static PreparedDatabase connectAndDescribe(Request request, String url, List<String> log) {
        try (Connection connection = DriverManager.getConnection(url)) {
            int tables = countTables(connection);
            writeLog(request, log);
            return PreparedDatabase.prepared(ID, url, environment(url),
                    "SQLite database the project declares at " + url + " with " + tables + " table(s)");
        } catch (SQLException e) {
            return PreparedDatabase.notPrepared(ID,
                    "the declared URL could not be opened: " + e.getMessage());
        }
    }

    private static Map<String, String> environment(String url) {
        return Map.of(URL_VARIABLE, url, "CODEWITNESS_DATABASE_ENGINE", "sqlite");
    }

    private static int countTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private static Path resolve(Path projectRoot, Path declared) {
        if (declared == null) {
            return null;
        }
        return declared.isAbsolute() ? declared : projectRoot.resolve(declared).normalize();
    }

    private static void writeLog(Request request, List<String> log) {
        StringBuilder text = new StringBuilder();
        text.append("SQLite database preparation\n");
        for (String line : log) {
            text.append("  ").append(line).append('\n');
        }
        try {
            request.artifacts().write(DIRECTORY + "/preparation.txt", text.toString());
        } catch (RuntimeException _) {
            // The log is for a reader, not for the verdict: losing it must not fail the preparation.
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException _) {
            // A leftover journal is harmless next to a freshly created database.
        }
    }

    /** @return the statement, shortened, so an error message stays readable. */
    private static String summarize(String statement) {
        String oneLine = statement.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 157) + "...";
    }
}
