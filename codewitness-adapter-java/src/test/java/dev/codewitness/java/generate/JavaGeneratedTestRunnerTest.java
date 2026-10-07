package dev.codewitness.java.generate;

import dev.codewitness.core.config.VerificationConfig;
import dev.codewitness.core.exec.ExecutionSandbox;
import dev.codewitness.core.exec.IsolationLevel;
import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessRunner;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.GeneratedTestExecution;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.java.BuildToolLauncher;
import dev.codewitness.java.TestContexts;
import dev.codewitness.java.build.MavenBuildSystem;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated-test executor's contract for the outcomes that do not need a real build.
 *
 * <p>The process runner is substituted, so these tests drive the parse-and-scope half of
 * {@link JavaGeneratedTestRunner#execute} directly: what it does with an empty report, with a report
 * that mentions unrelated tests, and with a failure raised while the generated sources are written.
 */
class JavaGeneratedTestRunnerTest {

    private static final String GENERATED_CLASS = "dev.codewitness.generated.Cw_SC_001_OB_001_Test";

    @Test
    @DisplayName("a report with no case at all is not executed, never a pass")
    void emptyParsedResultIsNotExecuted(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        GeneratedTestExecution execution = execute(root, runDirectory, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<testsuite name=\"pytest\" tests=\"0\" time=\"0.0\"/>\n");

        assertFalse(execution.result().executed(), execution.result().describe());
        assertEquals(0, execution.result().total());
        assertTrue(execution.note().contains("no test case for the generated tests"), execution.note());
    }

    @Test
    @DisplayName("an unrelated reported test is never attributed to the generated tests")
    void unrelatedCasesAreNotAttributedToTheGeneratedTests(@TempDir Path root, @TempDir Path runDirectory)
            throws IOException {
        String report = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.OtherTest" time="0.01">
                  <testcase classname="com.example.OtherTest" name="unrelated" time="0.01"/>
                </testsuite>
                """;

        GeneratedTestExecution execution = execute(root, runDirectory, report);

        assertFalse(execution.result().executed(), execution.result().describe());
        assertEquals(0, execution.result().total(),
                "a passing test from another package must not be counted as evidence:\n" + execution.note());
        assertTrue(execution.note().contains("no test case for the generated tests"), execution.note());
    }

    @Test
    @DisplayName("a case reported for the generated package is still an executed pass")
    void generatedPackageCasesAreReportedAsExecuted(@TempDir Path root, @TempDir Path runDirectory)
            throws IOException {
        String report = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="dev.codewitness.generated.Cw_SC_001_OB_001_Test" time="0.01">
                  <testcase classname="dev.codewitness.generated.Cw_SC_001_OB_001_Test" name="case0000" time="0.01"/>
                </testsuite>
                """;

        GeneratedTestExecution execution = execute(root, runDirectory, report);

        assertTrue(execution.result().executed(), execution.note());
        assertEquals(1, execution.result().total());
        assertEquals(1, execution.result().passed());
        assertEquals(1, execution.result().cases().size());
    }

    @Test
    @DisplayName("a failure raised while writing the generated sources is not discarded by the cleanup block")
    void cleanupNeverDiscardsAnInFlightFailure(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        Files.createDirectories(root.resolve("src/test/java"));
        Files.writeString(root.resolve("mvnw"), "#!/bin/sh\n", StandardCharsets.UTF_8);
        // A regular file where the generated package directory has to go makes the write fail.
        Files.createDirectories(root.resolve("src/test/java/dev"));
        Files.writeString(root.resolve("src/test/java/dev/codewitness"), "not a directory");

        ExecutionSandbox sandbox = sandbox(root, "");
        VerificationConfig config = keepGeneratedTests();
        VerificationContext context =
                TestContexts.context(TestContexts.projectInfo(root), runDirectory, config, sandbox);
        JavaGeneratedTestRunner runner =
                new JavaGeneratedTestRunner(new BuildToolLauncher(sandbox.runner()), new MavenBuildSystem());

        assertThrows(UncheckedIOException.class, () -> runner.execute(context, List.of(generatedTest())),
                "the cleanup block must let the in-flight failure out instead of returning from finally");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Runs the executor against the report the substituted process runner is about to write, so the
     * parse-and-scope half of {@code execute} is exercised without a build tool.
     */
    private static GeneratedTestExecution execute(Path root, Path runDirectory, String report)
            throws IOException {
        Files.createDirectories(root.resolve("src/test/java"));
        // The launcher must resolve an executable, but the substituted runner never starts it.
        Files.writeString(root.resolve("mvnw"), "#!/bin/sh\n", StandardCharsets.UTF_8);

        ExecutionSandbox sandbox = sandbox(root, report);
        VerificationConfig config = TestContexts.config(List.of());
        VerificationContext context =
                TestContexts.context(TestContexts.projectInfo(root), runDirectory, config, sandbox);
        return new JavaGeneratedTestRunner(new BuildToolLauncher(sandbox.runner()), new MavenBuildSystem())
                .execute(context, List.of(generatedTest()));
    }

    /** @return a sandbox whose process runner writes {@code reportXml} where surefire would. */
    private static ExecutionSandbox sandbox(Path projectRoot, String reportXml) {
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
                return "test double: writes the report the runner expects without running a build";
            }

            @Override
            public boolean permits(Path path) {
                return true;
            }

            @Override
            public ProcessRunner runner() {
                return spec -> {
                    Path report = projectRoot.resolve("target/surefire-reports/TEST-cw.xml");
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
        return new GeneratedTest(GENERATED_CLASS,
                "package dev.codewitness.generated;\n\npublic class Cw_SC_001_OB_001_Test {}\n",
                "randomSearch", List.of("R-001"), List.of("OB-001"), "scenario", 42L, null, true);
    }

    /** @return the default configuration with {@code report.keep_generated_tests} switched on. */
    private static VerificationConfig keepGeneratedTests() {
        VerificationConfig base = VerificationConfig.defaults();
        return new VerificationConfig(base.project(), base.language(), base.limits(), base.tests(),
                base.mutation(), base.propertyTesting(), base.staticAnalysis(), base.security(), base.llm(),
                new VerificationConfig.Report(true, true, null, true), base.sandbox(), List.of());
    }
}
