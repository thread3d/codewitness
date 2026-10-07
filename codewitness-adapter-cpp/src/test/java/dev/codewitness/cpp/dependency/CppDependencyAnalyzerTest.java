package dev.codewitness.cpp.dependency;

import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.PackageCoordinate;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.cpp.CppTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static dev.codewitness.cpp.CppTestContexts.LIBRARY_CMAKE;
import static dev.codewitness.cpp.CppTestContexts.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the C and C++ dependency inventory.
 *
 * <p>The inventory's whole job is to be honest about what it knows: a Conan lock file states the resolved
 * version and is queried, a vcpkg manifest states a floor and is not, and a git tag names a commit rather
 * than a release. Every one of those distinctions is asserted here, because an inventory that guessed would
 * put a version in front of an advisory service that the build never used.
 */
class CppDependencyAnalyzerTest {

    @Test
    @DisplayName("a Conan lock file gives the resolved version of every package it lists")
    void readsAConanLock() {
        String lock = """
                {
                  "version": "0.5",
                  "requires": [
                    "zlib/1.3.1#f2f3c1d2b7e1a4c5d6e7f8a9b0c1d2e3%1700000000.123",
                    "fmt/10.2.1",
                    "boost/1.84.0#abcdef"
                  ],
                  "build_requires": ["cmake/3.29.6"],
                  "python_requires": []
                }
                """;

        Map<String, String> versions = CppDependencyAnalyzer.conanLockVersions(lock);

        assertEquals(Map.of("zlib", "1.3.1", "fmt", "10.2.1", "boost", "1.84.0", "cmake", "3.29.6"), versions);
    }

    @Test
    @DisplayName("a lock file that is not one is not an empty package graph")
    void rejectsUnreadableLockFiles() {
        assertEquals(Map.of(), CppDependencyAnalyzer.conanLockVersions("not json at all"));
        assertEquals(Map.of(), CppDependencyAnalyzer.conanLockVersions(null));
        assertEquals(Map.of(), CppDependencyAnalyzer.conanLockVersions("{}"));
        assertEquals(Map.of(), CppDependencyAnalyzer.conanLockVersions("{\"requires\": [\"noversion\"]}"),
                "a reference with no version is skipped rather than reported under an invented one");
    }

    @Test
    @DisplayName("a vcpkg manifest states a floor, so its packages are reported without a version")
    void readsAVcpkgManifest() {
        String manifest = """
                {
                  "name": "ledger",
                  "version": "1.0.0",
                  "builtin-baseline": "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2",
                  "dependencies": [
                    "fmt",
                    { "name": "catch2", "version>=": "3.4.0" }
                  ]
                }
                """;

        List<String> baseline = CppDependencyAnalyzer.vcpkgBaseline(manifest);
        assertEquals(List.of("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"), baseline);

        List<PackageCoordinate> dependencies =
                CppDependencyAnalyzer.vcpkgDependencies(manifest, Map.of("builtin-baseline", baseline.getFirst()));

        assertEquals(2, dependencies.size());
        assertEquals("fmt", dependencies.getFirst().name());
        assertEquals("", dependencies.getFirst().version(),
                "a vcpkg manifest declares a floor, not the version the install selected");
        assertFalse(dependencies.getFirst().resolved());
        assertTrue(dependencies.get(1).display().contains(">=3.4.0"), dependencies.get(1).display());
        assertEquals(PackageCoordinate.CONAN_CENTER, dependencies.getFirst().ecosystem());
    }

    @Test
    @DisplayName("a FetchContent declaration is a git tag, which is not a published version")
    void readsFetchContent() {
        String cmake = """
                include(FetchContent)
                FetchContent_Declare(
                    googletest
                    GIT_REPOSITORY https://github.com/google/googletest.git
                    GIT_TAG v1.14.0
                )
                FetchContent_Declare(fmt URL https://example.invalid/fmt-10.2.1.tar.gz)
                # FetchContent_Declare(commented GIT_TAG v9.9.9)
                """;

        List<PackageCoordinate> dependencies = CppDependencyAnalyzer.fetchContentDependencies(cmake);

        assertEquals(2, dependencies.size(), dependencies.toString());
        assertEquals("googletest", dependencies.getFirst().name());
        assertEquals("", dependencies.getFirst().version(),
                "v1.14.0 names a commit in somebody else's repository, not a released version");
        assertTrue(dependencies.getFirst().display().contains("GIT_TAG v1.14.0"), dependencies.getFirst().display());
        assertEquals("fmt", dependencies.get(1).name());
        assertTrue(dependencies.get(1).display().contains("URL "), dependencies.get(1).display());
    }

    @Test
    @DisplayName("a find_package names a system package, whose version is a property of the machine")
    void readsFindPackage() {
        String cmake = """
                find_package(Threads REQUIRED)
                find_package(ZLIB 1.2.11 REQUIRED)
                find_package(Boost 1.84 COMPONENTS filesystem)
                find_package(Qt6 REQUIRED COMPONENTS Core)
                """;

        List<PackageCoordinate> dependencies = CppDependencyAnalyzer.findPackageDependencies(cmake);

        assertEquals(4, dependencies.size());
        assertEquals("Threads", dependencies.getFirst().name());
        assertEquals("", dependencies.getFirst().version());
        assertEquals("find_package(Threads)", dependencies.getFirst().display());
        assertEquals("find_package(ZLIB 1.2.11)", dependencies.get(1).display());
        assertEquals("find_package(Boost 1.84)", dependencies.get(2).display());
        assertEquals("find_package(Qt6)", dependencies.get(3).display(),
                "REQUIRED and COMPONENTS are keywords, not versions");
    }

    @Test
    @DisplayName("the note says what was read and what could not be asked about")
    void describesWhatTheInventoryCovers() {
        String note = CppDependencyAnalyzer.inventoryNote(3, List.of("fmt (a vcpkg floor)", "zlib (a system package)"));

        assertTrue(note.contains("inventory only"), note);
        assertTrue(note.contains("3 files"), note);
        assertTrue(note.contains("2 packages have no readable version"), note);
        assertTrue(note.contains("advisory scan"), note);
    }

    @Test
    @DisplayName("a tree with nothing to read says so instead of reporting an empty inventory")
    void reportsNothingToRead(@TempDir Path project, @TempDir Path run) throws IOException {
        write(project, "src/ledger.cpp", "int ledger() { return 0; }\n");
        VerificationContext context = CppTestContexts.context(project, run);

        DependencyReport report = new CppDependencyAnalyzer().analyseDependencies(context);

        assertFalse(report.analysed());
        assertTrue(report.note().contains("no CMakeLists.txt"), report.note());
        assertEquals(List.of(), new CppDependencyAnalyzer().dependencyCommand(context));
    }

    @Test
    @DisplayName("a lock file, a manifest and a CMake file are all read, and their versions kept apart")
    void inventoriesAWholeProject(@TempDir Path project, @TempDir Path run) throws IOException {
        write(project, "CMakeLists.txt", LIBRARY_CMAKE + """
                include(FetchContent)
                FetchContent_Declare(googletest GIT_TAG v1.14.0)
                find_package(Threads REQUIRED)
                """);
        write(project, "src/ledger.cpp", "int ledger() { return 0; }\n");
        write(project, "conan.lock", """
                { "version": "0.5", "requires": ["zlib/1.3.1#abc%1"] }
                """);
        write(project, "vcpkg.json", """
                { "dependencies": ["fmt"] }
                """);
        VerificationContext context = CppTestContexts.context(project, run);

        DependencyReport report = new CppDependencyAnalyzer().analyseDependencies(context);

        assertTrue(report.analysed(), report.note());
        List<String> names = report.coordinates().stream().map(PackageCoordinate::name).toList();
        assertTrue(names.contains("zlib"), names.toString());
        assertTrue(names.contains("fmt"), names.toString());
        assertTrue(names.contains("googletest"), names.toString());
        assertTrue(names.contains("Threads"), names.toString());
        assertEquals("1.3.1", report.coordinates().stream()
                .filter(coordinate -> coordinate.name().equals("zlib")).findFirst().orElseThrow().version(),
                "only the lock file's version is a resolved one");
        assertTrue(report.note().contains("no readable version"), report.note());
        assertEquals("cpp-packages", report.tool());
    }

    @Test
    @DisplayName("a lock file beside the top-level CMake file is found, wherever that file is")
    void findsManifestsBesideTheProjectFile(@TempDir Path project, @TempDir Path run) throws IOException {
        write(project, "build-src/CMakeLists.txt", LIBRARY_CMAKE);
        write(project, "build-src/src/ledger.cpp", "int ledger() { return 0; }\n");
        write(project, "build-src/conan.lock", """
                { "requires": ["openssl/3.2.1"] }
                """);
        VerificationContext context = CppTestContexts.context(project, run);

        DependencyReport report = new CppDependencyAnalyzer().analyseDependencies(context);

        assertTrue(report.analysed(), report.note());
        assertTrue(report.coordinates().stream().anyMatch(coordinate -> coordinate.name().equals("openssl")),
                report.coordinates().toString());
    }

    @Test
    @DisplayName("a lock file that cannot be parsed is not an empty graph")
    void anUnreadableLockFileIsNotAnEmptyGraph(@TempDir Path project, @TempDir Path run) throws IOException {
        write(project, "conan.lock", "{ this is not json }\n");
        VerificationContext context = CppTestContexts.context(project, run);

        DependencyReport report = new CppDependencyAnalyzer().analyseDependencies(context);

        assertFalse(report.analysed(), report.note());
        assertTrue(report.note().contains("could not be read"), report.note());
    }
}
