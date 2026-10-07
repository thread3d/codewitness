package dev.codewitness.typescript;

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
import dev.codewitness.typescript.coverage.TypeScriptCoverageProvider;

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
 * A node test run killed at its timeout is not a finished observation.
 *
 * <p>Node's JUnit reporter and its V8 LCOV export are both written as the run proceeds, so a kill
 * leaves a report that looks like a finished run. These tests supply such a report, report the
 * timeout, and require that it is not read as a suite or as a measurement.
 */
class TypeScriptTimeoutTest {

    @TempDir
    Path tmp;

    private static final String JUNIT_XML = """
            <?xml version="1.0" encoding="utf-8"?>
            <testsuites>
              <testcase name="adds" time="0.001" classname="test" file="tests/ledger.test.ts"/>
            </testsuites>
            """;

    private static final String LCOV = """
            SF:src/ledger.ts
            DA:1,1
            DA:2,0
            DA:3,1
            end_of_record
            """;

    @Test
    @DisplayName("a killed node run is not executed, even though a JUnit report was written")
    void killedTestRunIsNotParsedAsASuite() {
        Path root = projectDirectory();
        Path runDirectory = tmp.resolve("run");
        Path report = runDirectory.resolve("artifacts/typescript/tests/node-junit.xml");

        assertTrue(TypeScriptTestReportParser.parse(JUNIT_XML, "partial", List.of()).executed(),
                "the partial report parses on its own, so the guard is what refuses it");

        VerificationContext context = context(root, runDirectory, sandbox(() -> write(report, JUNIT_XML)));
        TestResult result = new TypeScriptTestProvider().runTests(context);

        assertFalse(result.executed(), "a killed run must not read as a finished suite: " + result.describe());
        assertTrue(Files.isRegularFile(report), "the partial report existed and had to be ignored");
        assertTrue(result.describe().contains("timeout"), result.describe());
        assertTrue(result.note().contains("was killed"), result.note());
    }

    @Test
    @DisplayName("a killed coverage run is not measured, even though a partial LCOV report was written")
    void killedCoverageRunIsNotMeasured() {
        Path root = projectDirectory();
        Path runDirectory = tmp.resolve("run");
        Path report = runDirectory.resolve("artifacts/typescript/coverage/lcov.info");

        assertTrue(TypeScriptCoverageProvider.parse(LCOV, "partial", List.of()).measured(),
                "the partial report parses on its own, so the guard is what refuses it");

        VerificationContext context = context(root, runDirectory, sandbox(() -> write(report, LCOV)));
        CoverageReport coverage = new TypeScriptCoverageProvider().measure(context);

        assertFalse(coverage.measured(), "a killed run must not be measured: " + coverage.describe());
        assertTrue(Files.isRegularFile(report), "the partial report existed and had to be ignored");
        assertTrue(coverage.note().contains("timeout"), coverage.note());
        assertTrue(coverage.note().contains("was killed"), coverage.note());
    }

    private Path projectDirectory() {
        Path root = tmp.resolve("project");
        write(root.resolve("tests/ledger.test.ts"), "import test from 'node:test';\ntest('adds', () => {});\n");
        return root;
    }

    private VerificationContext context(Path root, Path runDirectory, ExecutionSandbox sandbox) {
        ProjectInfo project = ProjectInfo.builder(ProjectRef.of(root))
                .language(LanguageId.TYPESCRIPT)
                .addModule(new ModuleInfo("sample", root, "module",
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
                "# tests 1\n# pass 1\n# still running", "partial output before the kill",
                Duration.ofSeconds(5), true, null);
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
