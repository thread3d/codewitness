package dev.codewitness.python;

import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Detection of Python projects. */
class PythonProjectDetectorTest {

    @Test
    @DisplayName("a pyproject project with a flat layout and a tests directory is detected")
    void detectsFlatProject(@TempDir Path root) throws IOException {
        PythonTestContexts.write(root, "pyproject.toml", """
                [project]
                name = "ledger"
                requires-python = ">=3.11"
                dependencies = []

                [build-system]
                requires = ["setuptools"]
                """);
        PythonTestContexts.write(root, "ledger.py", "VALUE = 1\n");
        PythonTestContexts.write(root, "tests/test_ledger.py", "def test_ok():\n    assert True\n");

        ProjectInfo info = PythonProjectDetector.inspect(ProjectRef.of(root));

        assertEquals(LanguageId.PYTHON, info.language());
        assertEquals(BuildSystemId.PYTHON, info.buildSystem());
        assertEquals("ledger", info.modules().getFirst().name());
        assertEquals(Path.of(root.toString()), info.sourceDirs().getFirst());
        assertEquals(Path.of(root.resolve("tests").toString()), info.testSourceDirs().getFirst());
        assertEquals("3.11", info.attributes().get(PythonProjectDetector.ATTR_PYTHON_VERSION));
        assertEquals("setuptools", info.attributes().get(PythonProjectDetector.ATTR_PACKAGE_MANAGER));
        assertTrue(info.testFrameworks().contains("unittest"));
        assertTrue(info.sourceExtensions().contains("py"));
    }

    @Test
    @DisplayName("a src layout is used as the source root so packages import as the developer expects")
    void detectsSrcLayout(@TempDir Path root) throws IOException {
        PythonTestContexts.write(root, "pyproject.toml", "[project]\nname = \"ledger\"\n");
        PythonTestContexts.write(root, "src/ledger/__init__.py", "");
        PythonTestContexts.write(root, "src/ledger/core.py", "VALUE = 1\n");

        ProjectInfo info = PythonProjectDetector.inspect(ProjectRef.of(root));

        assertEquals(Path.of(root.resolve("src").toString()), info.sourceDirs().getFirst());
    }

    @Test
    @DisplayName("configured pytest is reported as the project's test framework")
    void detectsPytest(@TempDir Path root) throws IOException {
        PythonTestContexts.write(root, "pytest.ini", "[pytest]\ntestpaths = tests\n");
        PythonTestContexts.write(root, "tests/test_ledger.py", "def test_ok():\n    assert True\n");

        ProjectInfo info = PythonProjectDetector.inspect(ProjectRef.of(root));

        assertEquals("pytest", info.attributes().get(PythonProjectDetector.ATTR_TEST_FRAMEWORK));
        assertTrue(info.testFrameworks().contains("pytest"));
    }

    @Test
    @DisplayName("an unrelated directory is not claimed as Python")
    void rejectsNonPythonProject(@TempDir Path root) throws IOException {
        PythonTestContexts.write(root, "README.txt", "nothing to see\n");
        PythonTestContexts.write(root, "src/main/java/Foo.java", "class Foo {}\n");

        assertFalse(PythonProjectDetector.looksLikePythonProject(root));
    }

    @Test
    @DisplayName("pythonFiles skips caches and virtual environments")
    void skipsCaches(@TempDir Path root) throws IOException {
        PythonTestContexts.write(root, "ledger.py", "VALUE = 1\n");
        PythonTestContexts.write(root, "__pycache__/ledger.cpython-311.py", "");
        PythonTestContexts.write(root, ".venv/lib/python3.11/site-packages/dependency.py", "");

        var files = PythonProjectDetector.pythonFiles(root);

        assertEquals(1, files.size());
        assertTrue(files.getFirst().toString().endsWith("ledger.py"));
    }
}
