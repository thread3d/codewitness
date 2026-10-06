package dev.codewitness.core.spi;

import dev.codewitness.core.config.VerificationConfig;
import dev.codewitness.core.exec.ExecutionSandbox;
import dev.codewitness.core.exec.ProcessRunner;
import dev.codewitness.core.io.ArtifactStore;
import dev.codewitness.core.io.WorkspaceManager;
import dev.codewitness.core.llm.LlmProvider;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.requirements.RequirementSet;
import dev.codewitness.core.result.BudgetUsage;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Everything a verification strategy or tool implementation is allowed to see.
 *
 * <p>The context is the only route to the outside world: it provides the sandbox, the artefact
 * store, the budget and the isolated project root. Nothing executes project code outside
 * {@link #runner()}.
 */
public final class VerificationContext {

    private final String runId;
    private final ProjectRef projectRef;
    private final ProjectInfo projectInfo;
    private final VerificationConfig config;
    private final WorkspaceManager.Workspace workspace;
    private final ArtifactStore artifacts;
    private final BudgetUsage.Tracker budget;
    private final RequirementSet requirements;
    private final LlmProvider llm;
    private final ProgressListener progress;
    private final ExecutionSandbox sandbox;
    private final Instant startedAt;
    private final RunState state;
    private final dev.codewitness.core.evidence.EvidenceLedger evidenceLedger;

    private VerificationContext(Builder builder) {
        this.runId = builder.runId;
        this.projectRef = builder.projectRef;
        this.config = builder.config;
        this.workspace = builder.workspace;
        this.artifacts = builder.artifacts;
        this.budget = builder.budget;
        this.requirements = builder.requirements;
        this.llm = builder.llm;
        this.progress = builder.progress;
        this.sandbox = builder.sandbox;
        this.startedAt = builder.startedAt;
        this.state = builder.state;
        this.evidenceLedger = builder.evidenceLedger;
        this.projectInfo = rebase(builder.projectInfo);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String runId() {
        return runId;
    }

    public ProjectRef projectRef() {
        return projectRef;
    }

    /** Project information with every path rebased into the isolated workspace. */
    public ProjectInfo projectInfo() {
        return projectInfo;
    }

    public VerificationConfig config() {
        return config;
    }

    public WorkspaceManager.Workspace workspace() {
        return workspace;
    }

    /** Root of the project as it exists inside the workspace: all build commands run from here. */
    public Path projectRoot() {
        return workspace.root();
    }

    public ArtifactStore artifacts() {
        return artifacts;
    }

    public BudgetUsage.Tracker budget() {
        return budget;
    }

    public RequirementSet requirements() {
        return requirements;
    }

    public LlmProvider llm() {
        return llm;
    }

    public ExecutionSandbox sandbox() {
        return sandbox;
    }

    public ProcessRunner runner() {
        return sandbox.runner();
    }

    public ProgressListener progress() {
        return progress;
    }

    /** Results of the deterministic phases completed so far. */
    public RunState state() {
        return state;
    }

    /** The evidence ledger of this run; strategies record their observations here. */
    public dev.codewitness.core.evidence.EvidenceLedger evidence() {
        return evidenceLedger;
    }

    public Instant startedAt() {
        return startedAt;
    }

    /** Emits a progress event. */
    public void emit(String phase, String message) {
        progress.onEvent(phase, message);
    }

    /**
     * Maps a path that was discovered in the original project onto the isolated workspace.
     *
     * @param originalPath a path under {@link ProjectRef#root()}
     * @return the corresponding path inside the workspace, or the input when it lies outside the project
     */
    public Path rebase(Path originalPath) {
        if (originalPath == null) {
            return null;
        }
        Path normalized = originalPath.toAbsolutePath().normalize();
        Path root = projectRoot();
        // A path that is already inside the workspace must be left alone; the workspace normally lives
        // under the project root, so without this guard every rebase would nest it one level deeper.
        if (normalized.startsWith(root)) {
            return normalized;
        }
        Path originalRoot = projectRef.root();
        if (normalized.startsWith(originalRoot)) {
            return root.resolve(originalRoot.relativize(normalized)).normalize();
        }
        return normalized;
    }

    private ProjectInfo rebase(ProjectInfo info) {
        if (info == null || workspace.mode() == WorkspaceManager.Mode.IN_PLACE) {
            return info;
        }
        ProjectInfo.Builder builder = ProjectInfo.builder(info.project())
                .language(info.language())
                .buildSystem(info.buildSystem())
                .buildRoot(rebase(info.buildRoot()))
                .javaVersion(info.javaVersion().orElse(null))
                .attributes(info.attributes());
        info.buildWrapper().ifPresent(wrapper -> builder.buildWrapper(rebase(wrapper)));
        info.detectedDescriptors().forEach(d -> builder.addDetectedDescriptor(rebase(d)));
        info.testFrameworks().forEach(builder::addTestFramework);
        for (var module : info.modules()) {
            builder.addModule(new dev.codewitness.core.model.ModuleInfo(
                    module.name(),
                    rebase(module.path()),
                    module.packaging(),
                    module.sourceDirs().stream().map(this::rebase).toList(),
                    module.testSourceDirs().stream().map(this::rebase).toList(),
                    module.javaVersion()));
        }
        return builder.build();
    }

    public static final class Builder {
        private String runId;
        private ProjectRef projectRef;
        private ProjectInfo projectInfo;
        private VerificationConfig config = VerificationConfig.defaults();
        private WorkspaceManager.Workspace workspace;
        private ArtifactStore artifacts;
        private BudgetUsage.Tracker budget;
        private RequirementSet requirements = RequirementSet.empty();
        private LlmProvider llm = LlmProvider.disabled("not configured");
        private ProgressListener progress = ProgressListener.silent();
        private ExecutionSandbox sandbox;
        private Instant startedAt = Instant.now();
        private RunState state = new RunState();
        private dev.codewitness.core.evidence.EvidenceLedger evidenceLedger;

        public Builder runId(String value) {
            this.runId = value;
            return this;
        }

        public Builder projectRef(ProjectRef value) {
            this.projectRef = value;
            return this;
        }

        public Builder projectInfo(ProjectInfo value) {
            this.projectInfo = value;
            return this;
        }

        public Builder config(VerificationConfig value) {
            this.config = value;
            return this;
        }

        public Builder workspace(WorkspaceManager.Workspace value) {
            this.workspace = value;
            return this;
        }

        public Builder artifacts(ArtifactStore value) {
            this.artifacts = value;
            return this;
        }

        public Builder budget(BudgetUsage.Tracker value) {
            this.budget = value;
            return this;
        }

        public Builder requirements(RequirementSet value) {
            this.requirements = value;
            return this;
        }

        public Builder llm(LlmProvider value) {
            this.llm = value;
            return this;
        }

        public Builder progress(ProgressListener value) {
            this.progress = value;
            return this;
        }

        public Builder sandbox(ExecutionSandbox value) {
            this.sandbox = value;
            return this;
        }

        public Builder startedAt(Instant value) {
            this.startedAt = value;
            return this;
        }

        /** Shares the mutable phase-result holder between contexts of the same run. */
        public Builder state(RunState value) {
            this.state = value;
            return this;
        }

        /** Shares the evidence ledger between contexts of the same run. */
        public Builder evidenceLedger(dev.codewitness.core.evidence.EvidenceLedger value) {
            this.evidenceLedger = value;
            return this;
        }

        public VerificationContext build() {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(projectRef, "projectRef");
            Objects.requireNonNull(workspace, "workspace");
            Objects.requireNonNull(artifacts, "artifacts");
            Objects.requireNonNull(budget, "budget");
            Objects.requireNonNull(sandbox, "sandbox");
            Objects.requireNonNull(projectInfo, "projectInfo");
            Objects.requireNonNull(evidenceLedger, "evidenceLedger");
            return new VerificationContext(this);
        }
    }
}
