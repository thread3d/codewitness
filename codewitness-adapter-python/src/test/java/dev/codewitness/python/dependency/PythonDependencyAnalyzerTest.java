package dev.codewitness.python.dependency;

import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.PackageCoordinate;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.python.PythonTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Inventory of declared Python dependencies. */
class PythonDependencyAnalyzerTest {

    @Test
    @DisplayName("a requirements file yields names without version specifiers or options")
    void parsesRequirementsFile() {
        List<String> names = PythonDependencyAnalyzer.fromRequirements("""
                # runtime dependencies
                requests>=2.31
                Flask[async]==3.0.0 ; python_version >= "3.9"
                -r base.txt
                --index-url https://example.invalid/simple
                """);

        assertEquals(List.of("requests", "Flask"), names);
    }

    @Test
    @DisplayName("pyproject, setup.cfg and requirements are combined into one inventory")
    void inventoriesProject(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        PythonTestContexts.write(root, "pyproject.toml", """
                [project]
                name = "ledger"
                dependencies = ["requests>=2.31", "orjson"]

                [tool.poetry.dependencies]
                python = "^3.11"
                httpx = "^0.27"
                """);
        PythonTestContexts.write(root, "requirements-dev.txt", "pytest==8.0.0\nmypy>=1.8\n");
        VerificationContext context = PythonTestContexts.context(root, runDirectory);

        DependencyReport report = new PythonDependencyAnalyzer().analyseDependencies(context);

        assertTrue(report.analysed());
        assertTrue(report.directDependencies().containsAll(List.of("requests", "orjson", "httpx", "mypy")),
                "declared dependencies must be inventoried: " + report.directDependencies());
        assertTrue(!report.directDependencies().contains("python"), "the interpreter itself is not a dependency");
        assertTrue(!report.directDependencies().contains("pytest"), "test tooling is not a runtime dependency");
        assertTrue(report.note().contains("vulnerability"), "the report must admit it is an inventory only");
    }

    @Test
    @DisplayName("a project with no manifest is reported as not analysed")
    void withoutManifestIsNotAnalysed(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        PythonTestContexts.write(root, "ledger.py", "VALUE = 1\n");
        VerificationContext context = PythonTestContexts.context(root, runDirectory);

        DependencyReport report = new PythonDependencyAnalyzer().analyseDependencies(context);

        assertTrue(!report.analysed());
        assertTrue(report.note().contains("no dependency manifest"));
    }

    @Test
    @DisplayName("poetry.lock is preferred, and every distribution it resolves carries its version")
    void readsResolvedVersionsFromPoetryLock(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        PythonTestContexts.write(root, "pyproject.toml", """
                [tool.poetry.dependencies]
                python = "^3.11"
                requests = "^2.31"
                """);
        PythonTestContexts.write(root, "poetry.lock", """
                [[package]]
                name = "requests"
                version = "2.31.0"

                [[package]]
                name = "urllib3"
                version = "2.2.1"
                """);
        VerificationContext context = PythonTestContexts.context(root, runDirectory);

        DependencyReport report = new PythonDependencyAnalyzer().analyseDependencies(context);

        assertEquals("poetry-lock", report.tool());
        assertEquals(2, report.totalDependencies());
        assertEquals(List.of("requests@2.31.0", "urllib3@2.2.1"), report.directDependencies());
        assertEquals("PyPI", report.coordinates().getFirst().ecosystem());
        assertTrue(report.coordinates().stream().allMatch(coordinate -> coordinate.resolved()),
                "every coordinate from a lock file must be queryable: " + report.coordinates());
    }

    @Test
    @DisplayName("a requirement pinned with == is queryable, and an unpinned one is reported as not")
    void pinsOnlyExactRequirements(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        PythonTestContexts.write(root, "requirements.txt", """
                # pinned exactly
                requests==2.31.0
                Flask[async]===3.0.0
                urllib3==2.2.*
                orjson>=3.9
                """);
        VerificationContext context = PythonTestContexts.context(root, runDirectory);

        DependencyReport report = new PythonDependencyAnalyzer().analyseDependencies(context);

        assertTrue(report.directDependencies().contains("requests@2.31.0"), report.directDependencies().toString());
        assertTrue(report.directDependencies().contains("Flask@3.0.0"), report.directDependencies().toString());
        // A wildcard and a range both admit more than one version; claiming one of them would be a guess,
        // and an advisory finding about a version that was never installed is worse than no finding.
        assertTrue(report.directDependencies().contains("urllib3"), report.directDependencies().toString());
        assertTrue(report.directDependencies().contains("orjson"), report.directDependencies().toString());
        assertEquals(2, report.coordinates().stream().filter(PackageCoordinate::resolved).count());
        assertTrue(report.note().contains("known vulnerability"), report.note());
    }
}
