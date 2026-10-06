package dev.codewitness.cli;

import dev.codewitness.core.io.WorkspaceManager;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code codewitness inspect} only reads the project: it detects the language, the build system and
 * the modules and never runs a build. That makes it safe to exercise against the real fixture.
 */
class InspectCommandTest {

    @TempDir
    Path tmp;

    @Test
    void inspectReportsTheDetectedBuildSystem() throws IOException {
        Path fixture = CliTestSupport.repositoryRoot().resolve("fixtures").resolve("withdrawal-ledger");
        Assumptions.assumeTrue(Files.isDirectory(fixture), "fixture withdrawal-ledger is not present");

        // Inspect writes its workspace below the project, so run it against a copy of the fixture
        // instead of the checkout itself.
        Path project = tmp.resolve("withdrawal-ledger");
        WorkspaceManager.copyTree(fixture, project);

        CliTestSupport.Result result = CliTestSupport.run("inspect", project.toString());

        assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
        assertTrue(result.stdout().contains("CODEWITNESS  inspect"), result.stdout());
        assertTrue(result.stdout().contains("build system     maven"),
                "inspect must say which build system it detected: " + result.stdout());
        assertTrue(result.stdout().contains("language         java"), result.stdout());
        assertTrue(result.stdout().contains("modules"), result.stdout());
    }

    @Test
    void inspectWarnsWhenThereIsNoBuildDescriptor() throws IOException {
        Path project = tmp.resolve("plain");
        Files.createDirectories(project.resolve("src/main/java/com/example"));

        CliTestSupport.Result result = CliTestSupport.run("inspect", project.toString());

        assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
        assertTrue(result.stdout().contains("build system     none"), result.stdout());
        assertTrue(result.stdout().contains("WARNING"),
                "a project that cannot be built must be flagged: " + result.stdout());
        assertTrue(result.stdout().contains("no pom.xml or Gradle build file"), result.stdout());
    }

    @Test
    void inspectOnANonExistentDirectoryFails() {
        CliTestSupport.Result result = CliTestSupport.run("inspect", tmp.resolve("nope").toString());

        assertNotEquals(0, result.exitCode(), "inspecting a missing project must not look like success");
    }
}
