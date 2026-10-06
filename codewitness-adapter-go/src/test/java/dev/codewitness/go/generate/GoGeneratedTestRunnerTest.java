package dev.codewitness.go.generate;

import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.GeneratedTestExecution;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.go.GoTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The generated-test executor's contract for the cases that do not need a Go toolchain. */
class GoGeneratedTestRunnerTest {

    private static GeneratedTest generatedTest() {
        return new GeneratedTest("TestCwScRollingOb001",
                "package ledger\n\nimport \"testing\"\n\nfunc TestCwScRollingOb001Case0000(t *testing.T) {}\n",
                "TestCwScRollingOb001", List.of("R-001"), List.of("OB-001"), "scenario", 42L, null);
    }

    @Test
    @DisplayName("an empty batch is reported as not executed, never as a pass")
    void emptyBatchIsNotExecuted(@TempDir Path root, @TempDir Path runDirectory) {
        VerificationContext context = GoTestContexts.context(root, runDirectory);

        GeneratedTestExecution execution =
                new GoGeneratedTestRunner().execute(context, List.of());

        assertFalse(execution.result().executed());
        assertEquals("no tests to run", execution.note());
    }

    @Test
    @DisplayName("the replay command pins the seed, the case and the generated function")
    void buildsAReplayCommand(@TempDir Path root, @TempDir Path runDirectory) {
        VerificationContext context = GoTestContexts.context(root, runDirectory);

        List<String> command = new GoGeneratedTestRunner()
                .reproductionCommand(context, List.of(generatedTest()), 42L, 7);

        assertTrue(command.contains("env"), command.toString());
        assertTrue(command.contains("CODEWITNESS_SEED=42"), command.toString());
        assertTrue(command.contains("CODEWITNESS_CASE=7"), command.toString());
        assertTrue(command.contains("test"), command.toString());
        assertTrue(command.contains("-run"), command.toString());
        assertTrue(command.stream().anyMatch(part -> part.startsWith("^(TestCwScRollingOb001)")), command.toString());
    }
}
