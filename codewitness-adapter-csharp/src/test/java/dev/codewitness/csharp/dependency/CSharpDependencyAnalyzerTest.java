package dev.codewitness.csharp.dependency;

import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.PackageCoordinate;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.csharp.CSharpTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static dev.codewitness.csharp.CSharpTestContexts.LIBRARY_PROJECT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the NuGet inventory, which is what an advisory scan can actually be asked about. */
class CSharpDependencyAnalyzerTest {

    private final CSharpDependencyAnalyzer analyzer = new CSharpDependencyAnalyzer();

    @Test
    @DisplayName("declared package references become NuGet coordinates with the versions they declare")
    void readsDeclaredVersions(@TempDir Path project, @TempDir Path run) throws IOException {
        CSharpTestContexts.write(project, "Ledger/Ledger.csproj", """
                <Project Sdk="Microsoft.NET.Sdk">
                  <ItemGroup>
                    <PackageReference Include="Newtonsoft.Json" Version="13.0.1" />
                    <PackageReference Version="6.0.2" Include="coverlet.collector" />
                  </ItemGroup>
                </Project>
                """);
        VerificationContext context = CSharpTestContexts.context(project, run);

        DependencyReport report = analyzer.analyseDependencies(context);

        assertTrue(report.analysed());
        assertEquals(2, report.totalDependencies(), "attributes may be written in any order");
        assertEquals(List.of(PackageCoordinate.NUGET),
                List.copyOf(PackageCoordinate.ecosystems(report.coordinates())),
                "every coordinate is a NuGet one, and the list of ecosystems is distinct");
        assertTrue(report.coordinates().stream()
                .anyMatch(c -> c.name().equals("Newtonsoft.Json") && c.version().equals("13.0.1")));
        assertTrue(report.coordinates().stream()
                .anyMatch(c -> c.name().equals("coverlet.collector") && c.version().equals("6.0.2")));
        assertTrue(report.note().contains("project files"), report.note());
    }

    @Test
    @DisplayName("a central PackageVersion supplies the version a bare PackageReference leaves out")
    void readsCentralPackageManagement(@TempDir Path project, @TempDir Path run) throws IOException {
        CSharpTestContexts.write(project, "Directory.Packages.props", """
                <Project>
                  <ItemGroup>
                    <PackageVersion Include="xunit" Version="2.9.2" />
                  </ItemGroup>
                </Project>
                """);
        CSharpTestContexts.write(project, "Ledger.Tests/Ledger.Tests.csproj", """
                <Project Sdk="Microsoft.NET.Sdk">
                  <ItemGroup>
                    <PackageReference Include="xunit" />
                  </ItemGroup>
                </Project>
                """);
        VerificationContext context = CSharpTestContexts.context(project, run);

        DependencyReport report = analyzer.analyseDependencies(context);

        assertEquals(1, report.totalDependencies());
        assertEquals("2.9.2", report.coordinates().getFirst().version());
        assertTrue(report.coordinates().getFirst().resolved());
    }

    @Test
    @DisplayName("a lock file is the resolved graph, transitives included, and the note says so")
    void readsPackagesLockFile(@TempDir Path project, @TempDir Path run) throws IOException {
        CSharpTestContexts.write(project, "Ledger.Tests/Ledger.Tests.csproj", """
                <Project Sdk="Microsoft.NET.Sdk">
                  <ItemGroup>
                    <PackageReference Include="xunit" Version="2.9.2" />
                  </ItemGroup>
                </Project>
                """);
        CSharpTestContexts.write(project, "Ledger.Tests/packages.lock.json", """
                {
                  "version": 1,
                  "dependencies": {
                    "net8.0": {
                      "xunit": {
                        "type": "Direct",
                        "requested": "[2.9.2, )",
                        "resolved": "2.9.2",
                        "dependencies": { "xunit.core": "2.9.2" }
                      },
                      "xunit.core": {
                        "type": "Transitive",
                        "resolved": "2.9.4",
                        "contentHash": "abc"
                      }
                    }
                  }
                }
                """);
        VerificationContext context = CSharpTestContexts.context(project, run);

        DependencyReport report = analyzer.analyseDependencies(context);

        assertEquals(2, report.totalDependencies(),
                "the lock file's transitive package is part of the graph the build uses");
        assertTrue(report.coordinates().stream()
                .anyMatch(c -> c.name().equals("xunit.core") && c.version().equals("2.9.4")));
        assertTrue(report.note().contains("packages.lock.json"), report.note());
    }

    @Test
    @DisplayName("a version behind an MSBuild property is reported as unresolved, never guessed at")
    void reportsUnresolvedVersions(@TempDir Path project, @TempDir Path run) throws IOException {
        CSharpTestContexts.write(project, "Ledger/Ledger.csproj", """
                <Project Sdk="Microsoft.NET.Sdk">
                  <ItemGroup>
                    <PackageReference Include="xunit" Version="$(XunitVersion)" />
                  </ItemGroup>
                </Project>
                """);
        VerificationContext context = CSharpTestContexts.context(project, run);

        DependencyReport report = analyzer.analyseDependencies(context);

        PackageCoordinate xunit = report.coordinates().getFirst();
        assertEquals("xunit", xunit.name());
        assertEquals("", xunit.version());
        assertFalse(xunit.resolved(), "an advisory service cannot be asked about a version nobody knows");
        assertTrue(report.note().contains("cannot be asked about"), report.note());
        assertTrue(report.note().contains("xunit"), report.note());
    }

    @Test
    @DisplayName("dependencies are attributed to the project that declares them")
    void attributesDependenciesToProjects(@TempDir Path project, @TempDir Path run) throws IOException {
        CSharpTestContexts.write(project, "src/Ledger/Ledger.csproj", LIBRARY_PROJECT);
        CSharpTestContexts.write(project, "tests/Ledger.Tests/Ledger.Tests.csproj", """
                <Project Sdk="Microsoft.NET.Sdk">
                  <ItemGroup>
                    <PackageReference Include="xunit" Version="2.9.2" />
                  </ItemGroup>
                </Project>
                """);
        VerificationContext context = CSharpTestContexts.context(project, run);

        DependencyReport report = analyzer.analyseDependencies(context);

        Map<String, List<String>> byModule = report.dependenciesByModule();
        assertTrue(byModule.getOrDefault("Ledger", List.of()).isEmpty(),
                "a project with no packages declares none");
        assertEquals(List.of("xunit@2.9.2"), byModule.get("Ledger.Tests"));
    }

    @Test
    @DisplayName("a tree with no project file is not analysed, because an empty inventory would be a claim")
    void refusesToInventAnInventory(@TempDir Path project, @TempDir Path run) {
        VerificationContext context = CSharpTestContexts.context(project, run);

        DependencyReport report = analyzer.analyseDependencies(context);

        assertFalse(report.analysed());
        assertTrue(report.note().contains("no .csproj was found"));
        assertTrue(report.coordinates().isEmpty());
    }

    @Test
    @DisplayName("the manifest readers are testable on their own")
    void parsesManifests() {
        assertEquals(Map.of("xunit", "2.9.2", "Newtonsoft.Json", "13.0.1"),
                CSharpDependencyAnalyzer.centralVersions("""
                        <Project>
                          <ItemGroup>
                            <PackageVersion Include="xunit" Version="2.9.2" />
                            <PackageVersion Version="13.0.1" Include="Newtonsoft.Json" />
                            <PackageVersion Include="Unversioned" />
                          </ItemGroup>
                        </Project>
                        """));
        assertEquals(Map.of(), CSharpDependencyAnalyzer.centralVersions(""));
    }
}
