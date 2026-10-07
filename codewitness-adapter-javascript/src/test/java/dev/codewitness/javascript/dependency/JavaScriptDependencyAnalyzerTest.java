package dev.codewitness.javascript.dependency;

import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.javascript.JavaScriptTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Inventorying of a package.json's declared dependencies. */
class JavaScriptDependencyAnalyzerTest {

    @Test
    @DisplayName("dependency tables are inventoried and unrelated keys are ignored")
    void parsesDependencyTables() {
        String manifest = """
                {
                  "name": "sample-app",
                  "version": "1.0.0",
                  "scripts": {
                    "test": "node --test",
                    "dependencies": "not a table"
                  },
                  "dependencies": {
                    "left-pad": "^1.3.0",
                    "big.js": "6.2.1"
                  },
                  "devDependencies": {
                    "vitest": "2.0.0"
                  },
                  "optionalDependencies": {
                    "fsevents": "2.3.3"
                  },
                  "engines": {
                    "node": ">=18.17"
                  }
                }
                """;

        List<String> dependencies = JavaScriptDependencyAnalyzer.parse(manifest);

        assertEquals(List.of("left-pad", "big.js", "vitest", "fsevents"), dependencies);
        assertFalse(dependencies.contains("name"));
        assertFalse(dependencies.contains("test"));
        assertFalse(dependencies.contains("node"));
    }

    @Test
    @DisplayName("a manifest with no dependency table declares nothing and is still parsed")
    void handlesEmptyManifest() {
        assertTrue(JavaScriptDependencyAnalyzer.parse("{\"name\": \"app\"}").isEmpty());
        assertTrue(JavaScriptDependencyAnalyzer.parse("").isEmpty());
        assertTrue(JavaScriptDependencyAnalyzer.parse(null).isEmpty());
    }

    @Test
    @DisplayName("package-lock.json is preferred, and every package it resolves carries its version")
    void readsResolvedVersionsFromTheLockFile(@TempDir Path root, @TempDir Path runDirectory)
            throws IOException {
        JavaScriptTestContexts.write(root, "package.json", """
                {
                  "name": "sample-app",
                  "dependencies": { "left-pad": "^1.3.0" }
                }
                """);
        JavaScriptTestContexts.write(root, "package-lock.json", """
                {
                  "name": "sample-app",
                  "lockfileVersion": 3,
                  "packages": {
                    "": { "name": "sample-app" },
                    "node_modules/left-pad": { "version": "1.3.0" },
                    "node_modules/big.js": { "version": "6.2.1" }
                  }
                }
                """);
        VerificationContext context = JavaScriptTestContexts.context(root, runDirectory);

        DependencyReport report = new JavaScriptDependencyAnalyzer().analyseDependencies(context);

        assertEquals("package-lock", report.tool());
        assertEquals(2, report.totalDependencies());
        assertEquals(List.of("left-pad@1.3.0", "big.js@6.2.1"), report.directDependencies());
        assertEquals("npm", report.coordinates().getFirst().ecosystem());
        assertTrue(report.coordinates().stream().allMatch(coordinate -> coordinate.resolved()),
                "every coordinate from a lock file must be queryable: " + report.coordinates());
    }

    @Test
    @DisplayName("without a lock file the declared names are inventoried but are not queryable")
    void declaredNamesCarryNoResolvedVersion(@TempDir Path root, @TempDir Path runDirectory)
            throws IOException {
        JavaScriptTestContexts.write(root, "package.json", """
                {
                  "name": "sample-app",
                  "dependencies": { "left-pad": "^1.3.0" }
                }
                """);
        VerificationContext context = JavaScriptTestContexts.context(root, runDirectory);

        DependencyReport report = new JavaScriptDependencyAnalyzer().analyseDependencies(context);

        assertEquals("package-json", report.tool());
        assertEquals(List.of("left-pad"), report.directDependencies());
        assertTrue(report.coordinates().stream().noneMatch(coordinate -> coordinate.resolved()),
                "a version range is not a resolved version: " + report.coordinates());
        assertTrue(report.note().contains("no lock file"), report.note());
    }
}
