package dev.codewitness.ruby.mutation;

import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.ruby.RubyInterpreter;
import dev.codewitness.ruby.RubyProjectDetector;
import dev.codewitness.support.Text;
import dev.codewitness.support.mutation.CommandMutationProfile;
import dev.codewitness.support.mutation.CommandMutationTester;
import dev.codewitness.support.mutation.LanguageLexis;
import dev.codewitness.support.mutation.TextMutator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mutation testing for Ruby using the shared bounded source mutator.
 *
 * <p>A surviving mutant is the most direct evidence that a test suite does not constrain the behaviour
 * it appears to constrain, and it should not be unavailable just because a mutation framework is not
 * installed. Ruby's mutation frameworks are gems the project would have to add to its Gemfile, so this
 * tester uses {@link TextMutator}, the same text-level mutator the JavaScript, TypeScript, Rust, Go,
 * C#, Kotlin and C++ adapters use, with {@link RubyLexis} supplying the Ruby half. The operator set, the
 * mutant sites and the literal handling therefore live in exactly one place per language rather than in
 * a copy of the loop.
 *
 * <p>The loop, its bounds and its restore guarantee live once, in {@link CommandMutationTester}: at most
 * sixteen mutants over at most twenty files, inside the configured mutation budget, with every file
 * written back in a {@code finally} block. The workspace a run mutates is a disposable copy, but the
 * restore is what makes the guarantee true even so. This class supplies only the Ruby half through the
 * nested profile: the interpreter and the runner the build installed, the sources, the per-mutant
 * command and the lexis.
 *
 * <p>The shared loop tells four outcomes apart, and the difference between the last two is the whole
 * reason Ruby's profile has to describe its own failures:
 *
 * <ul>
 *   <li>{@code KILLED} - the mutant was written, RSpec ran the suite and an example failed;</li>
 *   <li>{@code SURVIVED} - RSpec ran the suite and every example passed;</li>
 *   <li>{@code TIMED_OUT} - the mutant's run was ended by the wall-clock budget. That is not a kill:
 *       the suite never reported an opinion, and a mutant that hangs the runner has not been detected
 *       by a test;</li>
 *   <li>{@code NON_VIABLE} - RSpec never produced a test result at all. A mutant the interpreter cannot
 *       parse, a Bundler that cannot load the project, or a run that discovered no example says
 *       something about the mutant or about the environment, never about the strength of the tests, so
 *       it is excluded from the score rather than counted as killed. Without that distinction every
 *       mutant Ruby refuses to parse would look like a mutant the suite detected.</li>
 * </ul>
 *
 * <p>A surviving mutant proves that the suite did not notice the change. It does not prove that the
 * changed behaviour is untested, because an equivalent mutant cannot be told apart by running tests:
 * {@code x.to_s} and {@code x.to_i} agree for the values a test happens to use, and {@code a * b} and
 * {@code a / b} agree wherever {@code b} is one. Survivors are therefore reported as prominent evidence
 * and as a run limitation, and they never change a verdict, exactly like the Java adapter's PIT results.
 * The reverse reading is wrong too: a killed mutant says a test observed the change, not that the test
 * was written to pin that behaviour down.
 */
public final class RubyMutationTester implements VerificationTools.MutationTester {

    /** The SPI id this tester publishes, the way the registry and the reports name it. */
    private static final String ID = "ruby-mutation";

    /** The tool id written on a report that was measured, so a reader can see which tester produced it. */
    private static final String REPORT_TOOL_ID = "ruby-text";

    /** How the report states the operator set, in the adapter's own words. */
    private static final String OPERATOR_SET_DESCRIPTION =
            "The operator set is deliberately small (arithmetic including exponentiation and modulo, "
                    + "comparison boundaries, equality and case equality, the regex match pair, boolean "
                    + "operators, the keyword operators and/or, the boolean constants, nil and the "
                    + "to_s/to_i conversion pair), and only operators surrounded by whitespace are "
                    + "considered.";

    /**
     * A missing interpreter or a missing runner is not a test result, so the run says so instead of
     * reporting a score over mutants nobody judged.
     */
    private static final List<String> SYNTAX_ERROR_MARKERS = List.of(
            "SyntaxError", "syntax error", "unexpected end-of-input", "unexpected token",
            "unterminated string");

    /** The same distinction for an environment that could not load the project at all. */
    private static final List<String> LOAD_ERROR_MARKERS = List.of(
            "An error occurred while loading", "LoadError", "cannot load such file", "Bundler::",
            "Could not find gem", "Could not locate Gemfile", "command not found",
            "is not part of the bundle");

    /** The shared loop; its lexical shape is Ruby's, not a C-family approximation. */
    private final CommandMutationTester delegate = new CommandMutationTester(new RubyMutationProfile());

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return delegate.supports(project);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The three ways this run can decline to measure are answered before any mutant is written: a
     * failed interpreter probe, a project with no test source directory, and a missing RSpec. Each is a
     * reason rather than a zero, because a generated count of zero over a suite that could not run is
     * indistinguishable in a report from a suite that killed nothing.
     */
    @Override
    public MutationReport analyse(VerificationContext context) {
        return delegate.analyse(context);
    }

    /**
     * @return why no mutant could be written, run and judged in this project, or {@code null} when the
     *     run may proceed.
     *
     * <p>The probe is checked first because it is the one failure that makes every later answer
     * meaningless: without an interpreter there is no parse of the mutant and no test run, so reporting
     * a score would be the exact failure the probe attribute exists to prevent. A project without test
     * sources is refused next, because the budget spent mutating it could only produce survivors. RSpec
     * is resolved last and at this moment rather than at inspection time, because RSpec is a gem: the
     * build's {@code bundle install} is what creates {@code bin/rspec}, and the build runs between
     * inspection and this phase.
     */
    static String manifestProblem(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        String probeFailure = project.attributes().get(RubyInterpreter.ATTR_PROBE_FAILURE);
        if (probeFailure != null) {
            return "the Ruby interpreter probe failed, so no mutant could be parsed or tested: "
                    + probeFailure;
        }
        if (!project.hasTests()) {
            return "the project has no Ruby test source directory, so no test could ever kill a mutant";
        }
        if (RubyInterpreter.rspec(project, context).isEmpty()) {
            return "RSpec was not found, so no mutant could be judged: neither <project>/bin/rspec (a "
                    + "Bundler binstub the build's bundle install creates), nor an RSpec the probe "
                    + "recorded, nor rspec on PATH, and no Gemfile for Bundler to run one from";
        }
        return null;
    }

    /**
     * @return the command that runs the project's own RSpec suite over one mutant: the runner
     *     {@link RubyInterpreter#rspec} resolved, the detected test roots, and the progress formatter.
     *
     * <p>The progress formatter is chosen because a mutant's output is read for one thing only - whether
     * the tests ran at all - and one character per example keeps a failing example's own message
     * readable. The JSON the test phase parses would cost a parse here and add nothing: the shared loop
     * decides from the exit status, and {@link #nonViableReason} decides from the runner's own words.
     *
     * <p>The test roots are read from the detector's report rather than assumed to be {@code spec/},
     * because the layout is the project's to choose, and a root that does not exist on disk is left out
     * rather than handed to RSpec as a path it would fail to find.
     */
    static List<String> testCommand(ProjectInfo project, VerificationContext context) {
        List<String> rspec = RubyInterpreter.rspec(project, context);
        if (rspec.isEmpty()) {
            return List.of();
        }
        List<String> command = new ArrayList<>(rspec);
        for (Path testRoot : project.testSourceDirs()) {
            if (Files.isDirectory(testRoot)) {
                command.add(relative(context.projectRoot(), testRoot));
            }
        }
        command.add("--format");
        command.add("progress");
        return List.copyOf(command);
    }

    /**
     * @param relative a project-relative path
     * @return {@code true} when the path holds tests rather than the code they test.
     *
     * <p>Mutating a test file would be mutating the oracle, which is not what a mutation score measures.
     * The naming convention answers this for a flat layout - RSpec's {@code _spec.rb} and minitest's
     * {@code _test.rb} - and the {@code spec} and {@code test} directory elements answer it for the
     * conventional one, which matters because the detector falls back to the project root as its source
     * root when it finds no {@code lib}, {@code app} or {@code src}.
     */
    static boolean isTestPath(String relative) {
        String normalized = relative.replace('\\', '/');
        String name = normalized.contains("/")
                ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
        if (name.endsWith("_spec.rb") || name.endsWith("_test.rb")) {
            return true;
        }
        for (String element : normalized.split("/")) {
            if (element.equals("spec") || element.equals("test")) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param output the combined output of one mutant's test run
     * @return the reason that output is not a test result, or {@code null} when RSpec ran the suite and
     *     the exit status decides the mutant.
     *
     * <p>Ruby has no compile step, so a mutant that does not parse fails at load time, in the middle of
     * the run, with a non-zero exit status - exactly like a real test failure. Without this check every
     * unparseable mutant would be reported as killed. A run that never got as far as loading the project,
     * and a run that discovered no example to execute, are non-viable for the same reason: neither says
     * anything about the strength of the suite.
     *
     * <p>The words are read from the runner's output, so a failing example whose own message happens to
     * contain one of them is read as an environment failure and excluded. That is the conservative
     * direction: it can lose a kill, while trusting the exit status alone would turn every mutant Ruby
     * refuses to parse into a kill the suite never made.
     */
    static String nonViableReason(String output) {
        if (output == null || output.isEmpty()) {
            return "RSpec produced no output at all, so no test result was observed for this mutant";
        }
        if (containsAny(output, SYNTAX_ERROR_MARKERS)) {
            return "the mutant did not parse, so no test ran against it: "
                    + diagnostic(output, SYNTAX_ERROR_MARKERS);
        }
        if (containsAny(output, LOAD_ERROR_MARKERS)) {
            return "the mutant's test run could not load the project, so nothing was tested: "
                    + diagnostic(output, LOAD_ERROR_MARKERS);
        }
        if (output.contains("0 examples")) {
            return "no example was discovered to run against the mutant, so nothing was observed about "
                    + "the suite: " + diagnostic(output, List.of("0 examples"));
        }
        return null;
    }

    private static boolean containsAny(String output, List<String> markers) {
        for (String marker : markers) {
            if (output.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the runner's own words for the failure: the line that carries the marker and the line
     *     after it, which is where RSpec puts the file and the message, or the tail of the output when
     *     the marker is not on a line of its own.
     */
    private static String diagnostic(String output, List<String> markers) {
        String[] lines = output.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            for (String marker : markers) {
                if (lines[i].contains(marker)) {
                    String first = lines[i].strip();
                    String next = i + 1 < lines.length ? lines[i + 1].strip() : "";
                    return next.isEmpty() ? first : first + " | " + next;
                }
            }
        }
        return Text.lastLines(output, 4);
    }

    private static String relative(Path root, Path file) {
        return file.startsWith(root) ? root.relativize(file).toString() : file.toString();
    }

    /** The Ruby half of the shared mutation loop: its runner, its sources, its command and its lexis. */
    private static final class RubyMutationProfile implements CommandMutationProfile {

        @Override
        public String languageName() {
            return "Ruby";
        }

        @Override
        public LanguageId languageId() {
            return LanguageId.RUBY;
        }

        @Override
        public String mutationTesterId() {
            return ID;
        }

        @Override
        public String mutationReportToolId() {
            return REPORT_TOOL_ID;
        }

        @Override
        public LanguageLexis lexis() {
            return RubyLexis.INSTANCE;
        }

        @Override
        public String manifestProblem(VerificationContext context) {
            return RubyMutationTester.manifestProblem(context);
        }

        @Override
        public List<Path> sourceFiles(Path sourceRoot) {
            return RubyProjectDetector.rubyFiles(sourceRoot);
        }

        @Override
        public boolean isTestPath(String relative) {
            return RubyMutationTester.isTestPath(relative);
        }

        @Override
        public List<String> testCommand(ProjectInfo project, VerificationContext context) {
            return RubyMutationTester.testCommand(project, context);
        }

        /**
         * {@inheritDoc}
         *
         * <p>The interpreter's own environment is the only channel through which Bundler can be told
         * where the build installed the gems, and through which a Ruby that ships Bundler as a default
         * gem is given the same configuration the build and the test phase ran with.
         */
        @Override
        public Map<String, String> environment(VerificationContext context) {
            return RubyInterpreter.environment(context.projectInfo(), context.projectRoot(),
                    context.artifacts().artifactsDirectory());
        }

        @Override
        public String nonViableReason(String output) {
            return RubyMutationTester.nonViableReason(output);
        }

        @Override
        public String operatorSetDescription() {
            return OPERATOR_SET_DESCRIPTION;
        }
    }
}
