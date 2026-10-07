package dev.codewitness.csharp;

import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ModuleInfo;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.codewitness.csharp.CSharpTestContexts.LIBRARY_PROJECT;
import static dev.codewitness.csharp.CSharpTestContexts.TEST_PROJECT;
import static dev.codewitness.csharp.CSharpTestContexts.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for .NET project detection, which is what decides whether the adapter claims a tree at all. */
class CSharpProjectDetectorTest {

    @Test
    @DisplayName("a project file, a solution or a bare .cs file identifies a C# project")
    void detectsCSharpProjects(@TempDir Path project) throws IOException {
        assertFalse(CSharpProjectDetector.looksLikeCSharpProject(project),
                "an empty directory is not a C# project");

        write(project, "Ledger.csproj", LIBRARY_PROJECT);
        assertTrue(CSharpProjectDetector.looksLikeCSharpProject(project),
                "a project file is the strongest possible signal");
    }

    @Test
    @DisplayName("sources without a project file are detected, and analysed rather than built")
    void detectsSourceOnlyTrees(@TempDir Path project) throws IOException {
        write(project, "src/Ledger.cs", "namespace Ledger;\n\npublic class Ledger { }\n");

        assertTrue(CSharpProjectDetector.looksLikeCSharpProject(project));
        ProjectInfo info = CSharpProjectDetector.inspect(ProjectRef.of(project));
        assertEquals(LanguageId.CSHARP, info.language());
        assertEquals(BuildSystemId.NONE, info.buildSystem(),
                "there is no project for the SDK to build, and saying otherwise would promise a build");
        assertNotNull(info.attributes().get(CSharpProjectDetector.ATTR_DETECTION_WARNING));
        assertEquals(1, info.modules().size());
        assertEquals(1, info.modules().getFirst().sourceDirs().size(),
                "the sources are still described, so static analysis has somewhere to look");
    }

    @Test
    @DisplayName("an F# or Visual Basic project is not claimed, because the adapter cannot build it")
    void doesNotClaimOtherDotNetLanguages(@TempDir Path project) throws IOException {
        write(project, "Ledger.fsproj", "<Project Sdk=\"Microsoft.NET.Sdk\"></Project>");
        write(project, "Ledger.fs", "module Ledger\n");
        assertFalse(CSharpProjectDetector.looksLikeCSharpProject(project));

        Path solution = write(project, "Ledger.sln", """
                Microsoft Visual Studio Solution File, Format Version 12.00
                Project("{FAE04EC0-301F-11D3-BF4B-00C04F79EFBC}") = "Ledger", "Ledger.fsproj", "{11111111-1111-1111-1111-111111111111}"
                EndProject
                """);
        assertTrue(Files.isRegularFile(solution));
        assertFalse(CSharpProjectDetector.looksLikeCSharpProject(project),
                "a solution that lists only F# projects is not a C# project either");
    }

    @Test
    @DisplayName("a solution that references a C# project identifies the tree even without the project beside it")
    void detectsSolution(@TempDir Path project) throws IOException {
        write(project, "Ledger.sln", """
                Microsoft Visual Studio Solution File, Format Version 12.00
                Project("{FAE04EC0-301F-11D3-BF4B-00C04F79EFBC}") = "Ledger", "src\\Ledger\\Ledger.csproj", "{11111111-1111-1111-1111-111111111111}"
                EndProject
                """);
        assertTrue(CSharpProjectDetector.looksLikeCSharpProject(project));
    }

    @Test
    @DisplayName("a test project is one that references a test host, not one that mentions a test framework")
    void distinguishesTestProjects(@TempDir Path project) throws IOException {
        Path testProject = write(project, "tests/Ledger.Tests/Ledger.Tests.csproj", TEST_PROJECT);
        Path library = write(project, "src/Ledger/Ledger.csproj", LIBRARY_PROJECT);
        write(project, "src/Ledger/Analyzer.csproj", """
                <Project Sdk="Microsoft.NET.Sdk">
                  <ItemGroup>
                    <PackageReference Include="xunit" Version="2.9.2" />
                  </ItemGroup>
                </Project>
                """);

        assertTrue(CSharpProjectDetector.isTestProject(testProject));
        assertFalse(CSharpProjectDetector.isTestProject(library));
        assertEquals("xunit", CSharpProjectDetector.testFramework(testProject));
        assertEquals(List.of(testProject), CSharpProjectDetector.testProjects(project),
                "a library that merely references xUnit is not a test project");
    }

    @Test
    @DisplayName("a project that declares IsTestProject is a test project whatever it references")
    void honoursIsTestProject(@TempDir Path project) throws IOException {
        Path csproj = write(project, "Tests.csproj", """
                <Project Sdk="Microsoft.NET.Sdk">
                  <PropertyGroup>
                    <IsTestProject>true</IsTestProject>
                  </PropertyGroup>
                </Project>
                """);
        assertTrue(CSharpProjectDetector.isTestProject(csproj));
    }

    @Test
    @DisplayName("inspection separates main sources from test sources and names what it found")
    void inspectsLayout(@TempDir Path project) throws IOException {
        write(project, "src/Ledger/Ledger.csproj", LIBRARY_PROJECT);
        write(project, "src/Ledger/WithdrawalLedger.cs", "namespace Ledger;\n");
        write(project, "tests/Ledger.Tests/Ledger.Tests.csproj", TEST_PROJECT);
        write(project, "tests/Ledger.Tests/WithdrawalLedgerTests.cs", "namespace Ledger.Tests;\n");
        write(project, "global.json", "{ \"sdk\": { \"version\": \"8.0.425\" } }");

        ProjectInfo info = CSharpProjectDetector.inspect(ProjectRef.of(project));

        assertEquals(LanguageId.CSHARP, info.language());
        assertEquals(BuildSystemId.DOTNET, info.buildSystem());
        assertEquals(List.of("cs"), List.copyOf(info.sourceExtensions()));
        assertEquals(2, info.modules().size());
        assertTrue(info.hasTests());

        ModuleInfo main = info.modules().stream().filter(m -> m.name().equals("Ledger")).findFirst().orElseThrow();
        assertTrue(main.sourceDirs().contains(project.resolve("src/Ledger")));
        assertTrue(main.testSourceDirs().isEmpty());

        ModuleInfo tests = info.modules().stream().filter(m -> m.name().equals("Ledger.Tests")).findFirst()
                .orElseThrow();
        assertTrue(tests.testSourceDirs().contains(project.resolve("tests/Ledger.Tests")));
        assertTrue(tests.sourceDirs().isEmpty(),
                "a test project's directory is a test tree, not a main source tree");

        assertEquals("xunit", info.attributes().get(CSharpProjectDetector.ATTR_TEST_FRAMEWORK));
        assertEquals("net8.0", info.attributes().get(CSharpProjectDetector.ATTR_TARGET_FRAMEWORK));
        assertEquals("Ledger,Ledger.Tests", info.attributes().get(CSharpProjectDetector.ATTR_NAMESPACES));
        assertEquals("2", info.attributes().get(CSharpProjectDetector.ATTR_PROJECTS));
        assertEquals("1", info.attributes().get(CSharpProjectDetector.ATTR_TEST_PROJECTS));
        assertTrue(info.detectedDescriptors().contains(project.resolve("global.json")));
    }

    @Test
    @DisplayName("build output and previous runs are not walked")
    void skipsBuildOutput(@TempDir Path project) throws IOException {
        write(project, "src/Ledger/Ledger.csproj", LIBRARY_PROJECT);
        write(project, "src/Ledger/obj/Debug/Copied.csproj", LIBRARY_PROJECT);
        write(project, "src/Ledger/bin/Debug/Copied.csproj", LIBRARY_PROJECT);
        write(project, ".codewitness/runs/run/workspace/Copied.csproj", LIBRARY_PROJECT);

        assertEquals(List.of(project.resolve("src/Ledger/Ledger.csproj")),
                CSharpProjectDetector.projectFiles(project));
    }

    @Test
    @DisplayName("one solution is the build target; several unrelated ones fall back to the projects")
    void resolvesBuildTargets(@TempDir Path project) throws IOException {
        write(project, "src/Ledger/Ledger.csproj", LIBRARY_PROJECT);
        write(project, "tests/Ledger.Tests/Ledger.Tests.csproj", TEST_PROJECT);
        assertEquals(2, CSharpProjectDetector.buildTargets(project).size(),
                "without a solution every project is built");

        Path solution = write(project, "Ledger.sln", "Microsoft Visual Studio Solution File, Format Version 12.00\n");
        assertEquals(List.of(solution), CSharpProjectDetector.buildTargets(project));

        write(project, "other/Other.sln", "Microsoft Visual Studio Solution File, Format Version 12.00\n");
        assertEquals(2, CSharpProjectDetector.buildTargets(project).size(),
                "two solutions mean neither is the tree's own statement of what belongs together");
    }

    @Test
    @DisplayName("package references and namespaces are read from the project files and sources")
    void readsPackageReferencesAndNamespaces(@TempDir Path project) throws IOException {
        Path csproj = write(project, "Ledger.csproj", TEST_PROJECT);
        assertEquals(List.of("Microsoft.NET.Test.Sdk", "xunit"),
                CSharpProjectDetector.packageReferences(csproj));

        assertEquals(List.of("Ledger.Tests"), CSharpProjectDetector.namespacesOf("namespace Ledger.Tests;\n"));
        assertEquals(List.of("A.B", "C"), CSharpProjectDetector.namespacesOf("""
                namespace A.B
                {
                }

                namespace C;
                """));
        assertEquals(List.of(), CSharpProjectDetector.namespacesOf("// namespace NotReal;\n"));
        assertEquals(List.of(), CSharpProjectDetector.namespacesOf(""));
    }

    @Test
    @DisplayName("an unreadable tree yields no projects rather than an exception")
    void toleratesMissingDirectory() {
        assertEquals(List.of(), CSharpProjectDetector.projectFiles(Path.of("/nonexistent-codewitness")));
        assertFalse(CSharpProjectDetector.looksLikeCSharpProject(Path.of("/nonexistent-codewitness")));
        assertFalse(CSharpProjectDetector.looksLikeCSharpProject(null));
    }
}
