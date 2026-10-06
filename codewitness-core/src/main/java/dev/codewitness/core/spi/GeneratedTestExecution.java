package dev.codewitness.core.spi;

import dev.codewitness.core.model.TestResult;

import java.nio.file.Path;
import java.util.List;

/**
 * The outcome of compiling and executing a batch of generated tests.
 *
 * @param executed   generated tests that were run
 * @param result     aggregated test result
 * @param artifacts  copies of the generated sources and raw reports, stored in the run directory
 * @param note       infrastructure explanation when the batch could not run
 */
public record GeneratedTestExecution(
        List<GeneratedTest> executed,
        TestResult result,
        List<Path> artifacts,
        String note) {

    public GeneratedTestExecution {
        executed = List.copyOf(executed == null ? List.of() : executed);
        artifacts = List.copyOf(artifacts == null ? List.of() : artifacts);
    }

    public static GeneratedTestExecution notExecuted(List<GeneratedTest> tests, String note) {
        return new GeneratedTestExecution(tests, TestResult.notExecuted(note), List.of(), note);
    }
}
