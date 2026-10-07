package dev.codewitness.csharp.generate;

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
import dev.codewitness.csharp.CSharpBuildProvider;
import dev.codewitness.csharp.CSharpProjectDetector;
import dev.codewitness.csharp.CSharpTestProvider;
import dev.codewitness.csharp.CSharpTestReportParser;
import dev.codewitness.csharp.DotnetTool;

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
import java.util.Objects;
import java.util.Set;

/**
 * Executes generated scenario tests and reads the structured counterexamples out of the TRX report.
 *
 * <p>A generated test has to be compiled by the .NET SDK and therefore has to live in a test project, so
 * each generated class is written beside that project's own sources as {@code <ClassName>.cs}, run with
 * {@code dotnet test} filtered to the generated classes, and removed again in a {@code finally} block
 * unless {@code report.keep_generated_tests} is set. A copy is always kept in the run's artefact store,
 * which is what makes the counterexample reviewable after the run.
 *
 * <p>The filter matters as much as the run: without it the project's own tests would run too, and every
 * one of their failures would be attributed to a generated test. Each generated class is selected by its
 * fully qualified name, which is also the name the TRX report records, so the cases are attributed back
 * to the generated test that owns them without guessing from a display name.
 *
 * <p>Because the project is copied into an isolated workspace by default, writing into the project never
 * touches the user's working tree; with {@code --in-place} it does, exactly as the Java adapter writes
 * generated tests into the project's test sources.
 */
public final class CSharpGeneratedTestRunner implements VerificationTools.GeneratedTestExecutor {

    /** Subdirectory of the artefact store the generated sources are copied into. */
    static final String GENERATED_DIRECTORY = "generated-tests";

    @Override
    public String id() {
        return "csharp-generated-tests";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.CSHARP);
    }

    @Override
    public GeneratedTestExecution execute(VerificationContext context, List<GeneratedTest> tests) {
        if (tests.isEmpty()) {
            return GeneratedTestExecution.notExecuted(tests, "no tests to run");
        }
        if (CSharpProjectDetector.testProjects(context.projectRoot()).isEmpty()) {
            return GeneratedTestExecution.notExecuted(tests,
                    "the project has no test project, so a generated test has nothing to be compiled by");
        }
        Map<Path, List<GeneratedTest>> byDirectory = new LinkedHashMap<>();
        for (GeneratedTest test : tests) {
            String requirement = test.requirementIds().isEmpty() ? null : test.requirementIds().getFirst();
            Path directory = CSharpScenarioTestGenerator.testDirectory(context, requirement);
            if (directory != null) {
                byDirectory.computeIfAbsent(directory, ignored -> new ArrayList<>()).add(test);
            }
        }
        if (byDirectory.isEmpty()) {
            return GeneratedTestExecution.notExecuted(tests,
                    "no test project directory could be found for the generated tests");
        }

        List<Path> written = new ArrayList<>();
        List<Path> artifacts = new ArrayList<>();
        List<TestCaseResult> scoped = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Duration duration = Duration.ZERO;
        Long seed = tests.stream().map(GeneratedTest::seed).filter(Objects::nonNull).findFirst().orElse(null);
        try {
            int runIndex = 0;
            for (Map.Entry<Path, List<GeneratedTest>> entry : byDirectory.entrySet()) {
                runIndex++;
                Path directory = entry.getKey();
                Path project = projectIn(directory);
                if (project == null) {
                    notes.add("no test project was found in " + directory);
                    continue;
                }
                Set<String> classNames = new LinkedHashSet<>();
                for (GeneratedTest test : entry.getValue()) {
                    Path target = directory.resolve(test.simpleName() + ".cs");
                    Files.writeString(target, test.sourceCode(), StandardCharsets.UTF_8);
                    written.add(target);
                    classNames.add(test.className());
                    artifacts.add(context.artifacts().copyIn(target,
                            GENERATED_DIRECTORY + "/" + test.simpleName() + ".cs"));
                }

                Path resultsDirectory = context.artifacts().artifactsDirectory()
                        .resolve("dotnet/generated/" + runIndex);
                try {
                    Files.createDirectories(resultsDirectory);
                    Files.deleteIfExists(resultsDirectory.resolve(CSharpTestProvider.REPORT_NAME));
                } catch (IOException e) {
                    notes.add("cannot prepare " + resultsDirectory + ": " + e);
                    continue;
                }
                ProcessResult result = runTests(context, project, resultsDirectory, classNames, seed);
                Path report = resultsDirectory.resolve(CSharpTestProvider.REPORT_NAME);
                if (!Files.isRegularFile(report)) {
                    notes.add("dotnet test produced no TRX report for the generated tests ("
                            + result.describeOutcome() + "); last output: "
                            + DotnetTool.lastLines(result.combinedOutput(), 6));
                    continue;
                }
                TestResult parsed = CSharpTestReportParser.parse(report,
                        "generated test run (" + result.describeOutcome() + ")");
                if (!parsed.executed()) {
                    notes.add(parsed.note());
                    continue;
                }
                scoped.addAll(reattribute(parsed.cases(), classNames));
                duration = duration.plus(parsed.duration());
                artifacts.add(report);
            }

            if (scoped.isEmpty()) {
                String note = "dotnet test reported no case for the generated tests: "
                        + (notes.isEmpty() ? "no output" : String.join("; ", notes));
                return new GeneratedTestExecution(tests, TestResult.notExecuted(note), artifacts, note);
            }
            String note = "generated test run (" + scoped.size() + " case(s))"
                    + (notes.isEmpty() ? "" : "; " + String.join("; ", notes));
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
            VerificationContext context, Path project, Path resultsDirectory,
            Set<String> classNames, Long seed) {

        List<String> command = new ArrayList<>();
        command.add(DotnetTool.dotnet(context.projectInfo(), context));
        command.add("test");
        command.add(CSharpBuildProvider.relativeArgument(context.projectRoot(), project));
        command.addAll(DotnetTool.commonArguments());
        command.add("--results-directory");
        command.add(resultsDirectory.toString());
        command.add("--logger");
        command.add("trx;LogFileName=" + CSharpTestProvider.REPORT_NAME);
        command.add("--filter");
        command.add(filterFor(classNames));
        ProcessSpec.Builder spec = ProcessSpec.builder(command, context.projectRoot())
                .environment(DotnetTool.environment(context))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(2_000_000);
        if (seed != null) {
            spec.env("CODEWITNESS_SEED", String.valueOf(seed));
        }
        return context.runner().run(spec.build());
    }

    /** @return a VSTest filter selecting exactly the generated classes. */
    public static String filterFor(Set<String> classNames) {
        StringBuilder sb = new StringBuilder();
        for (String className : classNames) {
            if (!sb.isEmpty()) {
                sb.append('|');
            }
            sb.append("FullyQualifiedName~").append(className);
        }
        return sb.toString();
    }

    /**
     * @return the parsed cases with each suite replaced by the generated test whose class the report
     *     named, so the engine can pair a failure with the obligation it was produced for.
     */
    private static List<TestCaseResult> reattribute(List<TestCaseResult> cases, Set<String> classNames) {
        List<TestCaseResult> scoped = new ArrayList<>();
        for (TestCaseResult testCase : cases) {
            for (String className : classNames) {
                String simpleName = className.substring(className.lastIndexOf('.') + 1);
                if (testCase.suite().equals(className) || testCase.suite().endsWith("." + simpleName)) {
                    scoped.add(new TestCaseResult(className, testCase.name(), testCase.outcome(),
                            testCase.duration(), testCase.failureType(), testCase.failureMessage(),
                            testCase.stackTrace()));
                    break;
                }
            }
        }
        return scoped;
    }

    /** @return the test project inside a directory, or {@code null} when there is none. */
    public static Path projectIn(Path directory) {
        for (Path project : CSharpProjectDetector.projectFiles(directory == null ? null : directory)) {
            if (project.getParent() != null && project.getParent().equals(directory)) {
                return project;
            }
        }
        return null;
    }

    @Override
    public List<String> reproductionCommand(
            VerificationContext context, List<GeneratedTest> tests, Long seed, Integer caseIndex) {
        if (tests.isEmpty()) {
            return List.of();
        }
        String requirement = tests.getFirst().requirementIds().isEmpty()
                ? null : tests.getFirst().requirementIds().getFirst();
        Path directory = CSharpScenarioTestGenerator.testDirectory(context, requirement);
        Path project = projectIn(directory);
        if (project == null) {
            return List.of();
        }
        Set<String> classNames = new LinkedHashSet<>();
        tests.forEach(test -> classNames.add(test.className()));

        List<String> command = new ArrayList<>();
        command.add("env");
        if (seed != null) {
            command.add("CODEWITNESS_SEED=" + seed);
        }
        if (caseIndex != null) {
            command.add("CODEWITNESS_CASE=" + caseIndex);
        }
        command.add(DotnetTool.dotnet(context.projectInfo(), context));
        command.add("test");
        command.add(CSharpBuildProvider.relativeArgument(context.projectRoot(), project));
        command.addAll(DotnetTool.commonArguments());
        command.add("--filter");
        command.add(filterFor(classNames));
        return List.copyOf(command);
    }
}
