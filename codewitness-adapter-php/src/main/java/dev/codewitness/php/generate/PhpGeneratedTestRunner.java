package dev.codewitness.php.generate;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.GeneratedTestExecution;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.php.PhpInterpreter;
import dev.codewitness.php.PhpTestProvider;
import dev.codewitness.php.PhpTestReportParser;
import dev.codewitness.support.Text;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Executes generated scenario tests with PHPUnit and reads the structured counterexamples out of the
 * report.
 *
 * <p>The generated classes are written into the project's own test root, not into the artefact store, and
 * they stay there. That is the opposite of the Python adapter and it is deliberate: a Python test is a
 * module that can live anywhere importable, while a PHP test is a class that only runs if the project's
 * PHPUnit configuration scans its file and the project's autoloader can load it. Deleting the file after
 * the run would also make the counterexample's own replay command a lie, because that command is a real
 * {@code phpunit --filter} invocation over the project.
 *
 * <p>A copy of every generated class is kept in the artefact store, so the evidence records the file that
 * was executed. The report is written to the run directory rather than to the test phase's report path so
 * that a failure in a generated test can never be read as a failure of the project's own suite.
 *
 * <p>The result is always parsed from PHPUnit's own JUnit report. A report that never appeared, and a
 * report that carries no case for the generated classes, are both "not executed": a batch that reported
 * nothing must never be mistaken for a batch that passed.
 */
public final class PhpGeneratedTestRunner implements VerificationTools.GeneratedTestExecutor {

    /** Subdirectory of the artefact store holding the copy of every generated class. */
    static final String GENERATED_DIRECTORY = "generated-tests";

    @Override
    public String id() {
        return "php-generated-tests";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.PHP);
    }

    @Override
    public GeneratedTestExecution execute(VerificationContext context, List<GeneratedTest> tests) {
        if (tests.isEmpty()) {
            return GeneratedTestExecution.notExecuted(tests, "no tests to run");
        }
        ProjectInfo project = context.projectInfo();
        List<String> phpunit = PhpInterpreter.phpunit(project, context);
        if (phpunit.isEmpty()) {
            return GeneratedTestExecution.notExecuted(tests, "PHPUnit was not found: neither vendor/bin/phpunit "
                    + "(created by the build's composer install), nor the runner the probe recorded, nor "
                    + "phpunit.phar next to the interpreter, so a generated test could not be executed");
        }
        List<Path> testRoots = project.testSourceDirs();
        if (testRoots.isEmpty()) {
            return GeneratedTestExecution.notExecuted(tests, "the project declares no test directory, and a "
                    + "generated PHP class is only runnable from the tree the project's PHPUnit configuration "
                    + "scans");
        }
        Path testRoot = testRoots.getFirst();
        Path report = reportPath(context);
        List<Path> artifacts = new ArrayList<>();
        try {
            Files.createDirectories(testRoot);
            for (GeneratedTest test : tests) {
                Path file = testRoot.resolve(test.simpleName() + ".php");
                Files.writeString(file, test.sourceCode(), StandardCharsets.UTF_8);
                artifacts.add(context.artifacts().write(
                        GENERATED_DIRECTORY + "/" + test.simpleName() + ".php", test.sourceCode()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the generated test into " + testRoot, e);
        }
        PhpTestReportParser.deleteIfExists(report);
        try {
            Files.createDirectories(report.getParent());
        } catch (IOException e) {
            return GeneratedTestExecution.notExecuted(tests, "cannot prepare the JUnit report directory: " + e);
        }

        List<String> command = command(context, phpunit, report, filter(tests));
        context.emit("tests", "running " + tests.size() + " generated PHPUnit class(es) in " + testRoot.getFileName());
        ProcessResult result = context.runner().run(ProcessSpec.builder(command, context.projectRoot())
                .environment(PhpInterpreter.environment(project, context.projectRoot(),
                        context.artifacts().artifactsDirectory()))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(1_000_000)
                .build());

        if (result.timedOut()) {
            // The killed runner may still have written a report for the cases that finished before the kill,
            // so the timeout is checked before that report is looked for.
            String note = "PHPUnit was killed after the " + context.config().limits().timeout().toSeconds()
                    + "s timeout; the partial output is not a complete run of the generated tests: "
                    + Text.lastLines(result.combinedOutput(), 6);
            return new GeneratedTestExecution(tests, TestResult.notExecuted(note), artifacts, note);
        }
        if (!Files.isRegularFile(report)) {
            String note = "PHPUnit produced no JUnit report for the generated tests ("
                    + result.describeOutcome() + "); last output: " + Text.lastLines(result.combinedOutput(), 6);
            return new GeneratedTestExecution(tests, TestResult.notExecuted(note), artifacts, note);
        }
        TestResult parsed = PhpTestReportParser.parse(List.of(report), context.projectRoot(),
                "generated test run (" + result.describeOutcome() + ")");
        if (!parsed.executed() || parsed.cases().isEmpty()) {
            // A report that carries no case for the generated classes is not a pass: the classes either
            // never ran or were not collected by the filter. Report the batch as not executed so that
            // silence is never mistaken for a successful run.
            String note = "the PHPUnit report carried no case for the generated tests ("
                    + result.describeOutcome() + "); last output: " + Text.lastLines(result.combinedOutput(), 6);
            return new GeneratedTestExecution(tests, TestResult.notExecuted(note), artifacts, note);
        }
        return new GeneratedTestExecution(tests, parsed, artifacts, parsed.describe());
    }

    /**
     * {@inheritDoc}
     *
     * <p>The command is a real PHPUnit invocation a human can paste into a shell: {@code env} carries the
     * seed and the case index, because PHPUnit's {@code --filter} can select a class or a method but has
     * nowhere to put either value. The generated class is left in the project for exactly this reason.
     */
    @Override
    public List<String> reproductionCommand(
            VerificationContext context, List<GeneratedTest> tests, Long seed, Integer caseIndex) {
        if (tests.isEmpty()) {
            return List.of();
        }
        List<String> phpunit = PhpInterpreter.phpunit(context.projectInfo(), context);
        if (phpunit.isEmpty()) {
            return List.of();
        }
        List<String> command = new ArrayList<>();
        command.add("env");
        if (seed != null) {
            command.add("CODEWITNESS_SEED=" + seed);
        }
        if (caseIndex != null) {
            command.add("CODEWITNESS_CASE=" + caseIndex);
        }
        command.addAll(phpunit);
        command.add("--filter");
        command.add(filter(tests));
        command.add("--do-not-cache-result");
        command.add("--colors=never");
        Path configuration = configurationFile(context.projectRoot());
        if (configuration != null) {
            command.add("--configuration");
            command.add(configuration.toString());
        }
        return List.copyOf(command);
    }

    /**
     * @return the PHPUnit command for a batch, shaped like {@link PhpTestProvider}'s own command but
     *     writing a different report and selecting only the generated classes.
     *
     * <p>The filter is what keeps this run's failures the generated tests' own: without it a project test
     * that fails would appear in the same report, and a counterexample could be attributed to the wrong
     * class.
     */
    private static List<String> command(
            VerificationContext context, List<String> phpunit, Path report, String filter) {
        List<String> command = new ArrayList<>(phpunit);
        command.add("--filter");
        command.add(filter);
        command.add("--log-junit");
        command.add(report.toString());
        command.add("--do-not-cache-result");
        command.add("--colors=never");
        Path configuration = configurationFile(context.projectRoot());
        if (configuration != null) {
            command.add("--configuration");
            command.add(configuration.toString());
        } else {
            for (Path directory : context.projectInfo().testSourceDirs()) {
                command.add(directory.toString());
            }
        }
        return List.copyOf(command);
    }

    /** @return a PHPUnit filter selecting the batch's classes; PHPUnit treats it as a regular expression. */
    static String filter(List<GeneratedTest> tests) {
        List<String> names = tests.stream().map(GeneratedTest::simpleName).distinct().toList();
        return names.size() == 1 ? names.getFirst() : "(" + String.join("|", names) + ")";
    }

    /** @return the project's PHPUnit configuration file, or {@code null} when it has none. */
    private static Path configurationFile(Path projectRoot) {
        Path configuration = projectRoot.resolve("phpunit.xml");
        if (Files.isRegularFile(configuration)) {
            return configuration;
        }
        Path fallback = projectRoot.resolve("phpunit.xml.dist");
        return Files.isRegularFile(fallback) ? fallback : null;
    }

    /** @return where this batch's JUnit report is written, inside the run's artefact store. */
    static Path reportPath(VerificationContext context) {
        return context.artifacts().artifactsDirectory().resolve("php").resolve(GENERATED_DIRECTORY)
                .resolve("junit.xml");
    }
}
