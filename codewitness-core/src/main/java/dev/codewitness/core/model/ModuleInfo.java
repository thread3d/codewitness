package dev.codewitness.core.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * A single build module of a project. Single-module projects are represented as one module whose
 * path equals the project root.
 *
 * @param name           module name (Maven artifactId, Gradle project name)
 * @param path           absolute path of the module directory
 * @param packaging      packaging or plugin type, for example {@code jar}
 * @param sourceDirs     absolute main source directories (normally {@code src/main/java})
 * @param testSourceDirs absolute test source directories (normally {@code src/test/java})
 * @param javaVersion    declared Java source/target version, or {@code null} when undeclared
 */
public record ModuleInfo(
        String name,
        Path path,
        String packaging,
        List<Path> sourceDirs,
        List<Path> testSourceDirs,
        String javaVersion) {

    public ModuleInfo {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(path, "path");
        path = path.toAbsolutePath().normalize();
        packaging = packaging == null ? "unknown" : packaging;
        sourceDirs = List.copyOf(sourceDirs == null ? List.of() : sourceDirs);
        testSourceDirs = List.copyOf(testSourceDirs == null ? List.of() : testSourceDirs);
    }

    /** @return {@code true} when this module contains at least one test source directory. */
    public boolean hasTests() {
        return !testSourceDirs.isEmpty();
    }
}
