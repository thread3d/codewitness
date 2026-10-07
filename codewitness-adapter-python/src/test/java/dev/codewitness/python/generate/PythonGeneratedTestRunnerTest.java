package dev.codewitness.python.generate;

import dev.codewitness.core.exec.ExecutionSandbox;
import dev.codewitness.core.exec.IsolationLevel;
import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessRunner;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.GeneratedTestExecution;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.python.PythonTestContexts;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated-test executor's contract for the outcomes that do not need a real interpreter.
 *
 * <p>The process runner is substituted, so these tests drive the parse half of
 * {@link PythonGeneratedTestRunner#execute}: a report that carries no case must be reported as not
 * executed, because a scenario that reports nothing must never be mistaken for one that passed.
 */
class PythonGeneratedTestRunnerTest {

    @Test
    @DisplayName("an empty batch is reported as not executed, never as a pass")
    void emptyBatchIsNotExecuted(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        ExecutionSandbox sandbox = sandboxWritingReport(runDirectory, "");
        VerificationContext context = PythonTestContexts.context(
                PythonTestContexts.projectInfo(root), runDirectory, List.of(), sandbox);

        GeneratedTestExecution execution = new PythonGeneratedTestRunner().execute(context, List.of());

        assertFalse(execution.result().executed(), execution.result().describe());
        assertEquals("no tests to run", execution.note());
    }

    @Test
    @DisplayName("a report with no case is not executed, never a pass")
    void emptyReportIsNotExecuted(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        String report = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<testsuite name=\"pytest\" tests=\"0\" time=\"0.0\"/>\n";

        GeneratedTestExecution execution = execute(root, runDirectory, report);

        assertFalse(execution.result().executed(), execution.result().describe());
        assertEquals(0, execution.result().total());
        assertTrue(execution.note().contains("no test case for the generated tests"), execution.note());
    }

    @Test
    @DisplayName("a reported generated case is still an executed pass")
    void reportedCaseIsExecuted(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        String report = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="test_cw_sc_001_ob_001" tests="1" time="0.01">
                  <testcase classname="test_cw_sc_001_ob_001" name="test_case0000" time="0.01"/>
                </testsuite>
                """;

        GeneratedTestExecution execution = execute(root, runDirectory, report);

        assertTrue(execution.result().executed(), execution.note());
        assertEquals(1, execution.result().total());
        assertEquals(1, execution.result().passed());
        assertEquals(1, execution.result().cases().size());
    }

    // ------------------------------------------------------------------ helpers

    /** Runs the executor against the report the substituted interpreter is about to write. */
    private static GeneratedTestExecution execute(Path root, Path runDirectory, String report)
            throws IOException {
        Files.writeString(root.resolve("app.py"), "VALUE = 1\n", StandardCharsets.UTF_8);
        ExecutionSandbox sandbox = sandboxWritingReport(runDirectory, report);
        VerificationContext context = PythonTestContexts.context(
                PythonTestContexts.projectInfo(root), runDirectory, List.of(), sandbox);
        return new PythonGeneratedTestRunner().execute(context, List.of(generatedTest()));
    }

    /**
     * @return a sandbox whose process runner writes {@code reportXml} into the artefact store, where
     *     the executor looks for the JUnit report of the generated modules
     */
    private static ExecutionSandbox sandboxWritingReport(Path runDirectory, String reportXml) {
        return new ExecutionSandbox() {
            @Override
            public String id() {
                return "test-sandbox";
            }

            @Override
            public IsolationLevel isolationLevel() {
                return IsolationLevel.NONE;
            }

            @Override
            public String description() {
                return "test double: writes the report the runner expects without starting Python";
            }

            @Override
            public boolean permits(Path path) {
                return true;
            }

            @Override
            public ProcessRunner runner() {
                return spec -> {
                    Path report = runDirectory.resolve("artifacts").resolve("generated-tests").resolve("junit.xml");
                    try {
                        Files.createDirectories(report.getParent());
                        Files.writeString(report, reportXml, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return new ProcessResult(spec.command(), spec.workingDirectory(), true, 0, "", "",
                            Duration.ZERO, false, null);
                };
            }
        };
    }

    private static GeneratedTest generatedTest() {
        return new GeneratedTest("test_cw_sc_001_ob_001",
                "import unittest\n\nclass Cw_sc_001_ob_001(unittest.TestCase):\n"
                        + "    def test_case0000(self):\n        pass\n",
                "test_random_search", List.of("R-001"), List.of("OB-001"), "scenario", 42L, null, true);
    }
}
