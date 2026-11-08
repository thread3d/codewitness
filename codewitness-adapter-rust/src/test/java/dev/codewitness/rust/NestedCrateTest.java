package dev.codewitness.rust;

import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.rust.dependency.RustDependencyAnalyzer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A crate whose manifest is below the project root: every phase must name that manifest.
 *
 * <p>Cargo looks for a manifest in its working directory and resolves nothing above it, so a crate one
 * directory down is invisible to a bare {@code cargo build} started at the project root. Detection finds
 * the manifest once; these tests pin that the build, the tests, the dependency inventory and the note the
 * report carries all use it, because a phase that falls back to the project root is the defect itself.
 */
class NestedCrateTest {

    /** Writes a project whose only crate is {@code rmath/}, with no manifest at the root. */
    private static Path nestedCrate(Path root) throws IOException {
        RustTestContexts.write(root, "README.md", "the crate lives in rmath/\n");
        RustTestContexts.write(root, "rmath/Cargo.toml", """
                [package]
                name = "rmath"
                version = "0.1.0"
                edition = "2021"

                [dependencies]
                serde = "1"
                """);
        RustTestContexts.write(root, "rmath/src/lib.rs", "pub fn add(a: i64, b: i64) -> i64 { a + b }\n");
        RustTestContexts.write(root, "rmath/tests/math.rs", "#[test]\nfn add_works() {}\n");
        return root.resolve("rmath");
    }

    @Test
    @DisplayName("the nested manifest is what the run resolves, and what Cargo is pointed at")
    void resolvesTheNestedManifest(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        Path crate = nestedCrate(root);
        VerificationContext context = RustTestContexts.context(root, runDirectory);

        assertTrue(Cargo.hasManifest(context), "the nested manifest is the project's manifest");
        assertEquals(crate.resolve("Cargo.toml"), Cargo.manifest(context));
        assertEquals(crate, Cargo.crateRoot(context));
        assertEquals(List.of("--manifest-path", "rmath/Cargo.toml"), Cargo.manifestArguments(context));
    }

    @Test
    @DisplayName("a manifest at the project root is used without naming it, so the command is unchanged")
    void leavesARootManifestAlone(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        RustTestContexts.write(root, "Cargo.toml", "[package]\nname = \"sample\"\n");
        RustTestContexts.write(root, "src/lib.rs", "pub fn value() {}\n");
        VerificationContext context = RustTestContexts.context(root, runDirectory);

        assertEquals(List.of(), Cargo.manifestArguments(context));
        assertEquals(root, Cargo.crateRoot(context));
    }

    @Test
    @DisplayName("the build command names the nested manifest")
    void buildCommandNamesTheManifest(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        nestedCrate(root);
        VerificationContext context = RustTestContexts.context(root, runDirectory);

        List<String> command = new RustBuildProvider().command(context);

        assertEquals(List.of("build", "--manifest-path", "rmath/Cargo.toml", "--all-targets"),
                command.subList(1, command.size()), command.toString());
        assertTrue(command.getFirst().endsWith("cargo"), command.toString());
    }

    @Test
    @DisplayName("the test command a counterexample replays names the nested manifest")
    void testCommandNamesTheManifest(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        nestedCrate(root);
        VerificationContext context = RustTestContexts.context(root, runDirectory);

        List<String> command = new RustTestProvider().testCommand(context);

        assertEquals(List.of("test", "--manifest-path", "rmath/Cargo.toml", "--no-fail-fast"),
                command.subList(1, command.size()), command.toString());
    }

    @Test
    @DisplayName("the dependency inventory reads the nested crate's manifest rather than finding nothing")
    void dependencyInventoryReadsTheNestedManifest(@TempDir Path root, @TempDir Path runDirectory)
            throws IOException {
        nestedCrate(root);
        VerificationContext context = RustTestContexts.context(root, runDirectory);

        DependencyReport report = new RustDependencyAnalyzer().analyseDependencies(context);

        assertTrue(report.analysed(), "a nested manifest is a manifest: " + report.note());
        assertEquals(List.of("serde"), report.directDependencies());
    }

    @Test
    @DisplayName("the run records that it built one crate, and names the ones it did not")
    void recordsWhichCrateWasBuilt(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        nestedCrate(root);
        RustTestContexts.write(root, "beta/Cargo.toml", "[package]\nname = \"beta\"\n");
        RustTestContexts.write(root, "beta/src/lib.rs", "pub fn b() {}\n");
        VerificationContext context = RustTestContexts.context(root, runDirectory);
        ProjectInfo info = RustProjectDetector.inspect(ProjectRef.of(root));

        RustLanguageAdapter.noteManifestLocation(context, info);

        String notes = String.join("\n", context.state().infrastructureNotes());
        assertTrue(notes.contains("rmath/Cargo.toml"), notes);
        assertTrue(notes.contains("beta/Cargo.toml"),
                "a crate that was not built must be named, not silently dropped: " + notes);
    }

    @Test
    @DisplayName("a project whose only manifest is at its root records no manifest note")
    void recordsNoNoteForARootManifest(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        RustTestContexts.write(root, "Cargo.toml", "[package]\nname = \"sample\"\n");
        RustTestContexts.write(root, "src/lib.rs", "pub fn value() {}\n");
        VerificationContext context = RustTestContexts.context(root, runDirectory);

        RustLanguageAdapter.noteManifestLocation(context, RustProjectDetector.inspect(ProjectRef.of(root)));

        assertTrue(context.state().infrastructureNotes().isEmpty(),
                context.state().infrastructureNotes().toString());
    }
}
