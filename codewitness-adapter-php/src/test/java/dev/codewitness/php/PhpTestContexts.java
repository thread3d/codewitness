package dev.codewitness.php;

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
import java.util.Map;

/**
 * Builds a real {@link VerificationContext} around a temporary PHP project.
 *
 * <p>The detector, the command builders and the report parser only read files and configuration, so the
 * context is complete as soon as it has a project, a workspace, an artefact store, a ledger, a budget and
 * a sandbox. Nothing here requires PHP, Composer or PHPUnit to be installed: a test that exercises an
 * unavailable tool points the project at an interpreter that does not exist and asserts the reason,
 * rather than depending on what the machine happens to have.
 */
public final class PhpTestContexts {

    /** An interpreter path that is guaranteed not to exist, for asserting the unavailable path. */
    public static final String MISSING_PHP = "/nonexistent/codewitness-php-binary";

    private PhpTestContexts() {
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
        return PhpProjectDetector.inspect(ProjectRef.of(root));
    }

    /**
     * @return the detector's description of {@code root} with the given attributes merged over it.
     *
     * <p>Needed because the interpreter facts - which interpreter, which runner - are what the adapter
     * normally records during inspection, and a test of a provider has to be able to state them without
     * running a probe.
     */
    public static ProjectInfo withAttributes(Path root, Map<String, String> attributes) {
        ProjectInfo detected = projectInfo(root);
        return ProjectInfo.builder(detected.project())
                .language(detected.language())
                .buildSystem(detected.buildSystem())
                .buildRoot(detected.buildRoot())
                .sourceExtensions(detected.sourceExtensions())
                .attributes(detected.attributes())
                .attributes(attributes)
                .modules(detected.modules())
                .build();
    }

    /** A context with the default configuration and no scenarios. */
    public static VerificationContext context(Path root, Path runDirectory) {
        return context(projectInfo(root), runDirectory, List.of(), new LocalProcessSandbox(List.of(root, runDirectory)));
    }

    /** A context around project information the caller built, with the default configuration. */
    public static VerificationContext context(ProjectInfo info, Path runDirectory) {
        return context(info, runDirectory, List.of(), new LocalProcessSandbox(List.of(info.project().root(), runDirectory)));
    }

    /** A context whose sandbox the caller supplies, for tests that replace the process runner. */
    public static VerificationContext context(
            ProjectInfo info, Path runDirectory, List<Scenario> scenarios, ExecutionSandbox sandbox) {
        ProjectRef projectRef = info.project();
        WorkspaceManager.Workspace workspace =
                WorkspaceManager.prepare(projectRef, runDirectory, WorkspaceManager.Mode.IN_PLACE);
        VerificationConfig defaults = VerificationConfig.defaults();
        VerificationConfig config = new VerificationConfig(
                defaults.project(), defaults.language(), defaults.limits(), defaults.tests(),
                defaults.mutation(), defaults.propertyTesting(), defaults.staticAnalysis(),
                defaults.security(), defaults.llm(), defaults.report(), defaults.sandbox(), scenarios);
        return VerificationContext.builder()
                .runId("CW-2026-01-01-TEST0")
                .projectRef(projectRef)
                .projectInfo(info)
                .config(config)
                .workspace(workspace)
                .artifacts(new ArtifactStore(runDirectory))
                .budget(new BudgetUsage.Tracker(config.toBudget()))
                .sandbox(sandbox)
                .evidenceLedger(new EvidenceLedger("CW-2026-01-01-TEST0"))
                .build();
    }
}
