package dev.codewitness.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the reactor root from wherever Maven happened to start, for the tests that assert on the repository
 * itself rather than on a module.
 *
 * <p>Surefire runs with the working directory set to the module directory, which differs between a full reactor
 * build and {@code ./mvnw -pl codewitness-cli test}, so a test that reads a repository file cannot use a path
 * relative to the working directory. Walking up to the POM that aggregates the modules works from either.
 */
final class ReactorRoot {

    private ReactorRoot() {
    }

    /** The directory holding the aggregator POM, with {@code codewitness-cli} and {@code .github} beneath it. */
    static Path find() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("pom.xml");
            if (Files.isRegularFile(candidate) && isReactorRoot(candidate)) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("could not locate the reactor root above " + Path.of("").toAbsolutePath());
    }

    /**
     * The reactor root is the only POM that aggregates the modules, which distinguishes it from a module
     * POM that merely names {@code codewitness-parent} as its parent.
     */
    private static boolean isReactorRoot(Path pom) {
        try {
            return Files.readString(pom, StandardCharsets.UTF_8).contains("<module>codewitness-core</module>");
        } catch (IOException e) {
            return false;
        }
    }
}
