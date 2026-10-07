package dev.codewitness.go.dependency;

import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.PackageCoordinate;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.go.GoTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The dependency inventory reads every {@code require} spelling and ignores the other directives. */
class GoDependencyAnalyzerTest {

    @Test
    @DisplayName("require blocks, indirect markers and single-line requires are all inventoried")
    void readsEveryRequireForm() {
        String manifest = """
                module example.com/sample

                go 1.22

                toolchain go1.22.1

                require (
                	github.com/pkg/errors v0.9.1
                	golang.org/x/text v0.14.0 // indirect
                )

                require github.com/google/uuid v1.6.0

                replace example.com/old => example.com/new v1.0.0

                exclude example.com/bad v1.0.0
                """;

        assertEquals(List.of("github.com/pkg/errors", "golang.org/x/text", "github.com/google/uuid"),
                GoDependencyAnalyzer.parse(manifest));
        assertEquals(List.of("github.com/pkg/errors", "github.com/google/uuid"),
                GoDependencyAnalyzer.direct(manifest));
    }

    @Test
    @DisplayName("a module with no requires declares none, and that is not an error")
    void emptyRequireSet() {
        assertEquals(List.of(), GoDependencyAnalyzer.parse("module example.com/sample\n\ngo 1.22\n"));
    }

    @Test
    @DisplayName("every require directive is inventoried with the version it selects")
    void carriesTheVersionEachRequirementSelects() {
        String manifest = """
                module example.com/sample

                go 1.22

                require (
                	github.com/pkg/errors v0.9.1
                	golang.org/x/text v0.14.0 // indirect
                )
                """;

        List<PackageCoordinate> coordinates = GoDependencyAnalyzer.coordinates(manifest);

        assertEquals(2, coordinates.size());
        assertEquals("Go", coordinates.getFirst().ecosystem());
        assertEquals("github.com/pkg/errors", coordinates.getFirst().name());
        assertEquals("v0.9.1", coordinates.getFirst().version());
        assertEquals("v0.14.0", coordinates.get(1).version());
    }

    @Test
    @DisplayName("the analysis reads go.mod from the project and reports a queryable inventory")
    void inventoriesTheModule(@TempDir Path root, @TempDir Path run) throws IOException {
        GoTestContexts.write(root, "go.mod", """
                module example.com/sample

                go 1.22

                require github.com/pkg/errors v0.9.1
                """);
        VerificationContext context = GoTestContexts.context(root, run);

        DependencyReport report = new GoDependencyAnalyzer().analyseDependencies(context);

        assertTrue(report.analysed());
        assertEquals("go-module", report.tool());
        assertEquals(1, report.totalDependencies());
        assertEquals(List.of("github.com/pkg/errors@v0.9.1"), report.directDependencies());
        // The point of carrying the version: an advisory service can be asked about this dependency,
        // and the module it belongs to is recorded so a finding can be charged to the right code.
        assertEquals("v0.9.1", report.coordinates().getFirst().version());
        assertEquals(1, report.dependenciesByModule().size());
        assertTrue(report.vulnerabilities().isEmpty());
        assertTrue(report.note().contains("known advisory"), report.note());
    }

    @Test
    @DisplayName("a project with no go.mod cannot be inventoried, and that is reported")
    void missingManifestIsNotAnalysed(@TempDir Path root, @TempDir Path run) {
        VerificationContext context = GoTestContexts.context(root, run);

        DependencyReport report = new GoDependencyAnalyzer().analyseDependencies(context);

        assertFalse(report.analysed());
        assertEquals("go-module", report.tool());
        assertTrue(report.note().contains("no go.mod"), report.note());
    }
}
