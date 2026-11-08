package dev.codewitness.rust.mutation;

import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.rust.RustTestContexts;
import dev.codewitness.support.mutation.CommandMutationProfile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mutation loop's Rust half: where its manifest is, and whether mutants can be compiled at all.
 *
 * <p>The shared loop runs its command in the project root, which is not where Cargo looks for a manifest
 * when the crate lives in a subdirectory, so the profile has to name it. This is the difference between
 * mutation evidence and a report that says every mutant was non-viable.
 */
class RustMutationProfileTest {

    /** A project whose only crate is {@code rmath/}, with no manifest at the root. */
    private static void nestedCrate(Path root) throws IOException {
        Files.createDirectories(root.resolve("rmath/src"));
        Files.writeString(root.resolve("rmath/Cargo.toml"), "[package]\nname = \"rmath\"\n",
                StandardCharsets.UTF_8);
        Files.writeString(root.resolve("rmath/src/lib.rs"), "pub fn add(a: i64, b: i64) -> i64 { a + b }\n",
                StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a nested crate is mutated through its own manifest, not through the project root")
    void namesTheNestedManifest(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        nestedCrate(root);
        VerificationContext context = RustTestContexts.context(root, runDirectory);
        CommandMutationProfile profile = new RustMutationTester.RustMutationProfile();

        assertNull(profile.manifestProblem(context), "a nested manifest is a manifest mutants can build");
        List<String> command = profile.testCommand(context.projectInfo(), context);

        assertEquals(List.of("test", "--manifest-path", "rmath/Cargo.toml", "--no-fail-fast", "--quiet"),
                command.subList(1, command.size()), command.toString());
        assertTrue(command.getFirst().endsWith("cargo"), command.toString());
    }

    @Test
    @DisplayName("a tree with no manifest at all says mutants cannot be compiled")
    void reportsAMissingManifest(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/lib.rs"), "pub fn add() {}\n", StandardCharsets.UTF_8);
        VerificationContext context = RustTestContexts.context(root, runDirectory);
        CommandMutationProfile profile = new RustMutationTester.RustMutationProfile();

        String problem = profile.manifestProblem(context);

        assertNotNull(problem);
        assertTrue(problem.contains("no Cargo.toml was found"), problem);
    }
}
