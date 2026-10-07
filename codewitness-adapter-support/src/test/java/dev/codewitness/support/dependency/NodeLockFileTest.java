package dev.codewitness.support.dependency;

import dev.codewitness.core.model.PackageCoordinate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading resolved npm versions out of a package-lock.json, in every lock file layout. */
class NodeLockFileTest {

    @Test
    @DisplayName("a lockfileVersion 3 packages map yields the resolved version of every installed package")
    void readsTheFlatPackagesMap() {
        String lock = """
                {
                  "name": "sample-app",
                  "lockfileVersion": 3,
                  "packages": {
                    "": {
                      "name": "sample-app",
                      "dependencies": { "left-pad": "^1.3.0" }
                    },
                    "node_modules/left-pad": {
                      "version": "1.3.0"
                    },
                    "node_modules/big.js": {
                      "version": "6.2.1"
                    }
                  }
                }
                """;

        List<PackageCoordinate> coordinates = NodeLockFile.parse(lock);

        assertEquals(2, coordinates.size(), "the project's own entry is not a dependency: " + coordinates);
        assertEquals("npm", coordinates.getFirst().ecosystem());
        assertEquals("left-pad", coordinates.getFirst().name());
        assertEquals("1.3.0", coordinates.getFirst().version());
        assertEquals("big.js", coordinates.get(1).name());
        assertEquals("6.2.1", coordinates.get(1).version());
    }

    @Test
    @DisplayName("a nested install path is read as the package it installs, not as the path")
    void readsNestedInstallPaths() {
        String lock = """
                {
                  "lockfileVersion": 2,
                  "packages": {
                    "node_modules/a": { "version": "1.0.0" },
                    "node_modules/a/node_modules/b": { "version": "2.0.0" }
                  }
                }
                """;

        List<PackageCoordinate> coordinates = NodeLockFile.parse(lock);

        assertEquals(List.of("a@1.0.0", "b@2.0.0"),
                coordinates.stream().map(PackageCoordinate::display).toList());
    }

    @Test
    @DisplayName("a lockfileVersion 1 tree is walked, including a conflicting nested copy")
    void readsALegacyTree() {
        String lock = """
                {
                  "lockfileVersion": 1,
                  "dependencies": {
                    "a": {
                      "version": "1.0.0",
                      "dependencies": {
                        "b": { "version": "2.0.0" }
                      }
                    },
                    "b": { "version": "1.0.0" }
                  }
                }
                """;

        List<PackageCoordinate> coordinates = NodeLockFile.parse(lock);

        // Both resolved copies of b are real: one belongs to a, one to the root.
        assertEquals(List.of("a@1.0.0", "b@2.0.0", "b@1.0.0"),
                coordinates.stream().map(PackageCoordinate::display).toList());
    }

    @Test
    @DisplayName("a workspace link is not a published package and is left out")
    void skipsWorkspaceLinks() {
        String lock = """
                {
                  "lockfileVersion": 3,
                  "packages": {
                    "node_modules/local-workspace": { "link": true, "resolved": "packages/local" },
                    "node_modules/real": { "version": "1.0.0" }
                  }
                }
                """;

        assertEquals(List.of("real@1.0.0"),
                NodeLockFile.parse(lock).stream().map(PackageCoordinate::display).toList());
    }

    @Test
    @DisplayName("an unparseable lock file yields nothing rather than a guess")
    void anUnreadableLockFileIsNotAnEmptyInstall() {
        assertTrue(NodeLockFile.parse("not json at all").isEmpty());
        assertTrue(NodeLockFile.parse("").isEmpty());
        assertTrue(NodeLockFile.parse(null).isEmpty());
        assertTrue(NodeLockFile.parse("{\"lockfileVersion\": 3}").isEmpty());
    }
}
