package dev.codewitness.core.spi;

import dev.codewitness.core.config.VerificationConfig;
import dev.codewitness.core.evidence.EvidenceLedger;
import dev.codewitness.core.exec.LocalProcessSandbox;
import dev.codewitness.core.io.ArtifactStore;
import dev.codewitness.core.io.WorkspaceManager;
import dev.codewitness.core.model.ModuleInfo;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.result.Budget;
import dev.codewitness.core.result.BudgetUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The context is the only route from a strategy to the outside world, and {@code rebase} is the one
 * place where a path discovered in the user's project becomes a path inside the isolated copy.
 */
class VerificationContextTest {

    private static VerificationContext context(Path projectRoot, Path workspaceRoot) {
        return context(projectRoot, workspaceRoot, WorkspaceManager.Mode.COPY, ProgressListener.silent());
    }

    private static VerificationContext context(
            Path projectRoot, Path workspaceRoot, WorkspaceManager.Mode mode, ProgressListener listener) {
        ProjectRef ref = ProjectRef.of(projectRoot);
        ProjectInfo info = ProjectInfo.builder(ref)
                .buildRoot(projectRoot)
                .buildWrapper(projectRoot.resolve("mvnw"))
                .addDetectedDescriptor(projectRoot.resolve("pom.xml"))
                .addModule(new ModuleInfo("root", projectRoot, "jar",
                        List.of(projectRoot.resolve("src/main/java")),
                        List.of(projectRoot.resolve("src/test/java")), null))
                .build();
        return VerificationContext.builder()
                .runId("run-1")
                .projectRef(ref)
                .projectInfo(info)
                .config(VerificationConfig.defaults())
                .workspace(new WorkspaceManager.Workspace(workspaceRoot, mode, "test workspace"))
                .artifacts(new ArtifactStore(workspaceRoot.getParent().resolve("artifacts")))
                .budget(new BudgetUsage.Tracker(Budget.defaults()))
                .sandbox(new LocalProcessSandbox(List.of(projectRoot)))
                .progress(listener)
                .evidenceLedger(new EvidenceLedger("run-1"))
                .build();
    }

    @Test
    @DisplayName("a path already inside the workspace is returned unchanged")
    void pathAlreadyInsideWorkspaceIsUnchanged(@TempDir Path root) {
        // Regression test: the workspace normally lives *under* the project root. A naive rebase would
        // treat the workspace path as a project path and nest the workspace one level deeper on every
        // call. The guard in rebase() is what makes repeated rebasing idempotent.
        Path projectRoot = root.resolve("project");
        Path workspaceRoot = projectRoot.resolve(".codewitness/runs/run-1/workspace");
        VerificationContext context = context(projectRoot, workspaceRoot);

        Path inside = workspaceRoot.resolve("src/main/java/com/example/App.java");
        Path rebased = context.rebase(inside);

        assertEquals(inside.normalize(), rebased);
        assertFalse(rebased.toString().contains("workspace/.codewitness"), rebased.toString());
        assertEquals(rebased, context.rebase(rebased));
    }

    @Test
    @DisplayName("the workspace root itself is returned unchanged")
    void workspaceRootItselfIsUnchanged(@TempDir Path root) {
        Path projectRoot = root.resolve("project");
        Path workspaceRoot = projectRoot.resolve(".codewitness/runs/run-1/workspace");
        VerificationContext context = context(projectRoot, workspaceRoot);

        assertEquals(workspaceRoot.normalize(), context.rebase(workspaceRoot));
    }

    @Test
    @DisplayName("a path under the original project root is mapped into the workspace")
    void projectPathIsMappedIntoWorkspace(@TempDir Path root) {
        Path projectRoot = root.resolve("project");
        Path workspaceRoot = root.resolve("workspace");
        VerificationContext context = context(projectRoot, workspaceRoot);

        Path original = projectRoot.resolve("src/main/java/com/example/App.java");
        assertEquals(workspaceRoot.resolve("src/main/java/com/example/App.java").normalize(),
                context.rebase(original));
    }

    @Test
    @DisplayName("a path outside the project is returned normalised and untouched")
    void outsidePathIsUnchanged(@TempDir Path root) {
        Path projectRoot = root.resolve("project");
        Path workspaceRoot = root.resolve("workspace");
        VerificationContext context = context(projectRoot, workspaceRoot);

        Path outside = root.resolve("elsewhere/notes.txt");
        assertEquals(outside.normalize(), context.rebase(outside));
    }

    @Test
    @DisplayName("rebase(null) is null rather than an exception")
    void nullPathRebasesToNull(@TempDir Path root) {
        VerificationContext context = context(root.resolve("project"), root.resolve("workspace"));

        assertNull(context.rebase(null));
    }

    @Test
    @DisplayName("an in-place workspace leaves project paths alone")
    void inPlaceWorkspaceLeavesProjectPathsAlone(@TempDir Path root) {
        Path projectRoot = root.resolve("project");
        VerificationContext context = context(projectRoot, projectRoot, WorkspaceManager.Mode.IN_PLACE,
                ProgressListener.silent());

        Path original = projectRoot.resolve("src/main/java/com/example/App.java");
        assertEquals(original.normalize(), context.rebase(original));
        assertFalse(context.workspace().isIsolated());
    }

    @Test
    @DisplayName("projectInfo paths are rebased into a copied workspace on construction")
    void projectInfoIsRebasedOnConstruction(@TempDir Path root) {
        Path projectRoot = root.resolve("project");
        Path workspaceRoot = root.resolve("workspace");
        VerificationContext context = context(projectRoot, workspaceRoot);

        ProjectInfo info = context.projectInfo();
        assertEquals(workspaceRoot.resolve("src/main/java").normalize(), info.sourceDirs().get(0));
        assertEquals(workspaceRoot.resolve("src/test/java").normalize(), info.testSourceDirs().get(0));
        assertEquals(workspaceRoot.normalize(), info.buildRoot());
        assertEquals(workspaceRoot.resolve("mvnw").normalize(), info.buildWrapper().orElseThrow());
        assertEquals(workspaceRoot.resolve("pom.xml").normalize(), info.detectedDescriptors().get(0));
    }

    @Test
    @DisplayName("projectInfo keeps its language, build system and modules")
    void projectInfoKeepsMetadata(@TempDir Path root) {
        Path projectRoot = root.resolve("project");
        Path workspaceRoot = root.resolve("workspace");
        VerificationContext context = context(projectRoot, workspaceRoot);

        assertEquals(1, context.projectInfo().modules().size());
        assertEquals("root", context.projectInfo().modules().get(0).name());
        assertEquals(workspaceRoot.resolve("src/main/java").normalize(),
                context.projectInfo().modules().get(0).sourceDirs().get(0));
    }

    @Test
    @DisplayName("projectRoot() is the workspace root, so builds run against the copy")
    void projectRootIsTheWorkspaceRoot(@TempDir Path root) {
        Path workspaceRoot = root.resolve("workspace");
        VerificationContext context = context(root.resolve("project"), workspaceRoot);

        assertEquals(workspaceRoot.normalize(), context.projectRoot());
        assertEquals("run-1", context.runId());
        assertEquals(root.resolve("project").normalize(), context.projectRef().root());
    }

    @Test
    @DisplayName("emit forwards progress events to the listener")
    void emitForwardsProgressEvents(@TempDir Path root) {
        List<String> events = new ArrayList<>();
        VerificationContext context = context(root.resolve("project"), root.resolve("workspace"),
                WorkspaceManager.Mode.COPY, (phase, message) -> events.add(phase + ":" + message));

        context.emit("build", "started");

        assertEquals(List.of("build:started"), events);
    }

    @Test
    @DisplayName("the default context has a disabled llm, a silent listener and an empty requirement set")
    void defaultsAreSafe(@TempDir Path root) {
        VerificationContext context = context(root.resolve("project"), root.resolve("workspace"));

        assertFalse(context.llm().available());
        assertEquals("none", context.llm().id());
        assertTrue(context.requirements().isEmpty());
        assertTrue(context.state().findings().isEmpty());
        assertEquals(0, context.evidence().size());
    }
}
