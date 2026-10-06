package dev.codewitness.go;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a Go module's existing tests with {@code go test -json ./...}.
 *
 * <p>One command covers the whole module: unit tests beside the code, external test packages and
 * examples. The results are read from Go's own JSON event stream by {@link GoTestReportParser}, and
 * the raw stream is stored as an artefact so the evidence can be checked after the run.
 *
 * <p>{@code -count=1} disables Go's test result cache on purpose: a cached "ok (cached)" line is not
 * an experiment that ran, and verification must never accept a remembered pass as an observation. A
 * run that produced no case at all is reported as "tests did not execute", never as a pass.
 */
public final class GoTestProvider implements VerificationTools.TestRunner {

    @Override
    public String id() {
        return "go-test";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.GO);
    }

    @Override
    public TestResult runTests(VerificationContext context) {
        if (!GoTool.hasManifest(context)) {
            return TestResult.notExecuted("no go.mod was found, so the tests cannot be run");
        }
        String go = GoTool.go(context.projectInfo(), context);
        List<String> command = List.of(go, "test", "-json", "-count=1", "./...");
        context.emit("tests", "running go test");

        ProcessResult result = context.runner().run(ProcessSpec.builder(command, context.projectRoot())
                .environment(GoTool.environment(context))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(2_000_000)
                .build());

        List<Path> reports = new ArrayList<>();
        if (!result.stdout().isBlank()) {
            reports.add(context.artifacts().write("go/tests/go-test.json", result.stdout()));
        }
        if (!result.started()) {
            return TestResult.notExecuted("go could not be started: " + result.failureReason());
        }
        return GoTestReportParser.parse(result.stdout(), result.stderr(),
                "parsed from go test -json; " + result.describeOutcome(), reports);
    }

    @Override
    public List<String> replayCommand(VerificationContext context, TestCaseResult testCase) {
        String go = GoTool.go(context.projectInfo(), context);
        // Go selects a test by a regular expression on its name, and -count=1 keeps the replay from
        // being answered out of the test cache.
        List<String> command = new ArrayList<>(List.of(go, "test", "-count=1", "-run", "^" + testCase.name() + "$"));
        if (testCase.suite() != null && !testCase.suite().isBlank()) {
            command.add(testCase.suite());
        } else {
            command.add("./...");
        }
        return List.copyOf(command);
    }
}
