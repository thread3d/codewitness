package dev.codewitness.rust.dependency;

import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.rust.RustTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The dependency inventory reads every Cargo dependency table, in all of its spellings. */
class RustDependencyAnalyzerTest {

    @Test
    @DisplayName("inline, table-style, dev and target-specific dependencies are all inventoried")
    void readsEveryDependencyTable() {
        String manifest = """
                [package]
                name = "sample"
                version = "0.1.0"

                [dependencies]
                serde = { version = "1", features = ["derive"] }
                regex = "1"

                [dev-dependencies]
                tempfile = "3"

                [target.'cfg(unix)'.dependencies]
                libc = "0.2"

                [dependencies.tokio]
                version = "1"
                features = ["rt"]
                """;

        List<String> names = RustDependencyAnalyzer.parse(manifest);

        assertEquals(List.of("serde", "regex", "tempfile", "libc", "tokio"), names);
    }

    @Test
    @DisplayName("a manifest with no dependencies declares none, and that is not an error")
    void emptyDependencySet() {
        assertEquals(List.of(), RustDependencyAnalyzer.parse("[package]\nname = \"sample\"\n\n[dependencies]\n"));
    }

    @Test
    @DisplayName("Cargo.lock is preferred, and every crate it resolves carries its version")
    void readsResolvedVersionsFromCargoLock(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        RustTestContexts.write(root, "Cargo.toml", """
                [package]
                name = "sample"
                version = "0.1.0"

                [dependencies]
                serde = "1"
                """);
        RustTestContexts.write(root, "Cargo.lock", """
                [[package]]
                name = "sample"
                version = "0.1.0"

                [[package]]
                name = "serde"
                version = "1.0.193"
                source = "registry+https://github.com/rust-lang/crates.io-index"

                [[package]]
                name = "serde_derive"
                version = "1.0.193"
                source = "registry+https://github.com/rust-lang/crates.io-index"
                """);
        VerificationContext context = RustTestContexts.context(root, runDirectory);

        DependencyReport report = new RustDependencyAnalyzer().analyseDependencies(context);

        assertEquals("cargo-lock", report.tool());
        // The fixture's own crate is in the lock file too, and it is not a dependency of itself: a
        // [[package]] with no source is built from this tree rather than fetched from a registry.
        assertEquals(2, report.totalDependencies());
        assertEquals(List.of("serde@1.0.193", "serde_derive@1.0.193"), report.directDependencies());
        assertEquals("crates.io", report.coordinates().getFirst().ecosystem());
        assertTrue(report.coordinates().stream().allMatch(coordinate -> coordinate.resolved()),
                "every coordinate from a lock file must be queryable: " + report.coordinates());
    }

    @Test
    @DisplayName("without a lock file the declared crates are inventoried but are not queryable")
    void declaredCratesCarryNoResolvedVersion(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        RustTestContexts.write(root, "Cargo.toml", """
                [package]
                name = "sample"
                version = "0.1.0"

                [dependencies]
                serde = "1"
                """);
        VerificationContext context = RustTestContexts.context(root, runDirectory);

        DependencyReport report = new RustDependencyAnalyzer().analyseDependencies(context);

        assertEquals("cargo-manifest", report.tool());
        assertEquals(List.of("serde"), report.directDependencies());
        assertTrue(report.coordinates().stream().noneMatch(coordinate -> coordinate.resolved()),
                "a version range is not a resolved version: " + report.coordinates());
        assertTrue(report.note().contains("no Cargo.lock"), report.note());
    }
}
