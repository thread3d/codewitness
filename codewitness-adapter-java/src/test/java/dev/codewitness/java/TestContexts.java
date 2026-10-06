package dev.codewitness.java;

import dev.codewitness.core.config.Scenario;
import dev.codewitness.core.config.VerificationConfig;
import dev.codewitness.core.evidence.EvidenceLedger;
import dev.codewitness.core.exec.ExecutionSandbox;
import dev.codewitness.core.exec.LocalProcessSandbox;
import dev.codewitness.core.io.ArtifactStore;
import dev.codewitness.core.io.WorkspaceManager;
import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ModuleInfo;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.result.BudgetUsage;
import dev.codewitness.core.spi.VerificationContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Builds a real {@link VerificationContext} around a temporary project.
 *
 * <p>The analyzers and the generator under test only read source files and configuration, so the
 * context is complete as soon as it has a project, a workspace, an artefact store, a ledger, a
 * budget and a sandbox. Nothing executes project code in these tests; the sandbox is present
 * because the context requires one.
 */
public final class TestContexts {

    private TestContexts() {
    }

    /** @return a single-element list when the directory exists, an empty list otherwise. */
    public static List<Path> existing(Path directory) {
        return Files.isDirectory(directory) ? List.of(directory) : List.of();
    }

    /** Writes a UTF-8 file below {@code base}, creating parent directories as needed. */
    public static Path write(Path base, String relative, String content) throws IOException {
        Path file = base.resolve(relative);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    /** @return a Java {@link ProjectInfo} for a directory, using the conventional source layout. */
    public static ProjectInfo projectInfo(Path root) {
        String name = root.getFileName() == null ? "project" : root.getFileName().toString();
        return ProjectInfo.builder(ProjectRef.of(root))
                .language(LanguageId.JAVA)
                .buildSystem(BuildSystemId.NONE)
                .addModule(new ModuleInfo(name, root, "jar",
                        existing(root.resolve("src/main/java")),
                        existing(root.resolve("src/test/java")),
                        "17"))
                .build();
    }

    /** A context with the default configuration and no scenarios. */
    public static VerificationContext context(Path root, Path runDirectory) {
        return context(root, runDirectory, List.of());
    }

    /** A context with the given scenarios declared in code. */
    public static VerificationContext context(Path root, Path runDirectory, List<Scenario> scenarios) {
        ProjectRef projectRef = ProjectRef.of(root);
        WorkspaceManager.Workspace workspace =
                WorkspaceManager.prepare(projectRef, runDirectory, WorkspaceManager.Mode.IN_PLACE);
        ExecutionSandbox sandbox = new LocalProcessSandbox(List.of(root, runDirectory));
        VerificationConfig defaults = VerificationConfig.defaults();
        VerificationConfig config = new VerificationConfig(
                defaults.project(), defaults.language(), defaults.limits(), defaults.tests(),
                defaults.mutation(), defaults.propertyTesting(), defaults.staticAnalysis(),
                defaults.security(), defaults.llm(), defaults.report(), defaults.sandbox(), scenarios);
        return VerificationContext.builder()
                .runId("CW-2026-01-01-TEST0")
                .projectRef(projectRef)
                .projectInfo(projectInfo(root))
                .config(config)
                .workspace(workspace)
                .artifacts(new ArtifactStore(runDirectory))
                .budget(new BudgetUsage.Tracker(config.toBudget()))
                .sandbox(sandbox)
                .evidenceLedger(new EvidenceLedger("CW-2026-01-01-TEST0"))
                .build();
    }
}
