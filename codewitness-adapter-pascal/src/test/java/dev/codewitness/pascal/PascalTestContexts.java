package dev.codewitness.pascal;

import dev.codewitness.core.config.Scenario;
import dev.codewitness.core.config.VerificationConfig;
import dev.codewitness.core.evidence.EvidenceLedger;
import dev.codewitness.core.exec.ExecutionSandbox;
import dev.codewitness.core.exec.LocalProcessSandbox;
import dev.codewitness.core.io.ArtifactStore;
import dev.codewitness.core.io.WorkspaceManager;
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
 * Shared fabrication for the Pascal unit tests: real directories, a real detector and a context whose
 * toolchain is never invoked.
 *
 * <p>The tests exercise detection, parsing, analysis and generation in isolation, so they construct a
 * context the same way the engine does but never run a compiler. That is deliberate: the unit suite must
 * pass on a machine with no Free Pascal installed, and the end-to-end test is the one that needs the real
 * toolchain.
 */
public final class PascalTestContexts {

    private PascalTestContexts() {
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

    /** @return project information detected from a real directory. */
    public static ProjectInfo projectInfo(Path root) {
        return PascalProjectDetector.inspect(ProjectRef.of(root));
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
