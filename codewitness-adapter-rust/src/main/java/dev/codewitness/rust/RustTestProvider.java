package dev.codewitness.rust;

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
 * Runs a Rust project's existing tests with {@code cargo test}.
 *
 * <p>One command covers the whole crate: unit tests inside {@code src}, integration tests under
 * {@code tests/}, and documentation tests. The results are parsed by
 * {@link RustTestReportParser} from the runner's own line protocol, and the raw output is stored as
 * an artefact so the evidence can be checked after the run.
 *
 * <p>{@code --no-fail-fast} is used on purpose: a verification run wants every failing test it can
 * observe, not the first one. A run that produced no case at all is reported as "tests did not
 * execute", never as a pass.
 */
public final class RustTestProvider implements VerificationTools.TestRunner {

    @Override
    public String id() {
        return "cargo-test";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.RUST);
    }

    @Override
    public TestResult runTests(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        if (!Cargo.hasManifest(context)) {
            return TestResult.notExecuted("no Cargo.toml was found, so the tests cannot be run");
        }
        String cargo = Cargo.cargo(project, context);
        List<String> command = List.of(cargo, "test", "--no-fail-fast");
        context.emit("tests", "running cargo test");

        ProcessResult result = context.runner().run(ProcessSpec.builder(command, context.projectRoot())
                .environment(Cargo.environment(context))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(1_000_000)
                .build());

        List<Path> reports = new ArrayList<>();
        if (!result.combinedOutput().isBlank()) {
            reports.add(context.artifacts().write("rust/tests/cargo-test.txt", result.combinedOutput()));
        }
        if (!result.started()) {
            return TestResult.notExecuted("cargo could not be started: " + result.failureReason());
        }
        return RustTestReportParser.parse(result.stdout(), result.stderr(),
                "parsed from cargo test output; " + result.describeOutcome(), reports);
    }

    @Override
    public List<String> replayCommand(VerificationContext context, TestCaseResult testCase) {
        String cargo = Cargo.cargo(context.projectInfo(), context);
        // --exact takes the test path libtest printed, which is unique across the crate's targets.
        return List.of(cargo, "test", "--", "--exact", testCase.name());
    }
}
