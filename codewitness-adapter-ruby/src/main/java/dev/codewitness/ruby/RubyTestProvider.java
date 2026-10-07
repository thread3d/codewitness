package dev.codewitness.ruby;

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
 * Runs a Ruby project's existing tests with RSpec.
 *
 * <p>RSpec or nothing is the honest answer here, for the same reason PHPUnit is the answer in the PHP
 * adapter. Ruby does ship minitest as a default gem, but a project's suite is written against one runner,
 * and running a second, weaker one against files it was not written for produces results that describe
 * nothing. When no RSpec can be found the result is "the tests did not execute" with the reason - never a
 * zero-failure pass.
 *
 * <p>The runner is resolved when this method runs, not when the project was inspected, because RSpec is a
 * gem: {@code bundle install} is what puts it in the project's bundle, and the build phase runs between
 * inspection and testing. A provider that trusted the inspection-time probe would find no RSpec on any
 * project that had not already been built.
 *
 * <p>The result is always parsed from RSpec's own JSON report. A run that produced no report, a run killed
 * by the timeout, and a report that cannot be read are all reported as "tests did not execute" with the
 * tail of the runner's own output, never as a pass. A non-zero exit with a readable report is not a tool
 * failure: it is RSpec saying that examples failed, which the report says as well.
 */
public final class RubyTestProvider implements VerificationTools.TestRunner {

    @Override
    public String id() {
        return "rspec";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.RUBY);
    }

    @Override
    public TestResult runTests(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        if (RubyInterpreter.rspec(project, context).isEmpty()) {
            return TestResult.notExecuted("RSpec was not found: neither bin/rspec (a Bundler binstub the "
                    + "build's bundle install creates), nor bundle exec rspec (which a Gemfile always makes "
                    + "available through the interpreter's own Bundler), nor an RSpec below vendor/bundle or "
                    + "on PATH. This tree has no Gemfile and no RSpec was found, so there is nothing to run");
        }
        List<String> command = testCommand(context);
        if (command.isEmpty()) {
            return TestResult.notExecuted("no RSpec command could be built for this project");
        }
        Path report = reportPath(context);
        RubyTestReportParser.deleteIfExists(report);
        try {
            Files.createDirectories(report.getParent());
        } catch (IOException e) {
            return TestResult.notExecuted("cannot prepare the RSpec report directory: " + e);
        }
        context.emit("tests", "running RSpec for " + project.testSourceDirs().size() + " test directory(ies)");

        ProcessResult result = context.runner().run(ProcessSpec.builder(command, context.projectRoot())
                .environment(RubyInterpreter.environment(project, context.projectRoot(),
                        context.artifacts().artifactsDirectory()))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(1_000_000)
                .build());
        if (result.timedOut()) {
            // The killed runner may still have written a report for the examples that finished before the
            // kill, so the timeout is checked before that report is looked for or parsed.
            return TestResult.notExecuted("RSpec was killed after the "
                    + context.config().limits().timeout().toSeconds() + "s timeout; the partial output is "
                    + "not a complete test run: " + Text.lastLines(result.combinedOutput(), 6));
        }
        if (!Files.isRegularFile(report)) {
            return TestResult.notExecuted("RSpec produced no JSON report (" + result.describeOutcome()
                    + "); last output: " + Text.lastLines(result.combinedOutput(), 6));
        }
        return RubyTestReportParser.parse(List.of(report), context.projectRoot(),
                "parsed from the RSpec JSON report; " + result.describeOutcome());
    }

    /**
     * {@inheritDoc}
     *
     * <p>The report is asked for in RSpec's own JSON formatter and written into the run's artefact store,
     * because that is a report this engine reads rather than a file the project is meant to keep. A
     * project that configures its own formatters through {@code .rspec} is given another one on the
     * command line, which RSpec adds to rather than replaces, so an existing human-readable formatter
     * still runs and only the machine-readable file is new.
     *
     * <p>The paths to run are the detected test roots, unless the project has an {@code .rspec} file. That
     * file is RSpec's own configuration and is loaded automatically from the project root; it may set the
     * default path and the file pattern, so passing the detected roots as well would make two statements
     * about where the tests live and let the command line silently overrule the project.
     */
    @Override
    public List<String> testCommand(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        List<String> rspec = RubyInterpreter.rspec(project, context);
        if (rspec.isEmpty()) {
            return List.of();
        }
        List<String> command = new ArrayList<>(rspec);
        command.add("--format");
        command.add("json");
        command.add("--out");
        command.add(reportPath(context).toString());
        addSelection(context, command);
        return List.copyOf(command);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The replay pins one example by its full description with RSpec's own {@code --example} option,
     * so the command in a counterexample is one a human can paste into a shell and have it run that
     * example.
     *
     * <p>The full description is what this provider has: it is the test case's {@code name}, and RSpec
     * matches {@code --example} against exactly that. The example's other identifier, its
     * {@code id} such as {@code ./spec/ledger_spec.rb[1:1]}, is not carried by a
     * {@link TestCaseResult} at all - the record holds the suite, the name, the outcome and the failure -
     * so a replay command built from it would have to invent a field the model does not have. RSpec
     * accepts the description for the same purpose and it is already here, which is why it is used.
     */
    @Override
    public List<String> replayCommand(VerificationContext context, TestCaseResult testCase) {
        ProjectInfo project = context.projectInfo();
        List<String> rspec = RubyInterpreter.rspec(project, context);
        if (rspec.isEmpty()) {
            return List.of();
        }
        List<String> command = new ArrayList<>(rspec);
        command.add("--example");
        command.add(testCase.name());
        addSelection(context, command);
        return List.copyOf(command);
    }

    /**
     * Appends the paths the run should select, unless the project's own {@code .rspec} already says.
     *
     * <p>An empty directory list is not an error: RSpec without a path runs its default path, which is
     * {@code spec}, and a tree whose test directory the detector could not name is still a tree RSpec can
     * find for itself.
     */
    private static void addSelection(VerificationContext context, List<String> command) {
        if (Files.isRegularFile(context.projectRoot().resolve(".rspec"))) {
            return;
        }
        for (Path directory : context.projectInfo().testSourceDirs()) {
            command.add(directory.toString());
        }
    }

    /** @return where this run's JSON report is written, inside the run's artefact store. */
    static Path reportPath(VerificationContext context) {
        return context.artifacts().artifactsDirectory().resolve("ruby").resolve("rspec.json");
    }
}
