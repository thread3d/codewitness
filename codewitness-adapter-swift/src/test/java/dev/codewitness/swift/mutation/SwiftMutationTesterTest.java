package dev.codewitness.swift.mutation;

import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.swift.SwiftTestContexts;
import dev.codewitness.swift.SwiftToolchain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mutation tester's contract for the outcomes that need no toolchain.
 *
 * <p>Nothing here spawns {@code swift}: the parts that do - writing a mutant, compiling it, running the
 * suite - are covered by the lexis tests and by the end-to-end fixture, and a test that needed a Swift
 * toolchain installed to assert "the probe failed" would be asserting the machine rather than the
 * adapter. What these tests pin is the difference the whole profile exists for: a mutant the compiler
 * rejected is not a kill, and a project that cannot judge any mutant is a reason rather than a score of
 * zero.
 */
class SwiftMutationTesterTest {

    @Test
    @DisplayName("the tester names itself as the adapter documents and claims only Swift projects")
    void namesItselfAndClaimsOnlySwift(@TempDir Path root) {
        SwiftMutationTester tester = new SwiftMutationTester();

        assertEquals("swift-mutation", tester.id());
        assertTrue(tester.supports(SwiftTestContexts.projectInfo(root)));
        assertFalse(tester.supports(ProjectInfo.builder(ProjectRef.of(root))
                        .language(LanguageId.JAVA)
                        .build()),
                "a Java project's mutation is the Java adapter's to measure");
    }

    @Test
    @DisplayName("the project's sources are mutated and its tests are not")
    void theTestPathRuleKeepsTheOracleOut() {
        assertTrue(SwiftMutationTester.isTestPath("Tests/LedgerTests/LedgerTests.swift"));
        assertTrue(SwiftMutationTester.isTestPath("Test/Ledger.swift"),
                "the singular conventional directory answers it too");
        assertTrue(SwiftMutationTester.isTestPath("LedgerTests.swift"),
                "the naming convention answers it for a flat layout");
        assertTrue(SwiftMutationTester.isTestPath("LedgerTest.swift"));
        assertTrue(SwiftMutationTester.isTestPath("Tests\\LedgerTests\\Thing.swift"),
                "a Windows-separated path is normalized before the directory element is read");
        assertFalse(SwiftMutationTester.isTestPath("Sources/Ledger/Ledger.swift"),
                "mutating the oracle would measure the test suite against itself");
        assertFalse(SwiftMutationTester.isTestPath("Sources/Ledger/testing.swift"),
                "a name is not a directory element, so an ordinary file called testing.swift is source");
        assertFalse(SwiftMutationTester.isTestPath("Sources/Ledger/Contest.swift"),
                "only the Tests/Test spellings count, not any name that contains them");
    }

    @Test
    @DisplayName("a failed probe is reported first, even for a project that otherwise has a test target")
    void aFailedProbeIsReportedRatherThanMutated(@TempDir Path root, @TempDir Path run) throws IOException {
        SwiftTestContexts.writePackageWithTests(root);
        VerificationContext context = context(root, run,
                Map.of(SwiftToolchain.ATTR_PROBE_FAILURE, "swift: not found"));

        String problem = SwiftMutationTester.manifestProblem(context);

        assertNotNull(problem);
        assertTrue(problem.contains("toolchain probe failed"), problem);
        assertTrue(problem.contains("swift: not found"), "the reason must carry the probe's own words: "
                + problem);
    }

    @Test
    @DisplayName("a package with no test target is refused before the toolchain is even resolved")
    void aPackageWithoutTestsIsRefused(@TempDir Path root, @TempDir Path run) throws IOException {
        SwiftTestContexts.writePackageWithoutTests(root);
        VerificationContext context = context(root, run,
                Map.of(SwiftToolchain.ATTR_SWIFT, SwiftTestContexts.MISSING_SWIFT));

        String problem = SwiftMutationTester.manifestProblem(context);

        assertNotNull(problem, "no test exists that could ever kill a mutant");
        assertTrue(problem.contains("no test target"), problem);
        assertFalse(context.projectInfo().hasTests());
    }

    @Test
    @DisplayName("a runnable package has no manifest problem, and its command is swift test with the run's caches")
    void aRunnablePackageHasNoManifestProblemAndRunsSwiftTest(@TempDir Path root, @TempDir Path run)
            throws IOException {
        SwiftTestContexts.writePackageWithTests(root);
        VerificationContext context = context(root, run,
                Map.of(SwiftToolchain.ATTR_SWIFT, SwiftTestContexts.MISSING_SWIFT));

        assertNull(SwiftMutationTester.manifestProblem(context));

        List<String> command = SwiftMutationTester.testCommand(context.projectInfo(), context);

        assertEquals(SwiftTestContexts.MISSING_SWIFT, command.getFirst());
        assertEquals("test", command.get(1), command.toString());
        assertTrue(command.contains("--disable-sandbox"), command.toString());
        assertTrue(command.stream().anyMatch(part -> part.replace('\\', '/').endsWith("swift/cache")),
                "the run's own cache is inside the artefact store: " + command);
    }

    @Test
    @DisplayName("the recorded swift executable is preferred over a fresh machine lookup")
    void theRecordedExecutableIsPreferred(@TempDir Path root, @TempDir Path run) throws IOException {
        SwiftTestContexts.writePackageWithTests(root);
        VerificationContext context = context(root, run,
                Map.of(SwiftToolchain.ATTR_SWIFT, SwiftTestContexts.MISSING_SWIFT));

        assertEquals(SwiftTestContexts.MISSING_SWIFT, SwiftMutationTester.swift(context.projectInfo(), context));
    }

    @Test
    @DisplayName("a mutant the compiler rejected is non-viable, never a kill the suite never made")
    void aCompileErrorIsNotAKill() {
        assertNotNull(SwiftMutationTester.nonViableReason(null),
                "a run that printed nothing observed nothing");
        assertNotNull(SwiftMutationTester.nonViableReason(""));
        assertTrue(SwiftMutationTester.nonViableReason(null).contains("no output"));

        String compileError = "/work/Sources/Ledger/Broken.swift:3:5: error: cannot find 'x' in scope\n";
        String reason = SwiftMutationTester.nonViableReason(compileError);

        assertNotNull(reason, "a compile error with no XCTest line is a build failure, not a test failure");
        assertTrue(reason.contains("did not build"), reason);
        assertTrue(reason.contains("cannot find"), "the compiler's own words are carried: " + reason);

        String bareError = "error: fatal issue with the package manifest\n";
        assertNotNull(SwiftMutationTester.nonViableReason(bareError),
                "a module-level error with no case line is a build failure too");
    }

    @Test
    @DisplayName("a run that built and ran a case is left to the exit status, whatever the output also says")
    void aRunThatTestedIsLeftToTheExitStatus() {
        String failing = """
                Test Case '-[LedgerTests.LedgerTests testAdd]' started.
                /work/Tests/LedgerTests/LedgerTests.swift:6: error: -[LedgerTests.LedgerTests testAdd] : XCTAssertEqual failed
                Test Case '-[LedgerTests.LedgerTests testAdd]' failed (0.100 seconds).
                """;

        assertNull(SwiftMutationTester.nonViableReason(failing),
                "a case failed because the mutant changed behaviour: that is the suite doing its job");

        String passed = """
                Test Case '-[LedgerTests.LedgerTests testAdd]' started.
                Test Case '-[LedgerTests.LedgerTests testAdd]' passed (0.002 seconds).
                """;

        assertNull(SwiftMutationTester.nonViableReason(passed),
                "a survivor is decided by the exit status, not by this method");
    }

    @Test
    @DisplayName("an output with no case line at all is non-viable, the suite banner included")
    void aRunWithoutACaseLineIsNonViable() {
        String bannerOnly = """
                error: fatal issue with the package manifest
                Test Suite 'All tests' started at 2026-10-12 07:14:06.147.
                """;
        String reason = SwiftMutationTester.nonViableReason(bannerOnly);

        assertNotNull(reason, "the suite banner is evidence a build failure was not total, not that "
                + "anything was tested");
        assertTrue(reason.contains("no XCTest case"), reason);

        String building = "Building for debugging...\nCompiling Ledger\n";
        assertNotNull(SwiftMutationTester.nonViableReason(building));
        assertTrue(SwiftMutationTester.nonViableReason(building).contains("no XCTest case"));
    }

    // ------------------------------------------------------------------ helpers

    private static VerificationContext context(Path root, Path run, Map<String, String> attributes) {
        return SwiftTestContexts.context(SwiftTestContexts.withAttributes(root, attributes), run);
    }
}
