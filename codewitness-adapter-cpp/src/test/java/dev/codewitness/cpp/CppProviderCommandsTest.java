package dev.codewitness.cpp;

import dev.codewitness.core.model.Capability;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.spi.LanguageAdapterRegistry;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationProviderRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static dev.codewitness.cpp.CppTestContexts.LIBRARY_CMAKE;
import static dev.codewitness.cpp.CppTestContexts.TESTING_CMAKE;
import static dev.codewitness.cpp.CppTestContexts.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the adapter's wiring and the commands it runs.
 *
 * <p>Commands are asserted as argv, not executed: what matters here is that CMake is invoked in the way the
 * adapter documents - configure with a compile database, build in a directory inside the run, test through
 * CTest with an absolute report path - and a command is the cheapest thing to check.
 */
class CppProviderCommandsTest {

    @Test
    @DisplayName("the adapter identifies itself, claims CMake projects and offers every capability it wires")
    void adapterBasics(@TempDir Path project) throws IOException {
        CppLanguageAdapter adapter = new CppLanguageAdapter();
        assertEquals("cpp", adapter.id());
        assertFalse(adapter.supports(ProjectRef.of(project)), "an empty directory is not claimed");

        write(project, "CMakeLists.txt", LIBRARY_CMAKE);
        assertFalse(adapter.supports(ProjectRef.of(project)),
                "a CMake file with no source file is not a project to verify");
        write(project, "src/ledger.cpp", "int ledger() { return 0; }\n");
        assertTrue(adapter.supports(ProjectRef.of(project)));

        assertEquals(Set.of(Capability.INSPECT, Capability.BUILD, Capability.TEST, Capability.STATIC_ANALYSIS,
                        Capability.COVERAGE, Capability.MUTATION, Capability.PROPERTY_TESTING,
                        Capability.GENERATED_TESTS, Capability.SECURITY_SCAN, Capability.DEPENDENCY_SCAN),
                adapter.capabilities());
    }

    @Test
    @DisplayName("the bundle registers one provider of every kind, and analysis only when asked")
    void bundleRegistration() {
        LanguageAdapterRegistry languages = new LanguageAdapterRegistry();
        VerificationProviderRegistry providers = VerificationProviderRegistry.empty();
        CppAdapterBundle.register(languages, providers, true);

        List<String> ids = providers.providerIds();
        assertTrue(ids.contains("compiler:cmake-build"));
        assertTrue(ids.contains("test-runner:ctest"));
        assertTrue(ids.contains("coverage:gcov-coverage"));
        assertTrue(ids.contains("mutation:cpp-text-mutation"));
        assertTrue(ids.contains("dependency:cpp-packages"));
        assertTrue(ids.contains("test-generator:cpp-scenario-generator"));
        assertTrue(ids.contains("generated-test-executor:cpp-generated-tests"));
        assertTrue(ids.contains("static-analyzer:cpp-heuristics"));
        assertTrue(ids.contains("security:cpp-security"));
        assertEquals(List.of("cpp"), languages.ids());

        VerificationProviderRegistry withoutAnalysis = VerificationProviderRegistry.empty();
        CppAdapterBundle.register(new LanguageAdapterRegistry(), withoutAnalysis, false);
        assertFalse(withoutAnalysis.providerIds().contains("static-analyzer:cpp-heuristics"));
        assertFalse(withoutAnalysis.providerIds().contains("security:cpp-security"));
    }

    @Test
    @DisplayName("the configure command exports a compile database and asks for no generator it cannot have")
    void configureCommand(@TempDir Path project) {
        List<String> command = CppBuildTools.configureCommand("cmake", project.resolve("src"),
                project.resolve("build"), false);

        assertEquals("cmake", command.getFirst());
        assertTrue(command.contains("-S"), command.toString());
        assertTrue(command.contains("-B"), command.toString());
        assertTrue(command.contains("-DCMAKE_EXPORT_COMPILE_COMMANDS=ON"), command.toString());
        assertTrue(command.contains("-DCMAKE_BUILD_TYPE=Debug"), command.toString());
        assertFalse(command.contains("--coverage"), "the project's own build is never instrumented");
    }

    @Test
    @DisplayName("a coverage configure adds the instrumentation without touching the project's build")
    void coverageConfigureCommand(@TempDir Path project) {
        List<String> command = CppBuildTools.configureCommand("cmake", project.resolve("src"),
                project.resolve("cov"), true);

        assertTrue(command.stream().anyMatch(argument -> argument.startsWith("-DCMAKE_CXX_FLAGS=")), command.toString());
        assertTrue(command.stream().anyMatch(argument -> argument.contains("--coverage")), command.toString());
        assertTrue(command.contains("-DCMAKE_EXE_LINKER_FLAGS=--coverage"), command.toString());
    }

    @Test
    @DisplayName("the build and test commands name the build directory inside the run")
    void buildAndTestCommands(@TempDir Path project) {
        List<String> build = CppBuildTools.buildCommand("cmake", project.resolve("build"));
        assertEquals(List.of("cmake", "--build", project.resolve("build").toString(), "--parallel"), build);

        List<String> test = CppBuildTools.testCommand(project.resolve("build"),
                project.resolve("ctest-junit.xml"));
        assertEquals("ctest", test.getFirst());
        assertTrue(test.contains("--test-dir"), test.toString());
        assertTrue(test.contains("--output-junit"), test.toString());
        assertTrue(test.contains(project.resolve("ctest-junit.xml").toString()),
                "the report path is absolute, because CTest resolves a relative one against the test dir");
        assertTrue(test.contains("--output-on-failure"), test.toString());
    }

    @Test
    @DisplayName("the test report is written into the run rather than into the project")
    void testCommandRecordsTheReportUnderTheRun(@TempDir Path project, @TempDir Path run) throws IOException {
        VerificationContext context = CppTestContexts.fixture(project, run);

        List<String> command = new CppTestProvider().testCommand(context);

        assertTrue(command.contains(CppTestProvider.reportFile(context).toString()), command.toString());
        assertTrue(CppTestProvider.reportFile(context).startsWith(context.artifacts().artifactsDirectory()));
    }

    @Test
    @DisplayName("a replay command selects exactly the test that failed")
    void replayCommand(@TempDir Path project, @TempDir Path run) throws IOException {
        VerificationContext context = CppTestContexts.fixture(project, run);
        TestCaseResult failing = new TestCaseResult("withdrawal_ledger_test", "withdrawal_ledger_test",
                TestOutcome.FAILED, Duration.ZERO, null, "boom", null);

        List<String> command = new CppTestProvider().replayCommand(context, failing);

        assertTrue(command.contains("-R"), command.toString());
        assertTrue(command.contains("^withdrawal_ledger_test$"),
                "the selection is anchored, so a test whose name contains this one is not run too: "
                        + command);
        assertTrue(command.contains("--output-on-failure"), command.toString());
    }

    @Test
    @DisplayName("a project with no CMake file says so instead of running a suite that cannot exist")
    void reportsMissingProject(@TempDir Path project, @TempDir Path run) throws IOException {
        write(project, "src/ledger.cpp", "int ledger() { return 0; }\n");
        VerificationContext context = CppTestContexts.context(project, run);

        var result = new CppTestProvider().runTests(context);

        assertFalse(result.executed());
        assertTrue(result.note().contains("no CMakeLists.txt"), result.note());
        assertEquals(List.of(), new CppTestProvider().testCommand(context));
        assertTrue(new CppTestProvider().replayCommand(context,
                new TestCaseResult("a", "b", TestOutcome.FAILED, Duration.ZERO, null, null, null)).isEmpty());
    }

    @Test
    @DisplayName("a CMake project that registers no test says so rather than reporting an empty suite")
    void reportsMissingTests(@TempDir Path project, @TempDir Path run) throws IOException {
        write(project, "CMakeLists.txt", LIBRARY_CMAKE);
        write(project, "src/ledger.cpp", "int ledger() { return 0; }\n");
        VerificationContext context = CppTestContexts.context(project, run);

        var result = new CppTestProvider().runTests(context);

        assertFalse(result.executed());
        assertTrue(result.note().contains("registers no test with add_test"), result.note());
    }

    @Test
    @DisplayName("the compile flags come from the project's own build, and the generated test is linked to it")
    void readsTheCompileDatabase(@TempDir Path project) throws IOException {
        write(project, "build/compile_commands.json", """
                [
                  {
                    "directory": "/work/build",
                    "command": "/usr/bin/c++ -I/include -std=gnu++17 -Wall -c /work/src/ledger.cpp -o CMakeFiles/ledger.dir/src/ledger.cpp.o",
                    "file": "/work/src/ledger.cpp"
                  }
                ]
                """);

        List<String> flags = CppBuildTools.compileFlags(project.resolve("build"), Path.of("/work/src/ledger.cpp"));

        assertTrue(flags.contains("-I/include"), flags.toString());
        assertTrue(flags.contains("-std=gnu++17"), flags.toString());
        assertTrue(flags.contains("-Wall"), flags.toString());
        assertFalse(flags.contains("/work/src/ledger.cpp"), "the source file is not a flag: " + flags);
        assertFalse(flags.contains("-o"), "the output file is not a flag: " + flags);
        assertFalse(flags.contains("-c"), flags.toString());
    }

    @Test
    @DisplayName("a compile database in the arguments form is read the same way")
    void readsTheArgumentsForm(@TempDir Path project) throws IOException {
        write(project, "build/compile_commands.json", """
                [
                  {
                    "directory": "/work/build",
                    "arguments": ["/usr/bin/c++", "-I/include", "-O2", "-c", "/work/src/ledger.cpp"],
                    "file": "/work/src/ledger.cpp"
                  }
                ]
                """);

        List<String> flags = CppBuildTools.compileFlags(project.resolve("build"), Path.of("/work/src/ledger.cpp"));

        assertEquals(List.of("-I/include", "-O2"), flags);
        assertEquals(Path.of("/work/build"),
                CppBuildTools.compileDirectory(project.resolve("build"), Path.of("/work/src/ledger.cpp")));
        assertEquals("/usr/bin/c++", CppBuildTools.compiler(project.resolve("build"), Path.of("/work/src/ledger.cpp")));
    }

    @Test
    @DisplayName("a command line full of quoting is split the way a shell would")
    void splitsQuotedCommands() {
        assertEquals(List.of("c++", "-DNAME=a b", "-I/path", "src/a.cpp"),
                CppBuildTools.splitCommand("c++ -DNAME=\"a b\" -I/path src/a.cpp"));
        assertEquals(List.of("c++", "-DNAME=a b"), CppBuildTools.splitCommand("c++ -DNAME='a b'"));
        assertEquals(List.of(), CppBuildTools.splitCommand("   "));
    }

    @Test
    @DisplayName("the compile database is read from wherever the configure wrote it")
    void findsTheCompileDatabase(@TempDir Path project) throws IOException {
        assertEquals(project.resolve("build/compile_commands.json"),
                CppBuildTools.compileDatabase(project.resolve("build")));
        assertEquals(List.of(), CppBuildTools.compileFlags(project.resolve("absent"), project.resolve("x.cpp")));
        assertTrue(CppBuildTools.builtArchives(project.resolve("absent")).isEmpty());
    }

    @Test
    @DisplayName("a build directory the adapter creates is inside the run, never inside the tree")
    void buildDirectoriesLiveInTheRun(@TempDir Path project, @TempDir Path run) throws IOException {
        VerificationContext context = CppTestContexts.fixture(project, run);
        CppBuildTools tools = new CppBuildTools(context.runner());

        assertTrue(tools.buildDirectory(context).startsWith(context.artifacts().artifactsDirectory()));
        assertTrue(tools.coverageDirectory(context).startsWith(context.artifacts().artifactsDirectory()));
        assertTrue(tools.gcovDirectory(context).startsWith(context.artifacts().artifactsDirectory()));
        assertTrue(tools.generatedDirectory(context).startsWith(context.artifacts().artifactsDirectory()));
        assertFalse(tools.buildDirectory(context).startsWith(project),
                "nothing is configured inside the project under verification");
    }

    @Test
    @DisplayName("the toolchain is resolved from PATH and a missing one is a bare command name")
    void resolvesTheToolchain(@TempDir Path project, @TempDir Path install) throws IOException {
        Path cmake = install.resolve("cmake");
        Files.writeString(cmake, "#!/bin/sh\n");
        cmake.toFile().setExecutable(true);

        assertEquals("cmake", CppBuildTools.resolve(project, java.util.Map.of("PATH", "/nonexistent")),
                "nothing found means a bare command name, which the sandbox resolves through PATH");
        assertTrue(CppBuildTools.resolve(project, java.util.Map.of()).endsWith("cmake"));
    }
}
