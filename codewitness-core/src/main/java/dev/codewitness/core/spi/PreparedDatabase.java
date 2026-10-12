package dev.codewitness.core.spi;

import java.nio.file.Path;
import java.util.Map;

/**
 * The outcome of preparing an ephemeral database for a run.
 *
 * <p>It is on the {@link VerificationContext} so that any provider or strategy can reach the database
 * without knowing which fixture produced it, and its environment is merged into every child process the
 * sandbox starts, so a test or a generated scenario in any language reaches the same database through
 * {@link DatabaseFixture#URL_VARIABLE} rather than through a value the engine had to thread into that
 * language's tool.
 *
 * <p>The distinction between {@code prepared} and {@code notPrepared} is the one this project draws
 * everywhere: a fixture that could not prepare a database says so in {@code note}, and a run that asked
 * for one is reported as having been unable to use it rather than proceeding as though the code had been
 * exercised against a database. An explicit {@code url} a project supplies itself also comes back
 * {@code prepared}, because from the run's point of view a reachable database is what was asked for;
 * {@code description} says which of the two happened.
 *
 * @param prepared     whether a database is available to the run
 * @param id           the fixture that produced this, or {@code none}
 * @param jdbcUrl      connection URL a JDBC client can use, or {@code null} when none is available
 * @param environment  variables to add to every sandboxed process, such as
 *                     {@link DatabaseFixture#URL_VARIABLE}
 * @param description  human readable summary for the report
 * @param note         why no database is available, or {@code null} when one is
 */
public record PreparedDatabase(
        boolean prepared,
        String id,
        String jdbcUrl,
        Map<String, String> environment,
        String description,
        String note) {

    public PreparedDatabase {
        id = id == null || id.isBlank() ? "none" : id;
        environment = Map.copyOf(environment == null ? Map.of() : environment);
        description = description == null ? "" : description;
    }

    /** @return the outcome of a run that prepared no database, with the reason. */
    public static PreparedDatabase none(String note) {
        return new PreparedDatabase(false, "none", null, Map.of(), "no database was prepared", note);
    }

    /** @return the outcome of a fixture that was asked to prepare a database and could not. */
    public static PreparedDatabase notPrepared(String id, String note) {
        return new PreparedDatabase(false, id, null, Map.of(), "no database was prepared", note);
    }

    /** @return an available database, described for the report. */
    public static PreparedDatabase prepared(
            String id, String jdbcUrl, Map<String, String> environment, String description) {
        return new PreparedDatabase(true, id, jdbcUrl, environment, description, null);
    }

    /** @return the reason no database is available, or an empty string when one is. */
    public String limitation() {
        if (prepared) {
            return "";
        }
        return "Database fixture \"" + id + "\" prepared no database"
                + (note == null || note.isBlank() ? "." : ": " + note);
    }
}
