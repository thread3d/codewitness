package dev.codewitness.python;

import dev.codewitness.core.exec.ExecutionSandbox;
import dev.codewitness.core.exec.IsolationLevel;
import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessRunner;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ModuleInfo;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.python.coverage.PythonCoverageProvider;

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
 * A test or coverage runner killed at its timeout is not a finished observation.
 *
 * <p>The runner can have written a JUnit report for the tests that finished before the kill, or a
 * coverage JSON that only covers part of the suite. Both used to be read as a complete run. These
 * tests make the process write such a report and then report the timeout, and require that neither is
 * used.
 */
class PythonTimeoutTest {

    @TempDir
    Path tmp;

    private static final String JUNIT_XML = """
            <?xml version="1.0" encoding="utf-8"?>
            <testsuite name="tests.test_ledger" tests="1" time="0.01">
              <testcase classname="tests.test_ledger" name="records_fee" time="0.01"/>
            </testsuite>
            """;

    private static final String COVERAGE_JSON = """
            {
              "meta": {"version": "7.6.0"},
              "files": {"src/ledger.py": {"summary": {"percent_covered": 80.0}}},
              "totals": {"covered_lines": 8, "num_statements": 10}
            }
            """;

    @Test
    @DisplayName("a killed test run is not executed, even though a JUnit report was written")
    void killedTestRunIsNotParsedAsASuite() {
        Path root = projectDirectory();
        Path runDirectory = tmp.resolve("run");
        Path report = runDirectory.resolve("artifacts/python/tests/junit.xml");
        VerificationContext context = context(root, runDirectory, sandbox(() -> write(report, JUNIT_XML)));

        TestResult result = new PythonTestProvider().runTests(context);

        assertFalse(result.executed(), "a killed run must not read as a finished suite: " + result.describe());
        assertTrue(Files.isRegularFile(report), "the partial report existed and had to be ignored");
        assertTrue(result.describe().contains("timeout"), result.describe());
        assertTrue(result.note().contains("was killed"), result.note());
    }

    @Test
    @DisplayName("a killed coverage run is not measured, even though a partial JSON report was written")
    void killedCoverageRunIsNotMeasured() {
        Path root = projectDirectory();
        Path runDirectory = tmp.resolve("run");
        Path report = runDirectory.resolve("artifacts/python/coverage.json");
        VerificationContext context = context(root, runDirectory, sandbox(() -> write(report, COVERAGE_JSON)));

        CoverageReport coverage = new PythonCoverageProvider().measure(context);

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
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return root;
    }

    private VerificationContext context(Path root, Path runDirectory, ExecutionSandbox sandbox) {
        ProjectInfo project = ProjectInfo.builder(ProjectRef.of(root))
                .language(LanguageId.PYTHON)
                .attribute(PythonInterpreter.ATTR_HAS_COVERAGE, "true")
                .addModule(new ModuleInfo("sample", root, "wheel",
                        List.of(root.resolve("src")), List.of(root.resolve("tests")), null))
                .build();
        return PythonTestContexts.context(project, runDirectory, List.of(), sandbox);
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
                "1 passed, 4 still running", "partial output before the kill",
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
