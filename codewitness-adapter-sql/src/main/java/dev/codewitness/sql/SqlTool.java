package dev.codewitness.sql;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Locates the SQL tools this adapter can drive from outside the JVM.
 *
 * <p>Unlike every other adapter, SQL does not need a tool to verify: the schema is loaded and the test
 * scripts are executed through the JDBC driver that is already in the build. The one thing a tool is
 * wanted for is the reproduction recipe - {@code sqlite3} can replay a counterexample's script against
 * the database it failed on - and that is optional, so it is resolved rather than required.
 */
final class SqlTool {

    private SqlTool() {
    }

    /** @return the path to an executable on {@code PATH}, or empty when it is not installed. */
    static Optional<String> resolve(String executable) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        for (String directory : path.split(java.io.File.pathSeparator)) {
            if (directory.isBlank()) {
                continue;
            }
            Path candidate = Path.of(directory, executable);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return Optional.of(candidate.toString());
            }
        }
        return Optional.empty();
    }
}
