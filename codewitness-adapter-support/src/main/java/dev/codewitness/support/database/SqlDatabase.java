package dev.codewitness.support.database;

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
import java.util.List;
import java.util.Locale;

/**
 * Creates a SQLite database from a set of {@code .sql} scripts.
 *
 * <p>Shared by the database fixture, which prepares the database a run's tests connect to, and by the
 * SQL adapter, whose build is the same act: applying the declared schema to a fresh database and calling
 * a script the database rejects a failed build. Both need the identical three details, so they live here
 * once - the URL spelling, clearing a database that a previous run left behind, and executing one
 * statement at a time because the driver runs only the first statement of a multi-statement string.
 */
public final class SqlDatabase {

    private SqlDatabase() {
    }

    /** The result of applying a set of scripts. */
    public record Load(boolean loaded, int statements, List<String> log, Failure failure) {

        public static Load failure(Failure failure) {
            return new Load(false, 0, List.of(), failure);
        }
    }

    /**
     * The statement a script failed on.
     *
     * @param script         the file's name, for a message a reader can act on
     * @param statementIndex 1-based position of the statement in that file
     * @param statement      the statement, shortened
     * @param message        what the database said
     */
    public record Failure(String script, int statementIndex, String statement, String message) {

        /** @return a message naming the script and the statement, without quoting the whole statement twice. */
        public String describe() {
            return "applying " + script + " failed at statement " + statementIndex + ": " + message
                    + " (statement: " + statement + ")";
        }
    }

    /** @return the JDBC URL for a database file. */
    public static String url(Path databaseFile) {
        return "jdbc:sqlite:" + databaseFile.toAbsolutePath();
    }

    /**
     * Applies every script to the database file, in the order given.
     *
     * <p>The file is deleted first, together with any journal or write-ahead log beside it: a stale
     * journal can otherwise replay a statement a previous run rolled back into the fresh database. A
     * script that fails stops the load, because a partially applied schema is not the schema under test.
     *
     * @return what was applied, or the first failure
     */
    public static Load load(Path databaseFile, List<Path> scripts) {
        try {
            Files.createDirectories(databaseFile.getParent());
        } catch (IOException e) {
            return Load.failure(new Failure(databaseFile.getFileName().toString(), 0, "", 
                    "the database directory could not be created: " + e.getMessage()));
        }
        deleteDatabase(databaseFile);

        List<String> log = new ArrayList<>();
        int executed = 0;
        try (Connection connection = DriverManager.getConnection(url(databaseFile))) {
            for (Path script : scripts) {
                String content = read(script);
                if (content == null) {
                    return Load.failure(new Failure(script.getFileName().toString(), 0, "",
                            "the script could not be read"));
                }
                List<String> statements = SqlScript.statements(content);
                for (int index = 0; index < statements.size(); index++) {
                    String statement = statements.get(index);
                    try (Statement jdbc = connection.createStatement()) {
                        jdbc.execute(statement);
                        executed++;
                    } catch (SQLException e) {
                        Failure failure =
                                new Failure(script.getFileName().toString(), index + 1, summarize(statement),
                                        e.getMessage());
                        log.add("FAILED " + failure.describe());
                        return new Load(false, executed, List.copyOf(log), failure);
                    }
                }
                log.add("applied " + statements.size() + " statement(s) from " + script.getFileName());
            }
        } catch (SQLException e) {
            return Load.failure(new Failure(databaseFile.getFileName().toString(), 0, "",
                    "the database could not be opened: " + e.getMessage()));
        }
        return new Load(true, executed, List.copyOf(log), null);
    }

    /** @return the number of user tables in the database, or {@code -1} when it cannot be asked. */
    public static int tableCount(String jdbcUrl) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")) {
            return rows.next() ? rows.getInt(1) : 0;
        } catch (SQLException _) {
            return -1;
        }
    }

    /** Deletes a database file and whatever journal or write-ahead log sits beside it. */
    public static void deleteDatabase(Path databaseFile) {
        deleteQuietly(databaseFile);
        String name = databaseFile.getFileName().toString();
        deleteQuietly(databaseFile.resolveSibling(name + "-journal"));
        deleteQuietly(databaseFile.resolveSibling(name + "-wal"));
        deleteQuietly(databaseFile.resolveSibling(name + "-shm"));
    }

    private static String read(Path script) {
        try {
            return Files.readString(script, StandardCharsets.UTF_8);
        } catch (IOException _) {
            return null;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException _) {
            // A leftover journal beside a freshly created database is harmless.
        }
    }

    /** @return the statement, shortened, so an error message stays readable. */
    public static String summarize(String statement) {
        String oneLine = statement.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 157) + "...";
    }

    /** @return the lower-case extension of a path, without the dot. */
    static String extensionOf(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }
}
