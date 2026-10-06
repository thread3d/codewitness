package dev.codewitness.java.generate;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.GeneratedTestExecution;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.java.BuildToolLauncher;
import dev.codewitness.java.TestReportParser;
import dev.codewitness.java.build.JavaBuildSystem;

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
import java.util.stream.Stream;

/**
 * Compiles and runs generated tests inside the project's own build.
 *
 * <p>Generated sources are copied into a test source root of the workspace, the build tool is asked
 * to run exactly those test classes, the raw reports are parsed, and then the sources are removed
 * again. Copies of the sources and of the raw reports are kept in the run directory before removal,
 * which is what makes a counterexample reproducible after the run has finished.
 *
 * <p>Cleanup happens in a {@code finally} block: a crash must not leave generated tests behind in a
 * user's project.
 */
public final class JavaGeneratedTestRunner implements VerificationTools.GeneratedTestExecutor {

    private static final String GENERATED_PACKAGE = "dev.codewitness.generated";

    private final BuildToolLauncher launcher;
    private final JavaBuildSystem buildSystem;

    public JavaGeneratedTestRunner(BuildToolLauncher launcher, JavaBuildSystem buildSystem) {
        this.launcher = launcher;
        this.buildSystem = buildSystem;
    }

    @Override
    public String id() {
        return "java-generated-tests-" + buildSystem.id();
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.buildSystem().id().equals(buildSystem.id());
    }

    @Override
    public GeneratedTestExecution execute(VerificationContext context, List<GeneratedTest> tests) {
        if (tests.isEmpty()) {
            return GeneratedTestExecution.notExecuted(tests, "no tests to run");
        }
        ProjectInfo project = context.projectInfo();
        Path testRoot = testSourceRoot(project);
        if (testRoot == null) {
            return GeneratedTestExecution.notExecuted(tests, "no test source directory exists in the project, so "
                    + "generated tests cannot be compiled against it");
        }
        List<String> executable = launcher.resolve(project, buildSystem.buildRoot(project));
        if (executable == null) {
            return GeneratedTestExecution.notExecuted(tests, "no " + buildSystem.id() + " executable found");
        }

        List<Path> written = new ArrayList<>();
        List<Path> artifacts = new ArrayList<>();
        GeneratedTestExecution lastResult = null;
        try {
            for (GeneratedTest test : tests) {
                Path target = testRoot.resolve(test.relativePath());
                Files.createDirectories(target.getParent());
                Files.writeString(target, test.sourceCode(), StandardCharsets.UTF_8);
                written.add(target);
            }
            TestReportParser.clearReports(project);

            Map<String, String> properties = new LinkedHashMap<>();
            Long seed = tests.stream().map(GeneratedTest::seed).filter(java.util.Objects::nonNull).findFirst()
                    .orElse(null);
            if (seed != null) {
                properties.put("codewitness.seed", String.valueOf(seed));
            }

            List<String> classNames = tests.stream().map(GeneratedTest::className).distinct().toList();
            ProcessSpec spec = ProcessSpec.builder(
                            BuildToolLauncher.command(executable,
                                    buildSystem.targetedTestGoals(classNames, properties)),
                            buildSystem.buildRoot(project))
                    .env("CODEWITNESS_SEED", seed == null ? "" : String.valueOf(seed))
                    .timeout(context.config().limits().timeout())
                    .maxOutputBytes(1_000_000)
                    .build();
            ProcessResult result = context.runner().run(spec);

            List<Path> reports = TestReportParser.findReportFiles(project);
            for (GeneratedTest test : tests) {
                Path source = testRoot.resolve(test.relativePath());
                artifacts.add(context.artifacts().copyIn(source, "generated-tests/" + test.relativePath()));
            }
            for (Path report : reports) {
                artifacts.add(context.artifacts().copyIn(report,
                        "generated-tests/reports/" + report.getParent().getFileName() + "/"
                                + report.getFileName()));
            }

            if (reports.isEmpty()) {
                String note = "the build tool produced no test reports for the generated tests ("
                        + result.describeOutcome() + "). Last output: " + tail(result.combinedOutput(), 4000);
                lastResult = new GeneratedTestExecution(tests, TestResult.notExecuted(note), artifacts, note);
                return lastResult;
            }

            TestResult parsed = TestReportParser.parse(reports, "generated test run (" + result.describeOutcome() + ")");
            List<TestCaseResult> generatedCases = parsed.cases().stream()
                    .filter(c -> c.suite() != null && c.suite().startsWith(GENERATED_PACKAGE))
                    .toList();
            if (generatedCases.isEmpty()) {
                // The test class may be reported under a different name (for example a nested class
                // naming scheme); fall back to everything the run produced.
                generatedCases = parsed.cases();
            }
            TestResult scoped = TestResult.from(generatedCases, parsed.duration(), reports, parsed.note());
            lastResult = new GeneratedTestExecution(tests, scoped, artifacts, scoped.describe());
            return lastResult;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot prepare generated tests", e);
        } finally {
            if (context.config().report().keepGeneratedTests()) {
                // report.keep_generated_tests=true: the sources stay in the project so that the
                // reproduction command in the report can be run as printed.
                return lastResult;
            }
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

    /** Finds the test source root to write generated tests into. */
    private static Path testSourceRoot(ProjectInfo project) {
        for (var module : project.modules()) {
            for (Path candidate : module.testSourceDirs()) {
                if (Files.isDirectory(candidate)) {
                    return candidate;
                }
            }
        }
        for (var module : project.modules()) {
            Path candidate = module.path().resolve("src/test/java");
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
        try (Stream<Path> walk = Files.walk(directory)) {
            List<Path> directories = walk.filter(Files::isDirectory).sorted(java.util.Comparator.reverseOrder())
                    .toList();
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
        return path.toString().replace('\\', '/').toLowerCase(Locale.ROOT)
                .contains(GENERATED_PACKAGE.replace('.', '/').toLowerCase(Locale.ROOT));
    }
}
