package dev.codewitness.core.spi;

import dev.codewitness.core.config.VerificationConfig;
import dev.codewitness.core.io.ArtifactStore;

import java.nio.file.Path;

/**
 * Prepares an ephemeral database so that a run's tests and scenarios exercise real SQL.
 *
 * <p>This is the tool-level extension point for data logic, alongside {@link VerificationTools}. The
 * engine asks every registered fixture whether it can serve the run's {@code database:} configuration,
 * prepares the first one that claims it, and puts the result on the {@link VerificationContext} and into
 * every sandboxed process's environment. It never names an engine or a driver itself.
 *
 * <p>A fixture's resources belong to the run directory. The engine does not tear them down, because a
 * run directory is already the lifecycle boundary and a counterexample that names a database is only
 * replayable while that database still exists; a fixture whose resource cannot live as a file - a server
 * it started, say - is responsible for ending it when the run's directory is cleaned up.
 *
 * <p>Preparation is infrastructure, not evidence about the code: a schema that cannot be applied comes
 * back as {@link PreparedDatabase#notPrepared} with the reason, and the run says the database was
 * unavailable. It never invents a passing experiment.
 */
public interface DatabaseFixture {

    /**
     * Environment variable every prepared database is exported under, so that one variable reaches a
     * project's tests whatever language they are written in.
     */
    String URL_VARIABLE = "CODEWITNESS_DATABASE_URL";

    /** @return unique fixture identifier, for example {@code sqlite}. */
    String id();

    /**
     * @param request what the run asked for and where it may write
     * @return {@code true} when this fixture can serve that request
     */
    boolean supports(Request request);

    /**
     * Prepares the database. A fixture that cannot must return
     * {@link PreparedDatabase#notPrepared(String, String)} rather than throwing: an unavailable tool is a
     * reported limitation, not a crash.
     *
     * @param request what the run asked for and where it may write
     * @return the prepared database, or an honest explanation of why there is none
     */
    PreparedDatabase prepare(Request request);

    /**
     * What a fixture is given to work with.
     *
     * <p>It is deliberately not a {@link VerificationContext}: the engine prepares the database before it
     * creates the sandbox, so that the fixture's environment can be part of the sandbox's baseline for
     * every process the run starts, and a context cannot exist that early.
     *
     * @param config      effective configuration, including the {@code database:} section
     * @param projectRoot root of the project inside the run's workspace
     * @param runDirectory run directory the fixture may keep its files in
     * @param artifacts   artifact store, so a preparation log can be kept with the run's evidence
     */
    record Request(
            VerificationConfig config,
            Path projectRoot,
            Path runDirectory,
            ArtifactStore artifacts) {
    }
}
