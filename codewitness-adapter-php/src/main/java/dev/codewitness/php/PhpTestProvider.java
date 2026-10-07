package dev.codewitness.php;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.support.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a PHP project's existing tests with PHPUnit.
 *
 * <p>PHPUnit or nothing is the honest answer here. PHP ships no test framework in its standard library
 * and Pest is PHPUnit underneath, so when no runner can be found the result is "the tests did not
 * execute" with the reason - never a zero-failure pass, and never a second, weaker runner invented to
 * fill the gap.
 *
 * <p>The runner is resolved when this method runs, not when the project was inspected, because PHPUnit
 * is installed by the build: {@code composer install} is what creates {@code vendor/bin/phpunit}, and
 * the build phase runs between inspection and testing. A provider that trusted the inspection-time
 * probe would find no PHPUnit on any project that had not already been built.
 *
 * <p>The result is always parsed from the raw report. A run that produced no report, a run that was
 * killed by the timeout, and a report that cannot be read are all reported as "tests did not execute"
 * with the tail of the runner's own output, never as a pass.
 */
public final class PhpTestProvider implements VerificationTools.TestRunner {

    @Override
    public String id() {
        return "php-tests";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.PHP);
    }

    @Override
    public TestResult runTests(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        if (PhpInterpreter.phpunit(project, context).isEmpty()) {
            return TestResult.notExecuted("PHPUnit was not found: neither vendor/bin/phpunit (created by the "
                    + "build's composer install), nor the runner the probe recorded, nor phpunit.phar next to "
                    + "the interpreter. PHP has no standard-library test framework, so there is nothing to run");
        }
        List<String> command = testCommand(context);
        if (command.isEmpty()) {
            return TestResult.notExecuted("no PHPUnit command could be built for this project");
        }
        Path report = reportPath(context);
        PhpTestReportParser.deleteIfExists(report);
        try {
            Files.createDirectories(report.getParent());
        } catch (IOException e) {
            return TestResult.notExecuted("cannot prepare the JUnit report directory: " + e);
        }
        context.emit("tests", "running PHPUnit for " + project.testSourceDirs().size() + " test directory(ies)");

        ProcessResult result = context.runner().run(ProcessSpec.builder(command, context.projectRoot())
                .environment(PhpInterpreter.environment(project, context.projectRoot(),
                        context.artifacts().artifactsDirectory()))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(1_000_000)
                .build());
        if (result.timedOut()) {
            // The killed runner may still have written a report for the tests that finished before the
            // kill, so the timeout is checked before that report is looked for or parsed.
            return TestResult.notExecuted("PHPUnit was killed after the "
                    + context.config().limits().timeout().toSeconds() + "s timeout; the partial output is "
                    + "not a complete test run: " + Text.lastLines(result.combinedOutput(), 6));
        }
        if (!Files.isRegularFile(report)) {
            return TestResult.notExecuted("PHPUnit produced no JUnit report (" + result.describeOutcome()
                    + "); last output: " + Text.lastLines(result.combinedOutput(), 6));
        }
        return PhpTestReportParser.parse(List.of(report), context.projectRoot(),
                "parsed from the PHPUnit JUnit XML report; " + result.describeOutcome());
    }

    /**
     * {@inheritDoc}
     *
     * <p>A project with a PHPUnit configuration file is run with it, because that file is what selects
     * the bootstrap and therefore what makes the test classes loadable at all; without one, the detected
     * test roots are passed as the paths to run, which is the only thing PHPUnit could use to find them.
     */
    @Override
    public List<String> testCommand(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        List<String> phpunit = PhpInterpreter.phpunit(project, context);
        if (phpunit.isEmpty()) {
            return List.of();
        }
        List<String> command = new ArrayList<>(phpunit);
        command.add("--log-junit");
        command.add(reportPath(context).toString());
        // A result cache would let a second run reuse the first one's outcome, which is exactly what a
        // verification run must not do; the colours are turned off so the tail of the output is readable.
        command.add("--do-not-cache-result");
        command.add("--colors=never");
        Path configuration = configurationFile(context.projectRoot());
        if (configuration != null) {
            command.add("--configuration");
            command.add(configuration.toString());
        } else {
            for (Path directory : project.testSourceDirs()) {
                command.add(directory.toString());
            }
        }
        return List.copyOf(command);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The replay runs one test by PHPUnit's own filter syntax, {@code Class::method}, so the command
     * in a counterexample is one a human can paste into a shell and have it run that test.
     */
    @Override
    public List<String> replayCommand(VerificationContext context, TestCaseResult testCase) {
        ProjectInfo project = context.projectInfo();
        List<String> phpunit = PhpInterpreter.phpunit(project, context);
        if (phpunit.isEmpty()) {
            return List.of();
        }
        List<String> command = new ArrayList<>(phpunit);
        command.add("--filter");
        command.add(testCase.suite() + "::" + testCase.name());
        command.add("--do-not-cache-result");
        command.add("--colors=never");
        Path configuration = configurationFile(context.projectRoot());
        if (configuration != null) {
            command.add("--configuration");
            command.add(configuration.toString());
        } else {
            for (Path directory : project.testSourceDirs()) {
                command.add(directory.toString());
            }
        }
        return List.copyOf(command);
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

    /** @return where this run's JUnit report is written, inside the run's artefact store. */
    static Path reportPath(VerificationContext context) {
        return context.artifacts().artifactsDirectory().resolve("php").resolve("tests").resolve("junit.xml");
    }
}
