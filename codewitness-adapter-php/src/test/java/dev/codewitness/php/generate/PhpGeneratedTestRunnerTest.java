package dev.codewitness.php.generate;

import dev.codewitness.core.exec.ExecutionSandbox;
import dev.codewitness.core.exec.IsolationLevel;
import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessRunner;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.GeneratedTestExecution;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.php.PhpInterpreter;
import dev.codewitness.php.PhpTestContexts;

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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated-test executor's contract for the outcomes that do not need a real PHPUnit.
 *
 * <p>The process runner is substituted, so these tests drive the parse half of
 * {@link PhpGeneratedTestRunner#execute}: the class must be written where the project's PHPUnit
 * configuration can find it, a report that carries no case must be reported as not executed, and the
 * replay command must be a real PHPUnit invocation carrying the seed and the case index.
 */
class PhpGeneratedTestRunnerTest {

    @Test
    @DisplayName("the executor names itself and claims only PHP projects")
    void namesItself(@TempDir Path root) {
        PhpGeneratedTestRunner runner = new PhpGeneratedTestRunner();

        assertEquals("php-generated-tests", runner.id());
        assertTrue(runner.supports(PhpTestContexts.projectInfo(root)));
    }

    @Test
    @DisplayName("an empty batch is reported as not executed, never as a pass")
    void emptyBatchIsNotExecuted(@TempDir Path root, @TempDir Path run) throws IOException {
        VerificationContext context = context(root, run, sandboxWritingReport(run, ""));

        GeneratedTestExecution execution = new PhpGeneratedTestRunner().execute(context, List.of());

        assertFalse(execution.result().executed(), execution.result().describe());
        assertEquals("no tests to run", execution.note());
    }

    @Test
    @DisplayName("without PHPUnit the batch is not executed, never a suite with no failures")
    void withoutPhpunitNothingRuns(@TempDir Path root, @TempDir Path run) throws IOException {
        PhpTestContexts.write(root, "tests/LedgerTest.php", "<?php\n");
        VerificationContext context = PhpTestContexts.context(
                PhpTestContexts.withAttributes(root,
                        Map.of(PhpInterpreter.ATTR_PHP, PhpTestContexts.MISSING_PHP)),
                run);

        GeneratedTestExecution execution = new PhpGeneratedTestRunner().execute(context, List.of(generatedTest()));

        assertFalse(execution.result().executed(), execution.result().describe());
        assertTrue(execution.note().contains("PHPUnit was not found"), execution.note());
    }

    @Test
    @DisplayName("a report with no case is not executed, never a pass")
    void emptyReportIsNotExecuted(@TempDir Path root, @TempDir Path run) throws IOException {
        String report = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<testsuite name=\"phpunit\" tests=\"0\" time=\"0.0\"/>\n";

        GeneratedTestExecution execution = execute(root, run, report);

        assertFalse(execution.result().executed(), execution.result().describe());
        assertEquals(0, execution.result().total());
        assertTrue(execution.note().contains("no case for the generated"), execution.note());
    }

    @Test
    @DisplayName("a reported generated case is an executed pass, and its class stays where PHPUnit looks")
    void reportedCaseIsExecuted(@TempDir Path root, @TempDir Path run) throws IOException {
        String report = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="phpunit" tests="1" time="0.01">
                  <testcase classname="CwScSC_ROLLINGTest" name="test_case0000" time="0.01"/>
                </testsuite>
                """;

        GeneratedTestExecution execution = execute(root, run, report);

        assertTrue(execution.result().executed(), execution.note());
        assertEquals(1, execution.result().total());
        assertEquals(1, execution.result().passed());
        Path written = root.resolve("tests").resolve("CwScSC_ROLLINGTest.php");
        assertTrue(Files.isRegularFile(written),
                "the class is left in the project, because the replay command runs PHPUnit over the project");
        assertEquals(generatedTest().sourceCode(), Files.readString(written, StandardCharsets.UTF_8),
                "the file PHPUnit ran is the file the generator produced");
        assertEquals(1, execution.artifacts().size(), execution.artifacts().toString());
        assertTrue(execution.artifacts().getFirst().endsWith("generated-tests/CwScSC_ROLLINGTest.php"),
                "the run keeps its own copy so the evidence names the executed source: " + execution.artifacts());
    }

    @Test
    @DisplayName("the replay command is a real PHPUnit invocation carrying the seed and the case")
    void reproductionCommandCarriesTheSeed(@TempDir Path root, @TempDir Path run) throws IOException {
        PhpTestContexts.write(root, "phpunit.xml", "<phpunit/>\n");
        PhpTestContexts.write(root, "tests/LedgerTest.php", "<?php\n");
        PhpTestContexts.write(root, "vendor/bin/phpunit", "#!/usr/bin/env php\n");
        VerificationContext context = PhpTestContexts.context(
                PhpTestContexts.withAttributes(root,
                        Map.of(PhpInterpreter.ATTR_PHP, PhpTestContexts.MISSING_PHP)),
                run);

        List<String> command = new PhpGeneratedTestRunner()
                .reproductionCommand(context, List.of(generatedTest()), 42L, 0);
        String rendered = String.join(" ", command);

        assertEquals("env", command.getFirst(), "the values travel in the environment, as in every adapter: " + rendered);
        assertTrue(command.contains("CODEWITNESS_SEED=42"), rendered);
        assertTrue(command.contains("CODEWITNESS_CASE=0"), rendered);
        assertTrue(rendered.contains("phpunit"), "the command must actually run the runner: " + rendered);
        assertTrue(command.contains("--filter"), rendered);
        assertTrue(command.contains("CwScSC_ROLLINGTest"),
                "a --filter on the class is the only way to replay a generated case: " + rendered);
        assertTrue(command.contains("--do-not-cache-result"), rendered);
        assertTrue(command.contains("--configuration"),
                "the configuration file is the bootstrap, so without it the class cannot load: " + rendered);
    }

    @Test
    @DisplayName("with no runner the replay command is empty rather than a command that cannot work")
    void reproductionCommandWithoutPhpunitIsEmpty(@TempDir Path root, @TempDir Path run) throws IOException {
        PhpTestContexts.write(root, "tests/LedgerTest.php", "<?php\n");
        VerificationContext context = PhpTestContexts.context(
                PhpTestContexts.withAttributes(root,
                        Map.of(PhpInterpreter.ATTR_PHP, PhpTestContexts.MISSING_PHP)),
                run);

        assertEquals(List.of(), new PhpGeneratedTestRunner()
                .reproductionCommand(context, List.of(generatedTest()), 42L, 0));
        assertEquals(List.of(), new PhpGeneratedTestRunner().reproductionCommand(context, List.of(), null, null));
    }

    @Test
    @DisplayName("a batch of several classes is selected by one regular expression")
    void filterSelectsTheWholeBatch() {
        GeneratedTest second = new GeneratedTest("CwScSC_FEETest", "<?php\n", "test_single_case",
                List.of("R-002"), List.of("OB-003"), "scenario", 1L, null, true);

        assertEquals("CwScSC_ROLLINGTest", PhpGeneratedTestRunner.filter(List.of(generatedTest())));
        assertEquals("(CwScSC_ROLLINGTest|CwScSC_FEETest)",
                PhpGeneratedTestRunner.filter(List.of(generatedTest(), second)));
    }

    // ------------------------------------------------------------------ helpers

    /** Runs the executor against the report the substituted PHPUnit is about to write. */
    private static GeneratedTestExecution execute(Path root, Path run, String reportXml) throws IOException {
        PhpTestContexts.write(root, "tests/LedgerTest.php", "<?php\n");
        PhpTestContexts.write(root, "vendor/bin/phpunit", "#!/usr/bin/env php\n");
        VerificationContext context = PhpTestContexts.context(
                PhpTestContexts.withAttributes(root,
                        Map.of(PhpInterpreter.ATTR_PHP, PhpTestContexts.MISSING_PHP)),
                run, List.of(), sandboxWritingReport(run, reportXml));
        return new PhpGeneratedTestRunner().execute(context, List.of(generatedTest()));
    }

    private static VerificationContext context(Path root, Path run, ExecutionSandbox sandbox) {
        return PhpTestContexts.context(PhpTestContexts.projectInfo(root), run, List.of(), sandbox);
    }

    /**
     * @return a sandbox whose process runner writes {@code reportXml} where the executor looks for the
     *     JUnit report of the generated classes
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
                return "test double: writes the report the runner expects without starting PHPUnit";
            }

            @Override
            public boolean permits(Path path) {
                return true;
            }

            @Override
            public ProcessRunner runner() {
                return spec -> {
                    Path report = runDirectory.resolve("artifacts").resolve("php")
                            .resolve("generated-tests").resolve("junit.xml");
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
        return new GeneratedTest("CwScSC_ROLLINGTest",
                "<?php\n\nfinal class CwScSC_ROLLINGTest extends \\PHPUnit\\Framework\\TestCase\n{\n}\n",
                "test_random_search", List.of("R-001"), List.of("OB-001"), "scenario", 42L, null, true);
    }
}
