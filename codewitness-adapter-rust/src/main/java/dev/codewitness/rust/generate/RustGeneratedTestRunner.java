package dev.codewitness.rust.generate;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.GeneratedTestExecution;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.rust.Cargo;
import dev.codewitness.rust.RustTestReportParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Executes generated scenario tests and reads the structured counterexamples out of the output.
 *
 * <p>Unlike a Python test, a Rust test cannot live outside the crate: Cargo only discovers
 * integration tests under {@code tests/}. Generated sources are therefore written into the
 * workspace's {@code tests/} directory, run with {@code cargo test --test <name>}, and removed again
 * in a {@code finally} block unless {@code report.keep_generated_tests} is set. A copy is always kept
 * in the run's artefact store, which is what makes the counterexample reviewable after the run.
 *
 * <p>Because the project is copied into an isolated workspace by default, writing into {@code tests/}
 * never touches the user's working tree; with {@code --in-place} it does, exactly as the Java adapter
 * writes generated tests into the project's test sources.
 */
public final class RustGeneratedTestRunner implements VerificationTools.GeneratedTestExecutor {

    /** Subdirectory of the artefact store the generated sources are copied into. */
    static final String GENERATED_DIRECTORY = "generated-tests";

    @Override
    public String id() {
        return "rust-generated-tests";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.RUST);
    }

    @Override
    public GeneratedTestExecution execute(VerificationContext context, List<GeneratedTest> tests) {
        if (tests.isEmpty()) {
            return GeneratedTestExecution.notExecuted(tests, "no tests to run");
        }
        if (!Cargo.hasManifest(context)) {
            return GeneratedTestExecution.notExecuted(tests,
                    "no Cargo.toml was found, so generated tests cannot be compiled against the crate");
        }
        Path testRoot = context.projectRoot().resolve("tests");
        List<Path> written = new ArrayList<>();
        List<Path> artifacts = new ArrayList<>();
        try {
            Files.createDirectories(testRoot);
            for (GeneratedTest test : tests) {
                Path target = testRoot.resolve(test.className() + ".rs");
                Files.writeString(target, test.sourceCode(), StandardCharsets.UTF_8);
                written.add(target);
                artifacts.add(context.artifacts().copyIn(target, GENERATED_DIRECTORY + "/" + test.className() + ".rs"));
            }

            Long seed = tests.stream().map(GeneratedTest::seed).filter(java.util.Objects::nonNull)
                    .findFirst().orElse(null);
            String cargo = Cargo.cargo(context.projectInfo(), context);
            List<String> command = new ArrayList<>(List.of(cargo, "test", "--no-fail-fast"));
            for (GeneratedTest test : tests) {
                command.add("--test");
                command.add(test.className());
            }
            ProcessSpec.Builder spec = ProcessSpec.builder(command, context.projectRoot())
                    .environment(Cargo.environment(context))
                    .timeout(context.config().limits().timeout())
                    .maxOutputBytes(1_000_000);
            if (seed != null) {
                spec.env("CODEWITNESS_SEED", String.valueOf(seed));
            }
            ProcessResult result = context.runner().run(spec.build());
            if (!result.combinedOutput().isBlank()) {
                artifacts.add(context.artifacts().write("rust/tests/generated-test-run.txt", result.combinedOutput()));
            }

            TestResult parsed = RustTestReportParser.parse(result.stdout(), result.stderr(),
                    "generated test run (" + result.describeOutcome() + ")", List.of());
            Set<String> classNames = new LinkedHashSet<>();
            tests.forEach(test -> classNames.add(test.className()));
            List<TestCaseResult> scoped = parsed.cases().stream()
                    .filter(testCase -> classNames.contains(testCase.suite()))
                    .toList();
            if (scoped.isEmpty()) {
                String note = parsed.executed()
                        ? "cargo test ran but reported no case for the generated tests; last output: "
                                + Cargo.lastLines(result.combinedOutput(), 6)
                        : "the generated Rust tests could not be executed: " + parsed.note();
                return new GeneratedTestExecution(tests, TestResult.notExecuted(note), artifacts, note);
            }
            TestResult scopedResult = TestResult.from(scoped, parsed.duration(), List.of(), parsed.note());
            return new GeneratedTestExecution(tests, scopedResult, artifacts, scopedResult.describe());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot prepare generated tests", e);
        } finally {
            if (!context.config().report().keepGeneratedTests()) {
                for (Path path : written) {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException _) {
                        // leaving a generated file behind is untidy but not fatal
                    }
                }
            }
        }
    }

    @Override
    public List<String> reproductionCommand(
            VerificationContext context, List<GeneratedTest> tests, Long seed, Integer caseIndex) {
        if (tests.isEmpty()) {
            return List.of();
        }
        String cargo = Cargo.cargo(context.projectInfo(), context);
        List<String> command = new ArrayList<>();
        command.add("env");
        if (seed != null) {
            command.add("CODEWITNESS_SEED=" + seed);
        }
        if (caseIndex != null) {
            command.add("CODEWITNESS_CASE=" + caseIndex);
        }
        command.add(cargo);
        command.add("test");
        for (GeneratedTest test : tests) {
            command.add("--test");
            command.add(test.className());
        }
        return List.copyOf(command);
    }
}
