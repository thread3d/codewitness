package dev.codewitness.rust;

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

/** Detection of Cargo projects and their conventional layout. */
class RustProjectDetectorTest {

    @Test
    @DisplayName("a Cargo project is detected with its crate name, edition and directories")
    void detectsCargoProject(@TempDir Path root) throws IOException {
        RustTestContexts.write(root, "Cargo.toml", """
                [package]
                name = "withdrawal-ledger"
                version = "0.1.0"
                edition = "2021"

                [dependencies]
                serde = "1"
                """);
        RustTestContexts.write(root, "src/lib.rs", "pub fn value() -> i64 { 1 }\n");
        RustTestContexts.write(root, "tests/ledger.rs", "#[test]\nfn works() {}\n");

        ProjectInfo info = RustProjectDetector.inspect(ProjectRef.of(root));

        assertEquals(LanguageId.RUST, info.language());
        assertEquals(BuildSystemId.CARGO, info.buildSystem());
        assertEquals("withdrawal_ledger", info.attributes().get(RustProjectDetector.ATTR_CRATE_NAME));
        assertEquals("2021", info.attributes().get(RustProjectDetector.ATTR_EDITION));
        assertEquals(1, info.sourceDirs().size());
        assertEquals(1, info.testSourceDirs().size());
        assertTrue(info.sourceExtensions().contains("rs"));
        assertTrue(info.testFrameworks().contains("libtest"));
        assertEquals("rs", info.attributes().get(RustProjectDetector.ATTR_SOURCE_EXTENSIONS));
    }

    @Test
    @DisplayName("Rust sources without a manifest are detected but cannot be built")
    void detectsBareRustSourcesWithAWarning(@TempDir Path root) throws IOException {
        RustTestContexts.write(root, "src/main.rs", "fn main() {}\n");

        ProjectInfo info = RustProjectDetector.inspect(ProjectRef.of(root));

        assertEquals(LanguageId.RUST, info.language());
        assertEquals(BuildSystemId.NONE, info.buildSystem());
        assertEquals("none", info.attributes().get(RustProjectDetector.ATTR_DESCRIPTOR));
        assertTrue(info.attributes().containsKey(RustProjectDetector.ATTR_DETECTION_WARNING));
        assertTrue(RustProjectDetector.looksLikeRustProject(root));
    }

    @Test
    @DisplayName("a directory with neither manifest nor Rust sources is not a Rust project")
    void rejectsNonRustProject(@TempDir Path root) throws IOException {
        RustTestContexts.write(root, "README.md", "nothing to see\n");

        assertFalse(RustProjectDetector.looksLikeRustProject(root));
    }

    @Test
    @DisplayName("a crate whose manifest is below the project root is detected and built from that directory")
    void detectsNestedCrate(@TempDir Path root) throws IOException {
        RustTestContexts.write(root, "README.md", "the crate lives in rmath/\n");
        RustTestContexts.write(root, "rmath/Cargo.toml", """
                [package]
                name = "rmath"
                version = "0.1.0"
                edition = "2021"
                """);
        RustTestContexts.write(root, "rmath/src/lib.rs", "pub fn add(a: i64, b: i64) -> i64 { a + b }\n");
        RustTestContexts.write(root, "rmath/tests/math.rs", "#[test]\nfn add_works() {}\n");

        ProjectInfo info = RustProjectDetector.inspect(ProjectRef.of(root));

        assertEquals(BuildSystemId.CARGO, info.buildSystem(),
                "a nested crate is buildable, so the tool must not report no build system");
        assertEquals("rmath/Cargo.toml", info.attributes().get(RustProjectDetector.ATTR_MANIFEST));
        assertEquals("rmath", info.attributes().get(RustProjectDetector.ATTR_CRATE_NAME));
        assertEquals("2021", info.attributes().get(RustProjectDetector.ATTR_EDITION));
        assertEquals(root.resolve("rmath"), info.buildRoot(),
                "the build root is the directory Cargo's own manifest search would need");
        assertFalse(info.attributes().containsKey(RustProjectDetector.ATTR_DETECTION_WARNING));
        assertEquals(1, info.sourceDirs().size());
        assertEquals(root.resolve("rmath/src"), info.sourceDirs().getFirst());
        assertEquals(1, info.testSourceDirs().size());
        assertEquals(root.resolve("rmath/tests"), info.testSourceDirs().getFirst());
    }

    @Test
    @DisplayName("a nested crate without a declared name is named after its directory, as Cargo names it")
    void namesNestedCrateAfterItsDirectory(@TempDir Path root) throws IOException {
        RustTestContexts.write(root, "rmath/Cargo.toml", "[package]\nversion = \"0.1.0\"\n");
        RustTestContexts.write(root, "rmath/src/lib.rs", "pub fn add() {}\n");

        ProjectInfo info = RustProjectDetector.inspect(ProjectRef.of(root));

        assertEquals("rmath", info.attributes().get(RustProjectDetector.ATTR_CRATE_NAME));
    }

    @Test
    @DisplayName("the shallowest manifest is chosen, and the others are recorded rather than silently dropped")
    void choosesShallowestManifest(@TempDir Path root) throws IOException {
        RustTestContexts.write(root, "alpha/Cargo.toml", "[package]\nname = \"alpha\"\n");
        RustTestContexts.write(root, "alpha/src/lib.rs", "pub fn a() {}\n");
        RustTestContexts.write(root, "alpha/deep/Cargo.toml", "[package]\nname = \"deep\"\n");
        RustTestContexts.write(root, "alpha/deep/src/lib.rs", "pub fn d() {}\n");
        RustTestContexts.write(root, "beta/Cargo.toml", "[package]\nname = \"beta\"\n");
        RustTestContexts.write(root, "beta/src/lib.rs", "pub fn b() {}\n");

        ProjectInfo info = RustProjectDetector.inspect(ProjectRef.of(root));

        assertEquals("alpha/Cargo.toml", info.attributes().get(RustProjectDetector.ATTR_MANIFEST));
        assertEquals("alpha", info.attributes().get(RustProjectDetector.ATTR_CRATE_NAME));
        assertTrue(info.attributes().get(RustProjectDetector.ATTR_DETECTION_WARNING)
                        .contains("alpha/Cargo.toml"),
                "the choice of crate must be stated where a reader looks first: "
                        + info.attributes().get(RustProjectDetector.ATTR_DETECTION_WARNING));
        assertEquals("alpha/Cargo.toml,beta/Cargo.toml,alpha/deep/Cargo.toml",
                info.attributes().get(RustProjectDetector.ATTR_MANIFESTS),
                "every manifest found is recorded, shallowest first, so the report can name the crates "
                        + "that were not built");
    }

    @Test
    @DisplayName("a sample project under fixtures is not mistaken for the repository's own crate")
    void ignoresManifestsInSampleTrees(@TempDir Path root) throws IOException {
        RustTestContexts.write(root, "pom.xml", "<project/>\n");
        RustTestContexts.write(root, "fixtures/sample-service/Cargo.toml", "[package]\nname = \"sample\"\n");
        RustTestContexts.write(root, "fixtures/sample-service/src/lib.rs", "pub fn s() {}\n");

        ProjectInfo info = RustProjectDetector.inspect(ProjectRef.of(root));

        assertEquals(BuildSystemId.NONE, info.buildSystem(),
                "a repository's fixtures are inputs to its tests, not code the run set out to verify");
        assertFalse(RustProjectDetector.looksLikeRustProject(root),
                "claiming one would build a sample crate while verifying the repository");
    }

    @Test
    @DisplayName("a manifest inside build output or a vendor tree is not the project's manifest")
    void ignoresManifestsInExcludedDirectories(@TempDir Path root) throws IOException {
        RustTestContexts.write(root, "target/debug/build/leftover/Cargo.toml", "[package]\nname = \"left\"\n");
        RustTestContexts.write(root, "vendor/dep/Cargo.toml", "[package]\nname = \"dep\"\n");
        RustTestContexts.write(root, "src/main.rs", "fn main() {}\n");

        ProjectInfo info = RustProjectDetector.inspect(ProjectRef.of(root));

        assertEquals(BuildSystemId.NONE, info.buildSystem(),
                "generated or vendored manifests must not be mistaken for the project's own");
        assertFalse(info.attributes().containsKey(RustProjectDetector.ATTR_MANIFEST));
        assertTrue(info.attributes().containsKey(RustProjectDetector.ATTR_DETECTION_WARNING));
    }

    @Test
    @DisplayName("a hyphenated package name is normalised to the crate identifier Rust uses")
    void normalisesCrateName(@TempDir Path root) throws IOException {
        RustTestContexts.write(root, "Cargo.toml", "[package]\nname = \"my-crate\"\n");

        assertEquals("my_crate", RustProjectDetector.detectCrateName(ProjectRef.of(root), root));
    }

    @Test
    @DisplayName("the toolchain channel is read from rust-toolchain.toml")
    void readsToolchain(@TempDir Path root) throws IOException {
        RustTestContexts.write(root, "Cargo.toml", "[package]\nname = \"sample\"\n");
        RustTestContexts.write(root, "rust-toolchain.toml", "[toolchain]\nchannel = \"1.98.0\"\n");

        assertEquals("1.98.0", RustProjectDetector.detectRustVersion(root));
    }

    @Test
    @DisplayName("build output is never treated as source")
    void excludesTargetDirectory(@TempDir Path root) throws IOException {
        RustTestContexts.write(root, "src/lib.rs", "pub fn value() -> i64 { 1 }\n");
        RustTestContexts.write(root, "target/debug/build/generated.rs", "pub fn generated() {}\n");

        assertEquals(1, RustProjectDetector.rustFiles(root).size());
    }

    @Test
    @DisplayName("a workspace under .codewitness is not excluded by its own run directory")
    void excludesRelativeToTheWalkedRoot(@TempDir Path root) throws IOException {
        Path workspace = root.resolve(".codewitness/runs/CW-TEST/workspace");
        RustTestContexts.write(workspace, "src/lib.rs", "pub fn value() -> i64 { 1 }\n");
        RustTestContexts.write(workspace, "target/debug/generated.rs", "pub fn generated() {}\n");

        // The run workspace legitimately lives under .codewitness; an absolute exclusion check would
        // reject every file in it, leaving analysis and mutation silently with nothing to do.
        assertEquals(1, RustProjectDetector.rustFiles(workspace).size());
    }
}
