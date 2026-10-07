package dev.codewitness.go.coverage;

import dev.codewitness.core.config.VerificationConfig;
import dev.codewitness.core.evidence.EvidenceLedger;
import dev.codewitness.core.exec.ExecutionSandbox;
import dev.codewitness.core.exec.IsolationLevel;
import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessRunner;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.io.ArtifactStore;
import dev.codewitness.core.io.WorkspaceManager;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ModuleInfo;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.result.Budget;
import dev.codewitness.core.result.BudgetUsage;
import dev.codewitness.core.spi.VerificationContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code go test -coverprofile} run killed at its timeout is not a finished observation.
 *
 * <p>Go writes the profile as it goes, so a kill leaves a profile that looks like a measured run. This
 * test supplies such a profile, reports the timeout, and requires that it is not read as a
 * measurement. (The Go test provider is covered separately.)
 */
class GoCoverageTimeoutTest {

    @TempDir
    Path tmp;

    private static final String PROFILE = """
            mode: set
            example.com/sample/ledger/ledger.go:10.2,12.5 3 1
            example.com/sample/ledger/ledger.go:14.2,16.5 2 0
            """;

    @Test
    @DisplayName("a killed coverage run is not measured, even though it left a partial profile")
    void killedCoverageRunIsNotMeasured() {
        Path root = projectDirectory();
        Path runDirectory = tmp.resolve("run");
        Path report = runDirectory.resolve("artifacts/go/coverage.out");

        assertTrue(GoCoverageProvider.parse(write(report, PROFILE), "partial").measured(),
                "the partial profile parses on its own, so the guard is what refuses it");

        VerificationContext context = context(root, runDirectory, sandbox(() -> write(report, PROFILE)));
        CoverageReport coverage = new GoCoverageProvider().measure(context);

        assertFalse(coverage.measured(), "a killed run must not be measured: " + coverage.describe());
        assertTrue(Files.isRegularFile(report), "the partial report existed and had to be ignored");
        assertTrue(coverage.note().contains("timeout"), coverage.note());
        assertTrue(coverage.note().contains("was killed"), coverage.note());
    }

    private Path projectDirectory() {
        Path root = tmp.resolve("project");
        write(root.resolve("go.mod"), "module example.com/sample\n\ngo 1.22\n");
        return root;
    }

    private VerificationContext context(Path root, Path runDirectory, ExecutionSandbox sandbox) {
        ProjectInfo project = ProjectInfo.builder(ProjectRef.of(root))
                .language(LanguageId.GO)
                .addModule(new ModuleInfo("sample", root, "module",
                        List.of(root), List.of(root), null))
                .build();
        ProjectRef ref = project.project();
        return VerificationContext.builder()
                .runId("CW-2026-01-01-TEST0")
                .projectRef(ref)
                .projectInfo(project)
                .config(VerificationConfig.defaults())
                .workspace(new WorkspaceManager.Workspace(ref.root(), WorkspaceManager.Mode.IN_PLACE, "test"))
                .artifacts(new ArtifactStore(runDirectory))
                .budget(new BudgetUsage.Tracker(Budget.defaults()))
                .sandbox(sandbox)
                .evidenceLedger(new EvidenceLedger("CW-2026-01-01-TEST0"))
                .build();
    }

    /** A sandbox whose runner performs {@code onRun} and reports a process killed by its timeout. */
    private static ExecutionSandbox sandbox(Runnable onRun) {
        return new ExecutionSandbox() {
            @Override
            public String id() {
                return "timeout-double";
            }

            @Override
            public IsolationLevel isolationLevel() {
                return IsolationLevel.NONE;
            }

            @Override
            public String description() {
                return "test double: runs nothing, reports a timeout";
            }

            @Override
            public boolean permits(Path path) {
                return true;
            }

            @Override
            public ProcessRunner runner() {
                return spec -> {
                    onRun.run();
                    return timedOut(spec);
                };
            }
        };
    }

    private static ProcessResult timedOut(ProcessSpec spec) {
        return new ProcessResult(spec.command(), spec.workingDirectory(), true, -1,
                "ok  \texample.com/sample/ledger\t0.01s", "partial output before the kill",
                Duration.ofSeconds(5), true, null);
    }

    private static Path write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
