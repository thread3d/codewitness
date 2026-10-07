package dev.codewitness.kotlin.generate;

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
import dev.codewitness.kotlin.KotlinBuildSystem;
import dev.codewitness.kotlin.KotlinBuildTools;
import dev.codewitness.kotlin.KotlinTestReportParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Compiles and runs generated Kotlin tests inside the project's own build.
 *
 * <p>Generated sources are copied into a test source root of the workspace, the build tool is asked to run
 * exactly those test classes, the raw reports are parsed, and then the sources are removed again. Copies of
 * the sources and of the raw reports are kept in the run directory before removal, which is what makes a
 * counterexample reproducible after the run has finished.
 *
 * <p>Cleanup happens in a {@code finally} block: a crash must not leave generated tests behind in a user's
 * project.
 *
 * <p>A Kotlin generated test is a {@code .kt} file, which is why the file name is derived here rather than
 * from {@link GeneratedTest#relativePath()}: that method is the Java convention and would write Kotlin into
 * a file the Kotlin compiler never reads.
 */
public final class KotlinGeneratedTestRunner implements VerificationTools.GeneratedTestExecutor {

    private static final String GENERATED_PACKAGE = "dev.codewitness.generated";

    private final KotlinBuildTools tools;
    private final KotlinBuildSystem buildSystem;

    public KotlinGeneratedTestRunner(KotlinBuildTools tools, KotlinBuildSystem buildSystem) {
        this.tools = tools;
        this.buildSystem = buildSystem;
    }

    @Override
    public String id() {
        return "kotlin-generated-tests-" + buildSystem.id();
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.KOTLIN)
                && project.buildSystem().id().equals(buildSystem.id());
    }

    /**
     * Replays generated tests, pinning the seed and case index when they are known.
     *
     * <p>For Maven the seed and case index travel as system properties, which Surefire forwards to the test
     * JVM. Gradle does not forward system properties to the test JVM, so they are passed through the
     * environment instead; the generated tests read both.
     */
    @Override
    public List<String> reproductionCommand(
            VerificationContext context, List<GeneratedTest> tests, Long seed, Integer caseIndex) {
        List<String> build = context.state().build().command();
        List<String> command = new ArrayList<>();
        if (buildSystem.id().equals("gradle")) {
            command.add("env");
            if (seed != null) {
                command.add("CODEWITNESS_SEED=" + seed);
            }
            if (caseIndex != null) {
                command.add("CODEWITNESS_CASE=" + caseIndex);
            }
            command.add(build.isEmpty() ? "./gradlew" : build.getFirst());
            command.add("test");
            for (GeneratedTest test : tests) {
                command.add("--tests");
                command.add(test.className());
            }
            return List.copyOf(command);
        }
        command.add(build.isEmpty() ? "mvn" : build.getFirst());
        command.add("-Dtest=" + tests.stream().map(GeneratedTest::simpleName)
                .collect(java.util.stream.Collectors.joining(",")));
        if (seed != null) {
            command.add("-D" + dev.codewitness.core.engine.CounterexampleProtocol.seedProperty() + "=" + seed);
        }
        if (caseIndex != null) {
            command.add("-D" + dev.codewitness.core.engine.CounterexampleProtocol.caseProperty() + "=" + caseIndex);
        }
        command.add("test");
        return List.copyOf(command);
    }

    @Override
    public GeneratedTestExecution execute(VerificationContext context, List<GeneratedTest> tests) {
        if (tests.isEmpty()) {
            return GeneratedTestExecution.notExecuted(tests, "no tests to run");
        }
        ProjectInfo project = context.projectInfo();
        Path testRoot = testSourceRoot(project);
        if (testRoot == null) {
            return GeneratedTestExecution.notExecuted(tests, "no test source directory exists in the project, "
                    + "so generated tests cannot be compiled against it");
        }
        List<String> executable = tools.resolve(project, buildSystem.buildRoot(project));
        if (executable == null) {
            return GeneratedTestExecution.notExecuted(tests, "no " + buildSystem.id() + " executable found");
        }

        List<Path> written = new ArrayList<>();
        List<Path> artifacts = new ArrayList<>();
        try {
            for (GeneratedTest test : tests) {
                Path target = testRoot.resolve(relativeSourcePath(test));
                Files.createDirectories(target.getParent());
                Files.writeString(target, test.sourceCode(), StandardCharsets.UTF_8);
                written.add(target);
            }
            KotlinTestReportParser.clearReports(project);

            Map<String, String> properties = new LinkedHashMap<>();
            Long seed = tests.stream().map(GeneratedTest::seed).filter(Objects::nonNull).findFirst().orElse(null);
            if (seed != null) {
                properties.put("codewitness.seed", String.valueOf(seed));
            }

            List<String> classNames = tests.stream().map(GeneratedTest::className).distinct().toList();
            ProcessResult result = context.runner().run(ProcessSpec.builder(
                            KotlinBuildTools.command(executable,
                                    buildSystem.targetedTestGoals(classNames, properties)),
                            buildSystem.buildRoot(project))
                    .env("CODEWITNESS_SEED", seed == null ? "" : String.valueOf(seed))
                    .timeout(generatedTestTimeout(context))
                    .maxOutputBytes(1_000_000)
                    .build());

            List<Path> reports = KotlinTestReportParser.findReportFiles(project);
            for (GeneratedTest test : tests) {
                Path source = testRoot.resolve(relativeSourcePath(test));
                if (Files.isRegularFile(source)) {
                    artifacts.add(context.artifacts().copyIn(source, "generated-tests/" + relativeSourcePath(test)));
                }
            }
            for (Path report : reports) {
                artifacts.add(context.artifacts().copyIn(report,
                        "generated-tests/reports/" + report.getParent().getFileName() + "/" + report.getFileName()));
            }

            if (reports.isEmpty()) {
                String note = "the build tool produced no test reports for the generated tests ("
                        + result.describeOutcome() + "). Last output: " + tail(result.combinedOutput(), 4000);
                return new GeneratedTestExecution(tests, TestResult.notExecuted(note), artifacts, note);
            }

            TestResult parsed = KotlinTestReportParser.parse(reports,
                    "generated test run (" + result.describeOutcome() + ")");
            List<TestCaseResult> generatedCases = parsed.cases().stream()
                    .filter(caseResult -> caseResult.suite() != null
                            && caseResult.suite().startsWith(GENERATED_PACKAGE))
                    .toList();
            if (generatedCases.isEmpty()) {
                // Every generated test carries a non-empty case list, and the build tool was asked to run
                // exactly the generated classes. A parsed result that mentions none of them therefore means
                // the generated tests did not run at all - it is not a pass with zero cases. Falling back to
                // every parsed case would attribute unrelated tests, and their failures, to this obligation,
                // so the batch is reported as not executed instead.
                String note = "the build tool reported no test case for the generated tests ("
                        + result.describeOutcome() + "): " + parsed.note()
                        + ". Last output: " + tail(result.combinedOutput(), 2000);
                return new GeneratedTestExecution(tests, TestResult.notExecuted(note), artifacts, note);
            }
            TestResult scoped = TestResult.from(generatedCases, parsed.duration(), reports, parsed.note());
            return new GeneratedTestExecution(tests, scoped, artifacts, scoped.describe());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot prepare generated tests", e);
        } finally {
            // Cleanup happens here, but nothing is returned from here: a return in a finally block would
            // discard the in-flight UncheckedIOException above and could hand the caller null. With
            // report.keep_generated_tests=true the sources stay in the project so that the reproduction
            // command in the report can be run as printed.
            if (!context.config().report().keepGeneratedTests()) {
                for (Path path : written) {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException _) {
                        // leaving a generated file behind is untidy but not fatal
                    }
                }
                cleanEmptyDirectories(testRoot.resolve(GENERATED_PACKAGE.replace('.', '/')));
            }
        }
    }

    /** @return where a generated test's source goes, relative to the test source root. */
    public static String relativeSourcePath(GeneratedTest test) {
        return test.className().replace('.', '/') + ".kt";
    }

    /** Finds the test source root to write generated tests into. */
    private static Path testSourceRoot(ProjectInfo project) {
        for (var module : project.modules()) {
            for (Path candidate : module.testSourceDirs()) {
                if (Files.isDirectory(candidate)) {
                    return candidate;
                }
            }
        }
        // A Kotlin project with no test root yet still gets one rather than being told it cannot be
        // verified: the conventional directory is created inside the disposable workspace, which is where a
        // generated test belongs.
        for (var module : project.modules()) {
            Path candidate = module.path().resolve("src/test/kotlin");
            try {
                Files.createDirectories(candidate);
                return candidate;
            } catch (IOException _) {
                // try the next module
            }
        }
        return null;
    }

    private static void cleanEmptyDirectories(Path directory) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            List<Path> directories = walk.filter(Files::isDirectory)
                    .sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path path : directories) {
                try (Stream<Path> children = Files.list(path)) {
                    if (children.findAny().isEmpty()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        } catch (IOException _) {
            // best effort cleanup
        }
    }

    private static String tail(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(text.length() - max);
    }

    /** @return the duration to allow for a generated test run. */
    static Duration generatedTestTimeout(VerificationContext context) {
        return context.config().limits().timeout();
    }

    /** @return the generated test package name. */
    public static String generatedPackage() {
        return GENERATED_PACKAGE;
    }

    /** @return {@code true} when the path belongs to a generated test source. */
    public static boolean isGeneratedSource(Path path) {
        return path != null && path.toString().replace('\\', '/').toLowerCase(Locale.ROOT)
                .contains(GENERATED_PACKAGE.replace('.', '/').toLowerCase(Locale.ROOT));
    }
}
