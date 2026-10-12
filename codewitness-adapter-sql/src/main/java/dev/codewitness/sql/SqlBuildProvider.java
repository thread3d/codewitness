package dev.codewitness.sql;

import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.support.database.SqlDatabase;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Loads the project's schema into a fresh database.
 *
 * <p>This is the language's first failure. SQL has no compiler, so the equivalent of compiling is
 * applying every schema and migration script, in order, to an empty database: a script the database
 * rejects - a syntax error, a table that does not exist yet, a foreign key to a table declared later -
 * is a project problem reported as a failed build, which the engine maps to {@code BLOCKED}, rather
 * than a defect in the code.
 *
 * <p>The database is created inside the run's artefact directory, never in the project tree, so a
 * verification run leaves the project as it found it and the schema it loaded is kept beside the
 * evidence for a reader to inspect.
 */
public final class SqlBuildProvider implements VerificationTools.Compiler {

    @Override
    public String id() {
        return "sql-build";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.SQL);
    }

    @Override
    public BuildResult build(VerificationContext context) {
        List<Path> scripts = SqlProjectDetector.schemaFiles(context.projectRoot());
        if (scripts.isEmpty()) {
            return BuildResult.notAttempted(
                    "no schema or migration script was found, so there is nothing to load");
        }
        Path databaseFile = databaseFile(context, "build.db");
        Instant start = Instant.now();
        SqlDatabase.Load load = SqlDatabase.load(databaseFile, scripts);
        List<CompilerDiagnostic> diagnostics = load.loaded()
                ? List.of()
                : List.of(CompilerDiagnostic.error(relative(context, scripts, load.failure().script()), null,
                        load.failure().describe()));
        context.emit("build", "loaded " + load.statements() + " statement(s) from " + scripts.size()
                + " script(s); " + (load.loaded() ? "the schema applies" : "the schema does not apply"));
        return BuildResult.builder()
                .attempted(true)
                .success(load.loaded())
                .exitCode(load.loaded() ? 0 : 1)
                .duration(Duration.between(start, Instant.now()))
                .command(List.of())
                .output(String.join(System.lineSeparator(), load.log()))
                .diagnostics(diagnostics)
                .artifacts(List.of(databaseFile))
                .build();
    }

    /** @return a database file for one phase of the run, inside the artefact directory. */
    static Path databaseFile(VerificationContext context, String name) {
        return context.artifacts().artifactsDirectory().resolve("sql").resolve(name);
    }

    /** @return the script's path relative to the project root, or its name when it cannot be matched. */
    private static String relative(VerificationContext context, List<Path> scripts, String name) {
        return scripts.stream()
                .filter(script -> script.getFileName().toString().equals(name))
                .findFirst()
                .map(script -> {
                    Path root = context.projectRoot();
                    return script.startsWith(root) ? root.relativize(script).toString() : script.toString();
                })
                .orElse(name);
    }
}
