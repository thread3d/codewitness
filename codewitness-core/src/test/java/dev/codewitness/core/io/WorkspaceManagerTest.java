package dev.codewitness.core.io;

import dev.codewitness.core.io.WorkspaceManager.Mode;
import dev.codewitness.core.io.WorkspaceManager.Workspace;
import dev.codewitness.core.model.ProjectRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The workspace is the isolated copy a run operates on. Build output, VCS metadata and previous run
 * data must never be copied, and a copied build wrapper must still be executable.
 */
class WorkspaceManagerTest {

    private static Path projectWith(Path root, String relative, String content) throws IOException {
        Path file = root.resolve("project").resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }


    @Test
    @DisplayName("a symbolic link is not copied and is not followed")
    void symbolicLinksAreNotCopied(@TempDir Path root) throws IOException {
        // Following a link materialises data from outside the project into a run directory that is kept on
        // disk and travels with a bug report: `.env -> ~/.aws/credentials` copied the user's credentials into
        // .codewitness/runs/<id>/workspace/. Copying the link instead would leave the "isolated" copy able to
        // read outside itself. So a link is neither followed nor copied, and it is reported.
        Path project = Files.createDirectories(root.resolve("project"));
        Files.writeString(project.resolve("App.java"), "class App {}", StandardCharsets.UTF_8);
        Path secret = Files.writeString(root.resolve("secret.txt"), "TOP-SECRET", StandardCharsets.UTF_8);
        Files.createSymbolicLink(project.resolve("leak.txt"), secret);

        Workspace workspace = WorkspaceManager.prepare(
                new ProjectRef(project, "example", "example@1"), root.resolve("run"), Mode.COPY);
        Path copied = workspace.root().resolve("leak.txt");

        assertFalse(Files.exists(copied), "the link must not appear in the copy in any form");
        assertFalse(workspace.notCopied().isEmpty(), "a skipped link must be reported, not swallowed");
        assertTrue(workspace.notCopied().getFirst().toString().contains("leak.txt"),
                workspace.notCopied().toString());
        assertTrue(workspace.description().contains("symbolic link"),
                "the workspace description must mention the skipped link: " + workspace.description());
    }

    @Test
    @DisplayName("copying excludes target, .git, .codewitness and other build/VCS directories")
    void copyExcludesBuildAndVcsDirectories(@TempDir Path root) throws IOException {
        projectWith(root, "src/main/java/com/example/App.java", "package com.example; class App {}");
        projectWith(root, "target/classes/App.class", "compiled");
        projectWith(root, ".git/config", "[core]");
        projectWith(root, ".codewitness/runs/old/report.json", "{}");
        projectWith(root, "node_modules/left-pad/index.js", "module.exports = 1;");
        projectWith(root, "build/libs/app.jar", "jar");

        Workspace workspace = WorkspaceManager.prepare(ProjectRef.of(root.resolve("project")),
                root.resolve("run"), Mode.COPY);

        assertTrue(Files.exists(workspace.root().resolve("src/main/java/com/example/App.java")));
        assertFalse(Files.exists(workspace.root().resolve("target")), "build output must not be copied");
        assertFalse(Files.exists(workspace.root().resolve(".git")), "VCS metadata must not be copied");
        assertFalse(Files.exists(workspace.root().resolve(".codewitness")), "previous runs must not be copied");
        assertFalse(Files.exists(workspace.root().resolve("node_modules")));
        assertFalse(Files.exists(workspace.root().resolve("build")));
    }

    @Test
    @DisplayName("a source package named build, out or target is copied, not mistaken for build output")
    void sourcePackagesNamedLikeBuildOutputAreCopied(@TempDir Path root) throws IOException {
        // Regression: the exclusion list matches directory names at any depth, so it used to delete
        // CodeWitness's own dev.codewitness.java.build package from the isolated copy and the copied
        // project then failed to compile.
        projectWith(root, "src/main/java/com/example/build/Builder.java",
                "package com.example.build; class Builder {}");
        projectWith(root, "src/main/java/com/example/target/Target.java",
                "package com.example.target; class Target {}");
        projectWith(root, "src/test/java/com/example/out/OutTest.java",
                "package com.example.out; class OutTest {}");
        projectWith(root, "build/libs/app.jar", "jar");
        projectWith(root, "target/classes/App.class", "compiled");

        Workspace workspace = WorkspaceManager.prepare(ProjectRef.of(root.resolve("project")),
                root.resolve("run"), Mode.COPY);

        assertTrue(Files.exists(workspace.root().resolve("src/main/java/com/example/build/Builder.java")),
                "a package called build under src is source, not build output");
        assertTrue(Files.exists(workspace.root().resolve("src/main/java/com/example/target/Target.java")),
                "a package called target under src is source, not build output");
        assertTrue(Files.exists(workspace.root().resolve("src/test/java/com/example/out/OutTest.java")),
                "a package called out under src is source, not build output");
        assertFalse(Files.exists(workspace.root().resolve("build")),
                "build output at the project root must still be excluded");
        assertFalse(Files.exists(workspace.root().resolve("target")),
                "build output at the project root must still be excluded");
    }

    @Test
    @DisplayName("a copied project is marked isolated and described as such")
    void copiedWorkspaceIsIsolated(@TempDir Path root) throws IOException {
        projectWith(root, "pom.xml", "<project/>");

        Workspace workspace = WorkspaceManager.prepare(ProjectRef.of(root.resolve("project")),
                root.resolve("run"), Mode.COPY);

        assertTrue(workspace.isIsolated());
        assertEquals(Mode.COPY, workspace.mode());
        assertTrue(workspace.description().contains("isolated copy"), workspace.description());
        assertEquals(root.resolve("run/workspace").toAbsolutePath().normalize(), workspace.root());
    }

    @Test
    @DisplayName("the executable bit is restored on a copied mvnw")
    void executableBitIsRestoredOnMvnw(@TempDir Path root) throws IOException {
        Path mvnw = projectWith(root, "mvnw", "#!/bin/sh\necho maven\n");
        Files.setPosixFilePermissions(mvnw,
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        assertFalse(Files.isExecutable(mvnw), "precondition: the source wrapper is not executable");

        Workspace workspace = WorkspaceManager.prepare(ProjectRef.of(root.resolve("project")),
                root.resolve("run"), Mode.COPY);

        assertTrue(Files.isExecutable(workspace.root().resolve("mvnw")),
                "a non-executable mvnw is a very confusing build failure");
    }

    @Test
    @DisplayName("the executable bit is restored on shell scripts too")
    void executableBitIsRestoredOnShellScripts(@TempDir Path root) throws IOException {
        Path script = projectWith(root, "scripts/verify.sh", "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(script,
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));

        Workspace workspace = WorkspaceManager.prepare(ProjectRef.of(root.resolve("project")),
                root.resolve("run"), Mode.COPY);

        assertTrue(Files.isExecutable(workspace.root().resolve("scripts/verify.sh")));
    }

    @Test
    @DisplayName("IN_PLACE operates on the project itself and says so")
    void inPlaceWorkspaceUsesTheProjectRoot(@TempDir Path root) throws IOException {
        projectWith(root, "pom.xml", "<project/>");

        Workspace workspace = WorkspaceManager.prepare(ProjectRef.of(root.resolve("project")),
                root.resolve("run"), Mode.IN_PLACE);

        assertFalse(workspace.isIsolated());
        assertEquals(Mode.IN_PLACE, workspace.mode());
        assertEquals(root.resolve("project").toAbsolutePath().normalize(), workspace.root());
        assertTrue(workspace.description().contains("in place"), workspace.description());
    }

    @Test
    @DisplayName("file contents are copied verbatim")
    void copyPreservesFileContent(@TempDir Path root) throws IOException {
        String content = "line one\nline two\n\u20ac1,000\n";
        projectWith(root, "src/main/resources/data.txt", content);

        Workspace workspace = WorkspaceManager.prepare(ProjectRef.of(root.resolve("project")),
                root.resolve("run"), Mode.COPY);

        assertEquals(content, Files.readString(workspace.root().resolve("src/main/resources/data.txt")));
    }

    @Test
    @DisplayName("copyTree rejects a source that is not a directory")
    void copyTreeRejectsNonDirectory(@TempDir Path root) throws IOException {
        Path file = root.resolve("file.txt");
        Files.writeString(file, "not a directory");

        assertThrows(IllegalArgumentException.class,
                () -> WorkspaceManager.copyTree(file, root.resolve("target")));
    }

    @Test
    @DisplayName("copyTree copies an existing directory")
    void copyTreeCopiesDirectory(@TempDir Path root) throws IOException {
        Path source = root.resolve("source");
        Files.createDirectories(source.resolve("nested"));
        Files.writeString(source.resolve("nested/file.txt"), "hello");

        Path target = root.resolve("target");
        WorkspaceManager.copyTree(source, target);

        assertEquals("hello", Files.readString(target.resolve("nested/file.txt")));
    }

    @Test
    @DisplayName("the excluded directory list is exposed for reports")
    void excludedDirectoriesAreExposed() {
        assertTrue(WorkspaceManager.excludedDirectories().contains("target"));
        assertTrue(WorkspaceManager.excludedDirectories().contains(".git"));
        assertTrue(WorkspaceManager.excludedDirectories().contains(".codewitness"));
    }

    @Test
    @DisplayName("a Workspace normalises its root to an absolute path")
    void workspaceNormalisesRoot(@TempDir Path root) {
        Workspace workspace = new Workspace(root.resolve("a/../b"), Mode.COPY, "description", List.of());

        assertEquals(root.resolve("b").toAbsolutePath().normalize(), workspace.root());
    }
}
