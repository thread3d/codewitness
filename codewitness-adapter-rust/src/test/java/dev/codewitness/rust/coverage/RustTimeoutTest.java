package dev.codewitness.rust.coverage;

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
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.result.Budget;
import dev.codewitness.core.result.BudgetUsage;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.rust.Cargo;
import dev.codewitness.rust.RustTestProvider;
import dev.codewitness.rust.RustTestReportParser;

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
 * A cargo command killed at its timeout is not a finished observation.
 *
 * <p>cargo test prints complete-looking case lines as it goes, and cargo-llvm-cov writes its JSON as
 * the run proceeds, so a kill leaves exactly the output the parsers used to read as a whole run.
 * These tests supply that output, report the timeout, and require that it is not used.
 */
class RustTimeoutTest {

    @TempDir
    Path tmp;

    private static final String CARGO_STDERR = """
            Compiling sample v0.1.0
                 Running unittests src/lib.rs (target/debug/deps/sample-abc)
            """;

    private static final String CARGO_STDOUT = """
            running 1 test
            test ledger::records_fee ... ok

            test result: ok. 1 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.01s
            """;

    private static final String LLVM_COV_JSON = """
            {
              "data": [
                {
                  "files": [],
                  "totals": {
                    "lines": { "count": 100, "covered": 40, "percent": 40.0 },
                    "branches": { "count": 10, "covered": 1, "percent": 10.0 }
                  }
                }
              ],
              "type": "llvm.coverage.json.export",
              "version": "2.0.1"
            }
            """;

    @Test
    @DisplayName("a killed cargo test is not executed, even though its partial output looks like a suite")
    void killedCargoTestIsNotParsedAsASuite() {
        Path root = projectDirectory();
        Path runDirectory = tmp.resolve("run");

        // The very output the killed process left behind parses into a passing suite on its own; only
        // the timeout guard keeps it out of the test result.
        TestResult parsedDirectly = RustTestReportParser.parse(CARGO_STDOUT, CARGO_STDERR, "partial", List.of());
        assertTrue(parsedDirectly.executed(), "the partial output is parseable, so the guard is what refuses it");

        VerificationContext context = context(root, runDirectory, sandbox(() -> { }));
        TestResult result = new RustTestProvider().runTests(context);

        assertFalse(result.executed(), "a killed run must not read as a finished suite: " + result.describe());
        assertTrue(result.describe().contains("timeout"), result.describe());
        assertTrue(result.note().contains("was killed"), result.note());
    }

    @Test
    @DisplayName("a killed cargo-llvm-cov run is not measured, even though it left a partial JSON export")
    void killedCargoLlvmCovRunIsNotMeasured() {
        Path root = projectDirectory();
        Path runDirectory = tmp.resolve("run");
        Path report = runDirectory.resolve("artifacts/rust/coverage.json");
        VerificationContext context = context(root, runDirectory, sandbox(() -> write(report, LLVM_COV_JSON)));

        assertTrue(RustCoverageProvider.parse(write(report, LLVM_COV_JSON), "test").measured(),
                "the partial export parses on its own, so the guard is what refuses it");

        CoverageReport coverage = new RustCoverageProvider().measure(context);

        assertFalse(coverage.measured(), "a killed run must not be measured: " + coverage.describe());
        assertTrue(Files.isRegularFile(report), "the partial report existed and had to be ignored");
        assertTrue(coverage.note().contains("timeout"), coverage.note());
        assertTrue(coverage.note().contains("was killed"), coverage.note());
    }

    private Path projectDirectory() {
        Path root = tmp.resolve("project");
        try {
            Files.createDirectories(root.resolve("src"));
            Files.createDirectories(root.resolve("tests"));
            Files.writeString(root.resolve("Cargo.toml"), "[package]\nname = \"sample\"\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return root;
    }

    private VerificationContext context(Path root, Path runDirectory, ExecutionSandbox sandbox) {
        ProjectInfo project = ProjectInfo.builder(ProjectRef.of(root))
                .language(LanguageId.RUST)
                .attribute(Cargo.ATTR_HAS_LLVM_COV, "true")
                .addModule(new ModuleInfo("sample", root, "lib",
                        List.of(root.resolve("src")), List.of(root.resolve("tests")), null))
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
                CARGO_STDOUT, CARGO_STDERR, Duration.ofSeconds(5), true, null);
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
