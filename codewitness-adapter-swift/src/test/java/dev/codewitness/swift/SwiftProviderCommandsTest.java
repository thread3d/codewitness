package dev.codewitness.swift;

import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.spi.VerificationContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The commands the Swift adapter builds around the toolchain.
 *
 * <p>Nothing here invokes {@code swift}: the executable is a path that does not exist, and every test
 * asserts the argv and the run-owned directories rather than a toolchain result. What matters is that the
 * caches SwiftPM would otherwise write under the user's home are redirected inside the run, that SwiftPM's
 * nested sandbox is disabled, and that a counterexample's replay command names exactly one case.
 */
class SwiftProviderCommandsTest {

    @Test
    @DisplayName("every SwiftPM invocation disables the nested sandbox and points its directories inside the run")
    void toolchainCommandShape(@TempDir Path root, @TempDir Path run) throws IOException {
        SwiftTestContexts.writePackageWithTests(root);
        VerificationContext context = SwiftTestContexts.context(root, run);

        List<String> options = SwiftToolchain.commonOptions(context);

        assertEquals(List.of(
                        "--disable-sandbox",
                        "--cache-path", SwiftToolchain.directory(context, "cache").toString(),
                        "--config-path", SwiftToolchain.directory(context, "config").toString(),
                        "--security-path", SwiftToolchain.directory(context, "security").toString()),
                options);
        assertTrue(options.get(2).startsWith(context.artifacts().artifactsDirectory().toString()),
                "SwiftPM's default cache lives under the user's home, which is outside the run: " + options);

        List<String> command = SwiftToolchain.command(
                SwiftTestContexts.MISSING_SWIFT, context, List.of("build", "--build-tests"));

        assertEquals(SwiftTestContexts.MISSING_SWIFT, command.getFirst());
        assertEquals(List.of("build", "--build-tests"), command.subList(1, 3));
        assertEquals(options, command.subList(3, command.size()),
                "the shared options come after the subcommand and its own options: " + command);
    }

    @Test
    @DisplayName("prepareDirectories creates the cache, config, security and module-cache trees the run owns")
    void prepareDirectoriesCreatesTheRunOwnedTrees(@TempDir Path root, @TempDir Path run) throws IOException {
        SwiftTestContexts.writePackageWithTests(root);
        VerificationContext context = SwiftTestContexts.context(root, run);

        Path cache = SwiftToolchain.prepareDirectories(context);

        assertEquals(SwiftToolchain.directory(context, "cache"), cache);
        assertTrue(Files.isDirectory(cache));
        assertTrue(Files.isDirectory(SwiftToolchain.directory(context, "config")));
        assertTrue(Files.isDirectory(SwiftToolchain.directory(context, "security")));

        Map<String, String> environment = SwiftToolchain.environment(context);

        assertEquals(SwiftToolchain.directory(context, "clang-module-cache").toString(),
                environment.get("CLANG_MODULE_CACHE_PATH"),
                "Clang's own default module cache is per-user, so the driver is pointed inside the run");
        assertEquals(SwiftToolchain.directory(context, "swift-module-cache").toString(),
                environment.get("SWIFT_MODULECACHE_PATH"));
        assertTrue(Files.isDirectory(Path.of(environment.get("CLANG_MODULE_CACHE_PATH"))));
        assertTrue(Files.isDirectory(Path.of(environment.get("SWIFT_MODULECACHE_PATH"))));
    }

    @Test
    @DisplayName("the attribute names are the contract the detector and the generator share")
    void attributeNamesAreTheContract() {
        assertEquals("swift", SwiftToolchain.ATTR_SWIFT);
        assertEquals("testModule", SwiftToolchain.ATTR_TEST_MODULE);
        assertEquals("hostModule", SwiftToolchain.ATTR_HOST_MODULE);
        assertEquals("swiftProbeFailure", SwiftToolchain.ATTR_PROBE_FAILURE);
    }

    @Test
    @DisplayName("the test command runs the whole suite, and the replay command pins one case with --filter")
    void replayCommandSelectsOneCase(@TempDir Path root, @TempDir Path run) throws IOException {
        SwiftTestContexts.writePackageWithTests(root);
        VerificationContext context = SwiftTestContexts.context(
                SwiftTestContexts.withAttributes(root,
                        Map.of(SwiftToolchain.ATTR_SWIFT, SwiftTestContexts.MISSING_SWIFT)), run);
        SwiftTestProvider provider = new SwiftTestProvider();
        TestCaseResult failing = new TestCaseResult("LedgerTests.LedgerTests", "testAdd",
                TestOutcome.FAILED, Duration.ZERO, "XCTAssertEqual", "boom", null);

        List<String> replay = provider.replayCommand(context, failing);
        int filter = replay.indexOf("--filter");

        assertEquals(SwiftTestContexts.MISSING_SWIFT, replay.getFirst());
        assertTrue(replay.contains("test"), replay.toString());
        assertTrue(filter > 0, "the filter is SwiftPM's own option: " + replay);
        assertEquals("LedgerTests.LedgerTests/testAdd", replay.get(filter + 1),
                "the filter is <module-qualified suite>/<method>, exactly what a TestCaseResult carries: "
                        + replay);
        assertTrue(replay.contains("--disable-sandbox"),
                "a replay runs with the same run-owned options as the suite it is replaying: " + replay);

        assertEquals(List.of(), provider.replayCommand(context, null));
        assertEquals("xctest", provider.id());
    }

    @Test
    @DisplayName("there is no test command without an executable or without a test target, and no process is started")
    void testCommandRequiresAnExecutableAndATestTarget(@TempDir Path root, @TempDir Path run)
            throws IOException {
        SwiftTestProvider provider = new SwiftTestProvider();

        SwiftTestContexts.writePackageWithoutTests(root);
        VerificationContext noTests = SwiftTestContexts.context(
                SwiftTestContexts.withAttributes(root,
                        Map.of(SwiftToolchain.ATTR_SWIFT, SwiftTestContexts.MISSING_SWIFT)),
                run);
        assertTrue(provider.testCommand(noTests).isEmpty(),
                "there is no XCTest suite to run, and running swift test anyway would report nothing");

        Path withTests = root.resolve("with-tests");
        SwiftTestContexts.writePackageWithTests(withTests);
        VerificationContext noExecutable = SwiftTestContexts.context(withTests, run.resolve("no-exe"));
        assertTrue(provider.testCommand(noExecutable).isEmpty(),
                "no swift executable was resolved, so no command may be built");
    }

    @Test
    @DisplayName("the suite runs with no path selection, because SwiftPM decides which files are tests")
    void theSuiteCommandSelectsNoPaths(@TempDir Path root, @TempDir Path run) throws IOException {
        SwiftTestContexts.writePackageWithTests(root);
        VerificationContext context = SwiftTestContexts.context(
                SwiftTestContexts.withAttributes(root,
                        Map.of(SwiftToolchain.ATTR_SWIFT, SwiftTestContexts.MISSING_SWIFT)), run);

        List<String> command = new SwiftTestProvider().testCommand(context);

        assertEquals(SwiftTestContexts.MISSING_SWIFT, command.getFirst());
        assertEquals("test", command.get(1), command.toString());
        assertFalse(command.stream().anyMatch(part -> part.startsWith("Tests")),
                "a file outside a declared test target would not be compiled: " + command);
        assertEquals(context.artifacts().artifactsDirectory().resolve("swift").resolve("swift-test.log"),
                SwiftTestProvider.reportPath(context),
                "the console output is kept as the artefact the result was parsed from");
    }

    @Test
    @DisplayName("the test root is the recorded test target's directory, and null when the package declares none")
    void testRootComesFromTheDetector(@TempDir Path root, @TempDir Path run) throws IOException {
        SwiftTestContexts.writePackageWithTests(root);
        VerificationContext context = SwiftTestContexts.context(root, run);

        assertEquals(root.resolve("Tests/LedgerTests"), SwiftToolchain.testRoot(context, "LedgerTests"),
                "the module XCTest reports is the test target's own directory");
        assertEquals(root.resolve("Tests/LedgerTests"), SwiftToolchain.testRoot(context, "UnknownModule"),
                "an unnamed target falls back to the first detected test root");
        assertNull(SwiftToolchain.testRoot(context, null));
        assertNull(SwiftToolchain.testRoot(context, "  "));

        Path noTests = root.resolve("no-tests");
        SwiftTestContexts.writePackageWithoutTests(noTests);
        assertNull(SwiftToolchain.testRoot(SwiftTestContexts.context(noTests, run.resolve("plain")), "x"));
    }

    @Test
    @DisplayName("the providers claim Swift projects and no Java project")
    void providersClaimByLanguage(@TempDir Path root) {
        ProjectInfo swift = SwiftTestContexts.projectInfo(root);
        ProjectInfo java = ProjectInfo.builder(ProjectRef.of(root))
                .language(LanguageId.JAVA)
                .build();

        assertEquals("swiftpm-build", new SwiftBuildProvider().id());
        assertEquals("xctest", new SwiftTestProvider().id());
        assertTrue(new SwiftBuildProvider().supports(swift));
        assertTrue(new SwiftTestProvider().supports(swift));
        assertFalse(new SwiftBuildProvider().supports(java),
                "a Java project's build is the Java adapter's to run");
        assertFalse(new SwiftTestProvider().supports(java));
    }
}
