package dev.codewitness.go.generate;

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
import dev.codewitness.go.GoTestReportParser;
import dev.codewitness.go.GoTool;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executes generated scenario tests and reads the structured counterexamples out of the output.
 *
 * <p>A Go test cannot live outside a package, so generated sources are written into the package under
 * test as {@code <name>_test.go}, run with {@code go test}, and removed again in a {@code finally}
 * block unless {@code report.keep_generated_tests} is set. A copy is always kept in the run's
 * artefact store, which is what makes the counterexample reviewable after the run.
 *
 * <p>Go reports every test under its package, so the parsed suite is the package import path rather
 * than the generated test. Each generated test's functions share its class name as a prefix, so the
 * results are re-attributed to the generated test that owns them before they are returned; the
 * engine's generated-test strategy can then pair each failure with the obligation it was produced
 * for. {@code -run} is used to keep the project's own tests out of the experiment.
 *
 * <p>Because the project is copied into an isolated workspace by default, writing into the package
 * never touches the user's working tree; with {@code --in-place} it does, exactly as the Java adapter
 * writes generated tests into the project's test sources.
 */
public final class GoGeneratedTestRunner implements VerificationTools.GeneratedTestExecutor {

    /** Subdirectory of the artefact store the generated sources are copied into. */
    static final String GENERATED_DIRECTORY = "generated-tests";

    @Override
    public String id() {
        return "go-generated-tests";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.GO);
    }

    @Override
    public GeneratedTestExecution execute(VerificationContext context, List<GeneratedTest> tests) {
        if (tests.isEmpty()) {
            return GeneratedTestExecution.notExecuted(tests, "no tests to run");
        }
        if (!GoTool.hasManifest(context)) {
            return GeneratedTestExecution.notExecuted(tests,
                    "no go.mod was found, so generated tests cannot be compiled against the module");
        }

        Map<Path, List<GeneratedTest>> byDirectory = new LinkedHashMap<>();
        for (GeneratedTest test : tests) {
            String requirement = test.requirementIds().isEmpty() ? null : test.requirementIds().getFirst();
            Path directory = GoScenarioTestGenerator.packageDir(context, requirement);
            byDirectory.computeIfAbsent(directory, ignored -> new ArrayList<>()).add(test);
        }

        List<Path> written = new ArrayList<>();
        List<Path> artifacts = new ArrayList<>();
        try {
            for (Map.Entry<Path, List<GeneratedTest>> entry : byDirectory.entrySet()) {
                Files.createDirectories(entry.getKey());
                for (GeneratedTest test : entry.getValue()) {
                    Path target = entry.getKey().resolve(test.className() + "_test.go");
                    Files.writeString(target, test.sourceCode(), StandardCharsets.UTF_8);
                    written.add(target);
                    artifacts.add(context.artifacts().copyIn(
                            target, GENERATED_DIRECTORY + "/" + test.className() + ".go"));
                }
            }

            List<TestCaseResult> scoped = new ArrayList<>();
            List<String> notes = new ArrayList<>();
            Duration duration = Duration.ZERO;
            Long seed = tests.stream().map(GeneratedTest::seed).filter(java.util.Objects::nonNull)
                    .findFirst().orElse(null);
            int runIndex = 0;
            for (Map.Entry<Path, List<GeneratedTest>> entry : byDirectory.entrySet()) {
                Set<String> classNames = new LinkedHashSet<>();
                entry.getValue().forEach(test -> classNames.add(test.className()));
                ProcessResult result = runTests(context, entry.getKey(), classNames, seed);
                runIndex++;
                if (!result.stdout().isBlank()) {
                    artifacts.add(context.artifacts().write(
                            "go/tests/generated-test-run-" + runIndex + ".json", result.stdout()));
                }
                TestResult parsed = GoTestReportParser.parse(result.stdout(), result.stderr(),
                        "generated test run (" + result.describeOutcome() + ")", List.of());
                scoped.addAll(reattribute(parsed.cases(), classNames));
                duration = duration.plus(parsed.duration());
                if (!parsed.executed()) {
                    notes.add(parsed.note() + " (last output: "
                            + GoTool.lastLines(result.combinedOutput(), 6) + ")");
                }
            }

            if (scoped.isEmpty()) {
                String note = "go test reported no case for the generated tests: "
                        + (notes.isEmpty() ? "no output" : String.join("; ", notes));
                return new GeneratedTestExecution(tests, TestResult.notExecuted(note), artifacts, note);
            }
            String note = "generated test run (" + scoped.size() + " case(s))";
            TestResult scopedResult = TestResult.from(scoped, duration, List.of(), note);
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

    private ProcessResult runTests(
            VerificationContext context, Path directory, Set<String> classNames, Long seed) {
        String go = GoTool.go(context.projectInfo(), context);
        List<String> command = new ArrayList<>(List.of(
                go, "test", "-json", "-count=1", "-run", runPattern(classNames), packageArgument(context, directory)));
        ProcessSpec.Builder spec = ProcessSpec.builder(command, context.projectRoot())
                .environment(GoTool.environment(context))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(2_000_000);
        if (seed != null) {
            spec.env("CODEWITNESS_SEED", String.valueOf(seed));
        }
        return context.runner().run(spec.build());
    }

    /**
     * @return the parsed cases with each suite replaced by the generated test whose class name prefixes
     *     the reported test function
     */
    private static List<TestCaseResult> reattribute(List<TestCaseResult> cases, Set<String> classNames) {
        List<TestCaseResult> scoped = new ArrayList<>();
        for (TestCaseResult testCase : cases) {
            for (String className : classNames) {
                if (testCase.name().startsWith(className)) {
                    scoped.add(new TestCaseResult(className, testCase.name(), testCase.outcome(),
                            testCase.duration(), testCase.failureType(), testCase.failureMessage(),
                            testCase.stackTrace()));
                    break;
                }
            }
        }
        return scoped;
    }

    /** @return a {@code -run} pattern matching every generated function, which all share a class prefix. */
    private static String runPattern(Set<String> classNames) {
        return "^(" + String.join("|", classNames) + ")";
    }

    /** @return the package argument for {@code go test}, relative to the project root. */
    private static String packageArgument(VerificationContext context, Path directory) {
        Path root = context.projectRoot();
        if (directory == null || directory.equals(root)) {
            return ".";
        }
        return "./" + root.relativize(directory).toString().replace(java.io.File.separatorChar, '/');
    }

    @Override
    public List<String> reproductionCommand(
            VerificationContext context, List<GeneratedTest> tests, Long seed, Integer caseIndex) {
        if (tests.isEmpty()) {
            return List.of();
        }
        Set<String> classNames = new LinkedHashSet<>();
        tests.forEach(test -> classNames.add(test.className()));
        String requirement = tests.getFirst().requirementIds().isEmpty()
                ? null : tests.getFirst().requirementIds().getFirst();
        Path directory = GoScenarioTestGenerator.packageDir(context, requirement);

        List<String> command = new ArrayList<>();
        command.add("env");
        if (seed != null) {
            command.add("CODEWITNESS_SEED=" + seed);
        }
        if (caseIndex != null) {
            command.add("CODEWITNESS_CASE=" + caseIndex);
        }
        command.add(GoTool.go(context.projectInfo(), context));
        command.add("test");
        command.add("-count=1");
        command.add("-run");
        command.add(runPattern(classNames));
        command.add(packageArgument(context, directory));
        return List.copyOf(command);
    }
}
