package dev.codewitness.ruby.generate;

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
import dev.codewitness.ruby.RubyInterpreter;
import dev.codewitness.ruby.RubyTestReportParser;
import dev.codewitness.support.Text;

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
 * Executes generated scenario specs with RSpec and reads the structured counterexamples out of its report.
 *
 * <p>The generated specs are written into the project's own test tree, under its first test root, and they
 * stay there. That is the opposite of the Python adapter and it is deliberate: a Python test is a module
 * that can live anywhere importable, while an RSpec spec is only collected because it sits in the tree
 * RSpec scans and only loads because {@code require 'spec_helper'} resolves against the project's own
 * {@code spec/} directory. Deleting the file after the run would also make the counterexample's own replay
 * command a lie, because that command is a real {@code rspec} invocation over that file.
 *
 * <p>A copy of every generated spec is kept in the artefact store, so the evidence records the file that
 * was executed. The report is written beside the test provider's own report rather than over it, so that a
 * failure in a generated spec can never be read as a failure of the project's own suite.
 *
 * <p>Only the generated files are passed to RSpec. Without that selection the project's own examples would
 * land in the same report, and a counterexample could be attributed to the wrong test - the same reason the
 * PHP runner filters its PHPUnit invocation to the classes it wrote.
 *
 * <p>The result is always parsed from RSpec's own JSON report. A report that never appeared, and a report
 * that names no generated test, are both "not executed": a batch that reported nothing must never be
 * mistaken for a batch that passed. The second of those is worth stating, because unlike PHPUnit - which
 * reports the class a case belongs to - RSpec reports the file it came from, so a generated test's identity
 * in the report is its path. {@link #attributed} checks that the two names agree before any result is
 * handed to the engine, and refuses the batch with the names it saw when they do not.
 */
public final class RubyGeneratedTestRunner implements VerificationTools.GeneratedTestExecutor {

    /** Subdirectory of the artefact store holding the copy of every generated spec. */
    static final String GENERATED_DIRECTORY = "generated-tests";

    /** The name of the JSON report this executor asks RSpec for, below the run's {@code ruby} directory. */
    static final String REPORT_NAME = "generated-rspec.json";

    @Override
    public String id() {
        return "rspec-generated";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.RUBY);
    }

    @Override
    public GeneratedTestExecution execute(VerificationContext context, List<GeneratedTest> tests) {
        if (tests.isEmpty()) {
            return GeneratedTestExecution.notExecuted(tests, "no tests to run");
        }
        ProjectInfo project = context.projectInfo();
        List<String> rspec = RubyInterpreter.rspec(project, context);
        if (rspec.isEmpty()) {
            return GeneratedTestExecution.notExecuted(tests, "RSpec was not found: neither bin/rspec (a "
                    + "Bundler binstub the build's bundle install creates), nor bundle exec rspec (which a "
                    + "Gemfile always makes available through the interpreter's own Bundler), nor an RSpec "
                    + "below vendor/bundle or on PATH, so a generated spec could not be executed");
        }
        List<Path> testRoots = project.testSourceDirs();
        if (testRoots.isEmpty()) {
            return GeneratedTestExecution.notExecuted(tests, "the project declares no test directory, and a "
                    + "generated RSpec spec is only runnable from the tree RSpec's own file pattern scans");
        }
        Path testRoot = testRoots.getFirst();
        Path report = reportPath(context);
        List<Path> artifacts = new ArrayList<>();
        try {
            for (GeneratedTest test : tests) {
                // The path comes from the generator rather than from a second copy of the naming rule, so a
                // spec is written exactly where the name it was recorded under says it is.
                Path file = RubyScenarioTestGenerator.specFile(context, test.simpleName());
                Files.createDirectories(file.getParent());
                Files.writeString(file, test.sourceCode(), StandardCharsets.UTF_8);
                artifacts.add(context.artifacts().write(
                        "ruby/" + GENERATED_DIRECTORY + "/" + test.simpleName() + ".rb", test.sourceCode()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the generated spec into " + testRoot, e);
        }
        RubyTestReportParser.deleteIfExists(report);
        try {
            Files.createDirectories(report.getParent());
        } catch (IOException e) {
            return GeneratedTestExecution.notExecuted(tests, "cannot prepare the RSpec report directory: " + e);
        }

        List<String> command = command(context, rspec, report, tests);
        context.emit("tests", "running " + tests.size() + " generated RSpec spec file(s) under " + testRoot);
        ProcessResult result = context.runner().run(ProcessSpec.builder(command, context.projectRoot())
                .environment(RubyInterpreter.environment(project, context.projectRoot(),
                        context.artifacts().artifactsDirectory()))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(1_000_000)
                .build());

        if (result.timedOut()) {
            // The killed runner may still have written a report for the examples that finished before the
            // kill, so the timeout is checked before that report is looked for.
            String note = "RSpec was killed after the " + context.config().limits().timeout().toSeconds()
                    + "s timeout; the partial output is not a complete run of the generated tests: "
                    + Text.lastLines(result.combinedOutput(), 6);
            return new GeneratedTestExecution(tests, TestResult.notExecuted(note), artifacts, note);
        }
        if (!Files.isRegularFile(report)) {
            String note = "RSpec produced no JSON report for the generated tests ("
                    + result.describeOutcome() + "); last output: " + Text.lastLines(result.combinedOutput(), 6);
            return new GeneratedTestExecution(tests, TestResult.notExecuted(note), artifacts, note);
        }
        TestResult parsed = RubyTestReportParser.parse(List.of(report), context.projectRoot(),
                "generated test run (" + result.describeOutcome() + ")");
        if (!parsed.executed() || parsed.cases().isEmpty()) {
            // A report that carries no example for the generated specs is not a pass: the files either never
            // ran or were not collected. Report the batch as not executed so that silence is never mistaken
            // for a successful run.
            String note = "the RSpec report carried no example for the generated tests ("
                    + result.describeOutcome() + "); last output: " + Text.lastLines(result.combinedOutput(), 6);
            return new GeneratedTestExecution(tests, TestResult.notExecuted(note), artifacts, note);
        }
        if (!attributed(parsed, tests)) {
            String note = "the RSpec report named no generated test: it reported "
                    + suites(parsed) + " while the generated specs are named "
                    + names(tests) + ", so no result could be attributed to the test that produced it";
            return new GeneratedTestExecution(tests, TestResult.notExecuted(note), artifacts, note);
        }
        return new GeneratedTestExecution(tests, parsed, artifacts, parsed.describe());
    }

    /**
     * {@inheritDoc}
     *
     * <p>The command re-runs exactly the generated specs the batch wrote, which is a real {@code rspec}
     * invocation a human can paste into a shell.
     *
     * <p>The seed and the case index are carried in the environment as {@code CODEWITNESS_SEED} and
     * {@code CODEWITNESS_CASE}, which is the same channel the PHP adapter uses and which the generated
     * spec reads for itself: RSpec has no seed of its own to pin - {@code --seed} orders RSpec's own
     * example sequence and would not select the case a counterexample names - and there is no option that
     * pins a search case, because the search is the generated file's own loop. What the pinned case
     * therefore means is precise and is stated rather than implied: the seeded search runs only that case,
     * while a boundary case is already selected by the example the replay runs, and the variables are
     * simply unused there.
     *
     * <p>RSpec's {@code --example} is deliberately not used to pin the case. It selects by description, and
     * all the engine hands over is an index: mapping an index back onto a description here would mean
     * guessing whether the case came from the boundary list or from the search, and a guess that matched no
     * example would be a command that cannot work.
     */
    @Override
    public List<String> reproductionCommand(
            VerificationContext context, List<GeneratedTest> tests, Long seed, Integer caseIndex) {
        if (tests.isEmpty()) {
            return List.of();
        }
        List<String> rspec = RubyInterpreter.rspec(context.projectInfo(), context);
        if (rspec.isEmpty() || context.projectInfo().testSourceDirs().isEmpty()) {
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
        command.addAll(rspec);
        for (GeneratedTest test : tests) {
            command.add(RubyScenarioTestGenerator.specFile(context, test.simpleName()).toString());
        }
        return List.copyOf(command);
    }

    /**
     * @return the RSpec command for a batch, shaped like the test provider's own command but writing a
     *     different report and selecting only the files this batch wrote.
     *
     * <p>The formatter is named on the command line and the paths are the generated specs themselves, so
     * the invocation says nothing about the project's own tests. A project that configures its own
     * formatters through {@code .rspec} still gets them: RSpec adds a command-line formatter rather than
     * replacing one, so only the machine-readable file is new.
     */
    private static List<String> command(
            VerificationContext context, List<String> rspec, Path report, List<GeneratedTest> tests) {
        List<String> command = new ArrayList<>(rspec);
        command.add("--format");
        command.add("json");
        command.add("--out");
        command.add(report.toString());
        for (GeneratedTest test : tests) {
            command.add(RubyScenarioTestGenerator.specFile(context, test.simpleName()).toString());
        }
        return List.copyOf(command);
    }

    /**
     * @return {@code true} when at least one example in the report names a generated test.
     *
     * <p>The engine attributes a result by matching the suite a report carries against the generated test's
     * recorded name, so a report whose names are all different is a report nothing can be learned from -
     * and it would otherwise be scored obligation by obligation as "the runner reported no case for this
     * test", which reads like a runner that skipped the work rather than a name that did not match.
     */
    static boolean attributed(TestResult parsed, List<GeneratedTest> tests) {
        Set<String> names = new LinkedHashSet<>();
        for (GeneratedTest test : tests) {
            names.add(test.className());
        }
        for (TestCaseResult testCase : parsed.cases()) {
            if (names.contains(testCase.suite())) {
                return true;
            }
        }
        return false;
    }

    /** @return the distinct suites a report named, for the note that explains a mismatch. */
    private static String suites(TestResult parsed) {
        Set<String> suites = new LinkedHashSet<>();
        for (TestCaseResult testCase : parsed.cases()) {
            suites.add(testCase.suite());
        }
        return String.join(", ", suites);
    }

    /** @return the distinct names the batch's generated tests are recorded under. */
    private static String names(List<GeneratedTest> tests) {
        Set<String> names = new LinkedHashSet<>();
        for (GeneratedTest test : tests) {
            names.add(test.className());
        }
        return String.join(", ", names);
    }

    /** @return where this batch's RSpec report is written, inside the run's artefact store. */
    static Path reportPath(VerificationContext context) {
        return context.artifacts().artifactsDirectory().resolve("ruby").resolve(REPORT_NAME);
    }
}
