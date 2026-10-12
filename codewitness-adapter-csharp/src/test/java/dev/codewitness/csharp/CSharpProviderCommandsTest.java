package dev.codewitness.csharp;

import dev.codewitness.core.model.Capability;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.csharp.coverage.CSharpCoverageProvider;
import dev.codewitness.csharp.generate.CSharpGeneratedTestRunner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.codewitness.csharp.CSharpTestContexts.LIBRARY_PROJECT;
import static dev.codewitness.csharp.CSharpTestContexts.TEST_PROJECT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the adapter's wiring and the commands it runs.
 *
 * <p>Commands are asserted as argv, not executed: what matters here is that the SDK is invoked in the way
 * the adapter documents - audit off, results directory under the run, one report per test project, a
 * filter that selects exactly the generated classes - and a command is the cheapest thing to check.
 */
class CSharpProviderCommandsTest {

    private VerificationContext project(Path project, Path run) throws IOException {
        CSharpTestContexts.write(project, "WithdrawalLedger.sln", "Microsoft Visual Studio Solution File, "
                + "Format Version 12.00\n");
        CSharpTestContexts.write(project, "src/Ledger/Ledger.csproj", LIBRARY_PROJECT);
        CSharpTestContexts.write(project, "tests/Ledger.Tests/Ledger.Tests.csproj", TEST_PROJECT);
        return CSharpTestContexts.context(project, run);
    }

    @Test
    @DisplayName("the adapter identifies itself, claims C# projects and offers every capability it wires")
    void adapterBasics(@TempDir Path project) throws IOException {
        CSharpLanguageAdapter adapter = new CSharpLanguageAdapter();
        assertEquals("csharp", adapter.id());
        assertFalse(adapter.supports(dev.codewitness.core.model.ProjectRef.of(project)),
                "an empty directory is not claimed");
        CSharpTestContexts.write(project, "Ledger.csproj", LIBRARY_PROJECT);
        assertTrue(adapter.supports(dev.codewitness.core.model.ProjectRef.of(project)));
        assertEquals(Set.of(Capability.INSPECT, Capability.BUILD, Capability.TEST, Capability.STATIC_ANALYSIS,
                        Capability.COVERAGE, Capability.MUTATION, Capability.PROPERTY_TESTING,
                        Capability.GENERATED_TESTS, Capability.SECURITY_SCAN, Capability.DEPENDENCY_SCAN),
                adapter.capabilities());
    }

    @Test
    @DisplayName("the bundle registers one provider of every kind, and analysis only when asked")
    void bundleRegistration() {
        var languages = new dev.codewitness.core.spi.LanguageAdapterRegistry();
        var providers = dev.codewitness.core.spi.VerificationProviderRegistry.empty();
        CSharpAdapterBundle.register(languages, providers, true);

        List<String> ids = providers.providerIds();
        assertTrue(ids.contains("compiler:dotnet-build"));
        assertTrue(ids.contains("test-runner:dotnet-test"));
        assertTrue(ids.contains("coverage:dotnet-coverage"));
        assertTrue(ids.contains("mutation:csharp-text-mutation"));
        assertTrue(ids.contains("dependency:nuget-packages"));
        assertTrue(ids.contains("test-generator:csharp-scenario-generator"));
        assertTrue(ids.contains("generated-test-executor:csharp-generated-tests"));
        assertTrue(ids.contains("static-analyzer:csharp-heuristics"));
        assertTrue(ids.contains("security:csharp-security"));
        assertEquals(List.of("csharp"), languages.ids());

        var withoutAnalysis = dev.codewitness.core.spi.VerificationProviderRegistry.empty();
        CSharpAdapterBundle.register(new dev.codewitness.core.spi.LanguageAdapterRegistry(), withoutAnalysis, false);
        assertFalse(withoutAnalysis.providerIds().contains("static-analyzer:csharp-heuristics"));
    }

    @Test
    @DisplayName("the build command names the solution or project, and disables NuGet's own audit")
    void buildCommand(@TempDir Path project, @TempDir Path run) throws IOException {
        VerificationContext context = project(project, run);
        List<Path> targets = DotnetTool.buildTargets(context);
        assertEquals(List.of(project.resolve("WithdrawalLedger.sln")), targets);

        List<String> command = CSharpBuildProvider.commandFor("dotnet", context.projectRoot(), targets.getFirst());
        assertEquals(List.of("dotnet", "build", "WithdrawalLedger.sln", "--nologo", "-p:NuGetAudit=false"), command);
    }

    @Test
    @DisplayName("the test command writes its report into the run rather than into the project")
    void testCommand(@TempDir Path project, @TempDir Path run) throws IOException {
        VerificationContext context = project(project, run);
        Path resultsDirectory = context.artifacts().artifactsDirectory().resolve("dotnet/tests/1");

        List<String> command = CSharpTestProvider.commandFor(context, project.resolve("tests/Ledger.Tests/Ledger.Tests.csproj"),
                resultsDirectory);

        assertTrue(command.contains("tests/Ledger.Tests/Ledger.Tests.csproj"), command.toString());
        assertTrue(command.contains("--results-directory"), command.toString());
        assertTrue(command.contains(resultsDirectory.toString()));
        assertTrue(command.contains("trx;LogFileName=" + CSharpTestProvider.REPORT_NAME));
        assertEquals(new CSharpTestProvider().testCommand(context).size(), command.size(),
                "the recorded command is the command that runs");
    }

    @Test
    @DisplayName("a replay command filters on the class and method that failed")
    void replayCommand(@TempDir Path project, @TempDir Path run) throws IOException {
        VerificationContext context = project(project, run);
        CSharpTestContexts.write(project, "tests/Ledger.Tests/WithdrawalLedgerTests.cs", "namespace Ledger.Tests;\n");
        TestCaseResult failing = new TestCaseResult("Ledger.Tests.WithdrawalLedgerTests", "FeeIsAdded",
                TestOutcome.FAILED, Duration.ZERO, null, "boom", null);

        List<String> command = new CSharpTestProvider().replayCommand(context, failing);

        assertTrue(command.contains("--filter"), command.toString());
        assertTrue(command.contains("FullyQualifiedName~Ledger.Tests.WithdrawalLedgerTests.FeeIsAdded"));
        assertTrue(command.contains("tests/Ledger.Tests/Ledger.Tests.csproj"),
                "the replay names the test project that declares the class");
    }

    @Test
    @DisplayName("coverage is collected through the project's own test run, and generated tests are filtered")
    void coverageAndFilterCommands(@TempDir Path project, @TempDir Path run) throws IOException {
        VerificationContext context = project(project, run);
        Path resultsDirectory = context.artifacts().artifactsDirectory().resolve("dotnet/coverage/1");

        List<String> coverage = CSharpCoverageProvider.commandFor(context,
                project.resolve("tests/Ledger.Tests/Ledger.Tests.csproj"), resultsDirectory);
        assertTrue(coverage.contains("--collect"), coverage.toString());
        assertTrue(coverage.contains("XPlat Code Coverage"));

        Set<String> classes = new LinkedHashSet<>(List.of("Ledger.Tests.CwA", "Ledger.Tests.CwB"));
        assertEquals("FullyQualifiedName~Ledger.Tests.CwA|FullyQualifiedName~Ledger.Tests.CwB",
                CSharpGeneratedTestRunner.filterFor(classes));
    }

    @Test
    @DisplayName("the toolchain is resolved from DOTNET_ROOT, the conventional locations and PATH")
    void resolvesTheToolchain(@TempDir Path project, @TempDir Path install) throws IOException {
        Path dotnet = install.resolve("dotnet");
        Files.writeString(dotnet, "#!/bin/sh\n");
        dotnet.toFile().setExecutable(true);

        assertEquals(dotnet.toAbsolutePath().toString(),
                DotnetTool.resolve(project, Map.of("DOTNET_ROOT", install.toString())));
        assertEquals(dotnet.toAbsolutePath().toString(),
                DotnetTool.resolve(project, Map.of(), List.of(install)),
                "a machine-wide install is used even when DOTNET_ROOT and PATH name nothing");
        assertEquals(dotnet.toAbsolutePath().toString(),
                DotnetTool.resolve(project, Map.of("PATH", install.toString()), List.of()),
                "an executable on PATH is used when no earlier source named one");
        assertEquals("dotnet", DotnetTool.resolve(project, Map.of("PATH", "/nonexistent"), List.of()),
                "nothing found means a bare command name, which the sandbox resolves through PATH");
    }

    @Test
    @DisplayName("a project with no test project says so instead of running a test host that finds nothing")
    void reportsMissingTestProject(@TempDir Path project, @TempDir Path run) throws IOException {
        CSharpTestContexts.write(project, "src/Ledger/Ledger.csproj", LIBRARY_PROJECT);
        VerificationContext context = CSharpTestContexts.context(project, run);

        var result = new CSharpTestProvider().runTests(context);

        assertFalse(result.executed());
        assertTrue(result.note().contains("no project references a .NET test host"), result.note());
        assertEquals(List.of(), new CSharpTestProvider().testCommand(context));
        assertTrue(new CSharpTestProvider().replayCommand(context,
                new TestCaseResult("A.B", "C", TestOutcome.FAILED, Duration.ZERO, null, null, null)).isEmpty());
    }
}
