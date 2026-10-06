package dev.codewitness.typescript.generate;

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
import dev.codewitness.typescript.NodeRuntime;
import dev.codewitness.typescript.TypeScriptTestProvider;
import dev.codewitness.typescript.TypeScriptTestReportParser;

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
 * Executes generated scenario tests and reads the structured counterexamples out of them.
 *
 * <p>Unlike a Rust integration test, a Node test file does not have to live in a particular directory:
 * the runner names every generated file explicitly on the {@code node --test} command line. The files
 * are still written beside the project's own tests, because a scenario fragment imports the project
 * with a relative path, and writing into the test directory is what makes that path the same one a
 * hand-written test would use.
 *
 * <p>Generated sources are removed again in a {@code finally} block unless
 * {@code report.keep_generated_tests} is set. A copy is always kept in the run's artefact store, which
 * is what makes a counterexample reviewable after the run. Because the project is copied into an
 * isolated workspace by default, writing into the test directory never touches the user's working tree;
 * with {@code --in-place} it does, exactly as the Java adapter writes generated tests into the project's
 * test sources.
 */
public final class TypeScriptGeneratedTestRunner implements VerificationTools.GeneratedTestExecutor {

    /** Subdirectory of the artefact store the generated sources are copied into. */
    static final String GENERATED_DIRECTORY = "generated-tests";

    /** Suffix every generated test file carries, so the report names the generated class. */
    static final String SOURCE_SUFFIX = ".test.ts";

    @Override
    public String id() {
        return "typescript-generated-tests";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.TYPESCRIPT);
    }

    @Override
    public GeneratedTestExecution execute(VerificationContext context, List<GeneratedTest> tests) {
        if (tests.isEmpty()) {
            return GeneratedTestExecution.notExecuted(tests, "no tests to run");
        }
        Path directory = generatedDirectory(context);
        List<Path> written = new ArrayList<>();
        List<Path> artifacts = new ArrayList<>();
        Path report = TypeScriptTestProvider.prepareReport(
                context, "typescript/generated-tests/node-junit.xml");
        try {
            Files.createDirectories(directory);
            for (GeneratedTest test : tests) {
                Path target = directory.resolve(test.className() + SOURCE_SUFFIX);
                Files.writeString(target, test.sourceCode(), StandardCharsets.UTF_8);
                written.add(target);
                artifacts.add(context.artifacts().copyIn(target,
                        GENERATED_DIRECTORY + "/" + test.className() + SOURCE_SUFFIX));
            }

            Long seed = tests.stream().map(GeneratedTest::seed).filter(java.util.Objects::nonNull)
                    .findFirst().orElse(null);
            String node = NodeRuntime.node(context.projectInfo(), context);
            List<String> command = new ArrayList<>(List.of(
                    node, "--test", "--test-reporter=junit", "--test-reporter-destination=" + report));
            written.forEach(file -> command.add(file.toString()));
            ProcessSpec.Builder spec = ProcessSpec.builder(command, context.projectRoot())
                    .environment(NodeRuntime.environment(context))
                    .timeout(context.config().limits().timeout())
                    .maxOutputBytes(1_000_000);
            if (seed != null) {
                spec.env("CODEWITNESS_SEED", String.valueOf(seed));
            }
            ProcessResult result = context.runner().run(spec.build());
            if (!result.combinedOutput().isBlank()) {
                artifacts.add(context.artifacts().write(
                        "typescript/generated-tests/generated-test-run.txt", result.combinedOutput()));
            }
            if (!Files.isRegularFile(report)) {
                String note = "the generated TypeScript tests produced no JUnit report ("
                        + result.describeOutcome() + "); last output: "
                        + NodeRuntime.lastLines(result.combinedOutput(), 6);
                return new GeneratedTestExecution(tests, TestResult.notExecuted(note), artifacts, note);
            }

            TestResult parsed = TypeScriptTestReportParser.parse(report,
                    "generated test run (" + result.describeOutcome() + ")", List.of());
            Set<String> classNames = new LinkedHashSet<>();
            tests.forEach(test -> classNames.add(test.className()));
            List<TestCaseResult> scoped = parsed.cases().stream()
                    .filter(testCase -> classNames.contains(testCase.suite()))
                    .toList();
            if (scoped.isEmpty()) {
                String note = parsed.executed()
                        ? "node --test ran but reported no case for the generated tests; last output: "
                                + NodeRuntime.lastLines(result.combinedOutput(), 6)
                        : "the generated TypeScript tests could not be executed: " + parsed.note();
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
        Path directory = generatedDirectory(context);
        String node = NodeRuntime.node(context.projectInfo(), context);
        List<String> command = new ArrayList<>();
        command.add("env");
        if (seed != null) {
            command.add("CODEWITNESS_SEED=" + seed);
        }
        if (caseIndex != null) {
            command.add("CODEWITNESS_CASE=" + caseIndex);
        }
        command.add(node);
        command.add("--test");
        for (GeneratedTest test : tests) {
            command.add(directory.resolve(test.className() + SOURCE_SUFFIX).toString());
        }
        return List.copyOf(command);
    }

    /**
     * @return the directory generated tests are written into: the project's first test source
     *     directory, so a scenario fragment's relative imports match the project's own tests, or the
     *     project root when the project has no test directory
     */
    static Path generatedDirectory(VerificationContext context) {
        List<Path> testDirs = context.projectInfo().testSourceDirs();
        return testDirs.isEmpty() ? context.projectRoot() : testDirs.getFirst();
    }
}
