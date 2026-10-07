package dev.codewitness.cpp;

import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.codewitness.cpp.CppTestContexts.LIBRARY_CMAKE;
import static dev.codewitness.cpp.CppTestContexts.TESTING_CMAKE;
import static dev.codewitness.cpp.CppTestContexts.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for CMake project detection.
 *
 * <p>Detection is what every later phase is scheduled from: a tree the adapter does not claim is reported
 * as present and unverified rather than built, so the rules for claiming one - a CMakeLists.txt that
 * declares a project, and at least one C or C++ source - are asserted directly.
 */
class CppProjectDetectorTest {

    @Test
    @DisplayName("a CMake project with C++ sources is claimed, an empty directory is not")
    void claimsOnlyRealProjects(@TempDir Path project) throws IOException {
        assertFalse(CppProjectDetector.looksLikeCppProject(project), "an empty directory is not claimed");
        assertFalse(CppProjectDetector.looksLikeCppProject(null));

        write(project, "CMakeLists.txt", LIBRARY_CMAKE);
        assertFalse(CppProjectDetector.looksLikeCppProject(project),
                "a CMake file with no source file is not a project to verify");

        write(project, "src/ledger.cpp", "int main() { return 0; }\n");
        assertTrue(CppProjectDetector.looksLikeCppProject(project));
    }

    @Test
    @DisplayName("a tree of C++ sources with no CMake project is not claimed")
    void sourcesWithoutCMakeAreNotClaimed(@TempDir Path project) throws IOException {
        write(project, "Makefile", "all:\n\tc++ -o ledger src/ledger.cpp\n");
        write(project, "src/ledger.cpp", "int main() { return 0; }\n");

        assertFalse(CppProjectDetector.looksLikeCppProject(project),
                "the adapter drives CMake; a hand-written Makefile is the detector's to report, not this "
                        + "adapter's to guess at");
    }

    @Test
    @DisplayName("inspection reports the language, the build system, the targets and the standard")
    void inspectsAProject(@TempDir Path project) throws IOException {
        write(project, "CMakeLists.txt", TESTING_CMAKE);
        write(project, "src/withdrawal_ledger.cpp", "int ledger() { return 0; }\n");
        write(project, "tests/withdrawal_ledger_test.cpp", "int main() { return 0; }\n");

        ProjectInfo info = CppProjectDetector.inspect(ProjectRef.of(project));

        assertEquals(LanguageId.CPP, info.language());
        assertEquals(BuildSystemId.CMAKE, info.buildSystem());
        assertEquals(project.toAbsolutePath().normalize(), info.buildRoot());
        assertEquals("17", info.attributes().get(CppProjectDetector.ATTR_CPP_STANDARD));
        assertEquals("ledger", info.attributes().get(CppProjectDetector.ATTR_LIBRARY_TARGETS));
        assertEquals("withdrawal_ledger_test", info.attributes().get(CppProjectDetector.ATTR_TEST_TARGETS));
        assertEquals("1", info.attributes().get(CppProjectDetector.ATTR_CTEST_TESTS));
        assertEquals("ctest", info.attributes().get(CppProjectDetector.ATTR_TEST_FRAMEWORK));
        assertEquals("true", info.attributes().get(CppProjectDetector.ATTR_HAS_TESTS));
        assertTrue(info.testFrameworks().contains("ctest"));
        assertNull(info.attributes().get(CppProjectDetector.ATTR_DETECTION_WARNING));

        // The test directory is a test root and the source directory is a main root: the requirement mapper
        // must not read a test as the code it tests.
        assertEquals(List.of(project.resolve("src").toAbsolutePath().normalize()), info.sourceDirs());
        assertEquals(List.of(project.resolve("tests").toAbsolutePath().normalize()), info.testSourceDirs());
    }

    @Test
    @DisplayName("sources with no CMake project are reported with a warning rather than silently unbuilt")
    void reportsUnbuildableSources(@TempDir Path project) throws IOException {
        write(project, "src/ledger.cpp", "int main() { return 0; }\n");

        ProjectInfo info = CppProjectDetector.inspect(ProjectRef.of(project));

        assertEquals(BuildSystemId.NONE, info.buildSystem());
        assertNull(CppProjectDetector.buildRoot(project),
                "no CMakeLists.txt means there is no build root, whatever the project info defaults to");
        assertTrue(info.attributes().getOrDefault(CppProjectDetector.ATTR_DETECTION_WARNING, "")
                .contains("no CMakeLists.txt"), info.attributes().toString());
        assertEquals(1, info.modules().size(), "the sources still get a module so static analysis has a root");
        assertFalse(info.sourceExtensions().isEmpty());
    }

    @Test
    @DisplayName("the shallowest CMakeLists.txt that declares a project is the root of the build")
    void choosesTheTopLevelCMakeFile(@TempDir Path project) throws IOException {
        write(project, "CMakeLists.txt", TESTING_CMAKE);
        write(project, "src/ledger.cpp", "int ledger() { return 0; }\n");
        write(project, "modules/dep/CMakeLists.txt", "project(dep CXX)\nadd_library(dep STATIC dep.cpp)\n");
        write(project, "tests/withdrawal_ledger_test.cpp", "int main() { return 0; }\n");

        assertEquals(project.toAbsolutePath().normalize(), CppProjectDetector.buildRoot(project));
        // A nested project is part of the first one, which is CMake's own rule.
        assertEquals(project.toAbsolutePath().normalize(), CppProjectDetector.inspect(ProjectRef.of(project)).buildRoot());
    }

    @Test
    @DisplayName("a commented-out target or test is not a target or a test")
    void ignoresComments(@TempDir Path project) throws IOException {
        write(project, "CMakeLists.txt", """
                cmake_minimum_required(VERSION 3.20)
                project(ledger LANGUAGES CXX)
                # add_test(NAME commented_out COMMAND nothing)
                add_library(ledger STATIC src/ledger.cpp)
                add_test(NAME real_test COMMAND ledger)
                #[[ add_executable(block_commented a.cpp) ]]
                """);
        write(project, "src/ledger.cpp", "int ledger() { return 0; }\n");

        assertEquals(List.of("real_test"), CppProjectDetector.ctestTests(project));
        assertEquals(List.of("ledger"), CppProjectDetector.libraryTargets(project));
    }

    @Test
    @DisplayName("test sources are recognised by directory and by file name")
    void recognisesTestSources(@TempDir Path project) throws IOException {
        write(project, "src/withdrawal_ledger.cpp", "int ledger() { return 0; }\n");
        write(project, "tests/withdrawal_ledger_test.cpp", "int main() { return 0; }\n");
        write(project, "src/ledger_test.cpp", "int main() { return 0; }\n");
        write(project, "test_helper.h", "// a helper\n");

        List<String> tests = CppProjectDetector.testSourceFiles(project).stream()
                .map(path -> project.relativize(path).toString())
                .toList();

        assertEquals(List.of("src/ledger_test.cpp", "test_helper.h", "tests/withdrawal_ledger_test.cpp"), tests);
    }

    @Test
    @DisplayName("build output and vendored copies are not the project's own sources")
    void skipsBuildOutput(@TempDir Path project) throws IOException {
        write(project, "CMakeLists.txt", LIBRARY_CMAKE);
        write(project, "src/ledger.cpp", "int ledger() { return 0; }\n");
        write(project, "build/generated.cpp", "int generated() { return 0; }\n");
        write(project, "CMakeFiles/ledger.dir/ledger.cpp", "int copy() { return 0; }\n");
        write(project, "third_party/dep/dep.cpp", "int dep() { return 0; }\n");

        List<String> sources = CppProjectDetector.sourceFiles(project).stream()
                .map(path -> project.relativize(path).toString())
                .toList();

        assertEquals(List.of("src/ledger.cpp"), sources);
    }

    @Test
    @DisplayName("C and C++ are told apart for the report, and one language id covers both")
    void reportsBothLanguages(@TempDir Path project) throws IOException {
        write(project, "CMakeLists.txt", LIBRARY_CMAKE);
        write(project, "src/ledger.cpp", "int ledger() { return 0; }\n");
        write(project, "src/hash.c", "int hash(void) { return 0; }\n");
        write(project, "include/ledger.h", "int hash(void);\n");

        ProjectInfo info = CppProjectDetector.inspect(ProjectRef.of(project));

        assertEquals("1", info.attributes().get(CppProjectDetector.ATTR_C_SOURCES));
        assertEquals("1", info.attributes().get(CppProjectDetector.ATTR_CXX_SOURCES),
                "a header belongs to whichever language includes it, so it names neither");
        assertEquals("cpp,c,h", info.attributes().get(CppProjectDetector.ATTR_SOURCE_EXTENSIONS));
    }

    @Test
    @DisplayName("a project file that cannot be read is reported rather than guessed at")
    void unreadableTreeYieldsNothing(@TempDir Path project) {
        Path missing = project.resolve("does-not-exist");
        assertNull(CppProjectDetector.buildRoot(missing));
        assertEquals(List.of(), CppProjectDetector.cmakeListsFiles(missing));
        assertEquals(List.of(), CppProjectDetector.sourceFiles(missing));
        assertEquals("", CppProjectDetector.read(missing.resolve("CMakeLists.txt")));
    }

    @Test
    @DisplayName("the detector reads the package manager and the project name the file declares")
    void readsPackageManager(@TempDir Path project) throws IOException {
        write(project, "CMakeLists.txt", LIBRARY_CMAKE);
        write(project, "src/ledger.cpp", "int ledger() { return 0; }\n");

        assertEquals("system", CppProjectDetector.packageManager(project));
        assertEquals("ledger", CppProjectDetector.projectName(CppProjectDetector.read(project.resolve("CMakeLists.txt")), project));

        write(project, "vcpkg.json", "{ \"dependencies\": [\"fmt\"] }\n");
        assertEquals("vcpkg", CppProjectDetector.packageManager(project));
    }

    @Test
    @DisplayName("a directory that is not there is not a project")
    void handlesMissingDirectories(@TempDir Path project) {
        assertFalse(CppProjectDetector.looksLikeCppProject(project.resolve("nope")));
        assertNull(CppProjectDetector.buildRoot(project.resolve("nope")));
    }

    @Test
    @DisplayName("an unreadable source file does not stop the scan")
    void unreadableSourceIsSkipped(@TempDir Path project) throws IOException {
        write(project, "CMakeLists.txt", LIBRARY_CMAKE);
        Path source = write(project, "src/ledger.cpp", "int ledger() { return 0; }\n");
        Files.delete(source);

        assertEquals(List.of(), CppProjectDetector.sourceFiles(project));
        assertEquals(project.toAbsolutePath().normalize(), CppProjectDetector.buildRoot(project),
                "the project file is still a project file even when its sources have gone");
    }
}
