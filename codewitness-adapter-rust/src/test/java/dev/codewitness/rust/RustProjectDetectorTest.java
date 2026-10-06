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
