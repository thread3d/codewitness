package dev.codewitness.swift.generate;

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
import dev.codewitness.support.Text;
import dev.codewitness.swift.SwiftTestReportParser;
import dev.codewitness.swift.SwiftToolchain;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executes generated Swift scenario classes with {@code swift test} and reads the structured
 * counterexamples out of its console report.
 *
 * <p>The generated classes are written into the project's own test tree, under its first test root's
 * {@code Generated} directory, and they stay there. That is the opposite of the Python adapter and it is
 * deliberate: SwiftPM compiles a source only when it lives inside a declared test target's directory, so a
 * class written anywhere else is a file no compiler reads, and deleting it after the run would make the
 * counterexample's own replay command a lie, because that command is a real
 * {@code swift test --filter ...} invocation over that file.
 *
 * <p>A copy of every generated class is kept in the artefact store, so the evidence records the source that
 * was executed. The captured console output and the parsed report are written beside the test provider's
 * own output rather than over it, so that a failure in a generated class can never be read as a failure of
 * the project's own suite.
 *
 * <p>Only the generated classes are selected, with a {@code --filter} per class. Without that selection
 * the project's own XCTest cases would land in the same output, and a counterexample could be attributed
 * to the wrong test - the same reason the Ruby runner filters its RSpec invocation to the files it wrote.
 *
 * <p>The result is always read from the console report. A run that reported nothing, a run killed by the
 * timeout, and a report that names no generated suite are all "not executed": a batch that reported nothing
 * must never be mistaken for a batch that passed. The last is worth stating, because XCTest names a case
 * {@code <test target module>.<class>} while the generated class is recorded under that same string, and
 * {@link #attributed} refuses the batch unless at least one parsed case names it - so a drift between the
 * generator's naming and the parser's reading is reported as a failure to execute rather than silently
 * scoring as "no case ran".
 */
public final class SwiftGeneratedTestRunner implements VerificationTools.GeneratedTestExecutor {

    /** Subdirectory of the artefact store holding the copy of every generated class. */
    static final String GENERATED_DIRECTORY = "generated-tests";

    /** The name of the console log this executor writes, below the run's {@code swift} directory. */
    static final String LOG_NAME = "generated-swift-test.log";

    @Override
    public String id() {
        return "xctest-generated";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.SWIFT);
    }

    @Override
    public GeneratedTestExecution execute(VerificationContext context, List<GeneratedTest> tests) {
        if (tests.isEmpty()) {
            return GeneratedTestExecution.notExecuted(tests, "no tests to run");
        }
        ProjectInfo project = context.projectInfo();
        String swift = swift(context);
        if (swift == null) {
            return GeneratedTestExecution.notExecuted(tests, missingToolchain(project));
        }
        List<Path> testRoots = project.testSourceDirs();
        if (testRoots.isEmpty()) {
            return GeneratedTestExecution.notExecuted(tests, "the project declares no test directory, and a "
                    + "generated XCTest class is only compiled when it lives inside a declared test target's "
                    + "directory, so SwiftPM would never build it");
        }
        Path testRoot = testRoots.getFirst();
        List<Path> artifacts = new ArrayList<>();
        try {
            for (GeneratedTest test : tests) {
                // The path comes from the generator rather than from a second copy of the naming rule, so a
                // class is written exactly where the name it was recorded under says it is.
                Path file = SwiftScenarioTestGenerator.fileFor(context, test.simpleName());
                Files.createDirectories(file.getParent());
                Files.writeString(file, test.sourceCode(), StandardCharsets.UTF_8);
                artifacts.add(context.artifacts().write(
                        "swift/" + GENERATED_DIRECTORY + "/" + test.simpleName() + ".swift",
                        test.sourceCode()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the generated test into " + testRoot, e);
        }

        List<String> command = command(context, swift, tests);
        context.emit("tests", "running " + tests.size() + " generated XCTest class(es) under " + testRoot);
        ProcessResult result = context.runner().run(ProcessSpec.builder(command, context.projectRoot())
                .environment(SwiftToolchain.environment(context))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(1_000_000)
                .build());

        // The console output is written before anything else is decided: it is the evidence of what
        // happened, including a timeout or a toolchain that refused to start.
        Path log = context.artifacts().write("swift/" + LOG_NAME, result.combinedOutput());
        if (result.timedOut()) {
            // The killed runner may still have reported cases that finished before the kill, but a partial
            // run is not a complete run of the generated tests.
            String note = "swift test was killed after the " + context.config().limits().timeout().toSeconds()
                    + "s timeout; the partial output is not a complete run of the generated tests: "
                    + Text.lastLines(result.combinedOutput(), 6);
            return new GeneratedTestExecution(tests, TestResult.notExecuted(note), artifacts, note);
        }

        String captured = readLog(log);
        // The suite is normalised before anything reads it. Apple's XCTest names a case
        // `Module.Class`, while swift-corelibs-xctest on Linux names it `Class` alone (its console uses
        // `String(describing:)`, which drops the module, even though its filter matching uses
        // `String(reflecting:)`, which keeps it). The engine attributes a result by exact match on a
        // generated test's recorded name, so a Linux run would report every generated class under a name
        // nothing matches and leave every scenario UNVERIFIED. Rewriting the suite to the name the batch
        // recorded is the adapter's job, because only the adapter knows both spellings are one class.
        TestResult parsed = normalizeSuites(
                SwiftTestReportParser.parse(captured, context.projectRoot(),
                        "generated test run (" + result.describeOutcome() + ")"),
                tests);
        if (!parsed.executed() || parsed.cases().isEmpty()) {
            // A report that carries no case for the generated classes is not a pass: the classes either
            // never ran or were not compiled.
            String note = "the XCTest run carried no case for the generated tests ("
                    + result.describeOutcome() + "); last output: " + Text.lastLines(captured, 6);
            return new GeneratedTestExecution(tests, TestResult.notExecuted(note), artifacts, note);
        }
        if (!attributed(parsed, tests)) {
            String note = "the XCTest run named no generated suite: it reported " + suites(parsed)
                    + " while the generated classes are named " + names(tests)
                    + ", so no result could be attributed to the test that produced it";
            return new GeneratedTestExecution(tests, TestResult.notExecuted(note), artifacts, note);
        }
        return new GeneratedTestExecution(tests, parsed, artifacts, parsed.describe());
    }

    /**
     * @param parsed the result read from the runner's console
     * @param tests  the batch whose recorded names are the canonical ones
     * @return the same result with every case's suite rewritten to the generated class it belongs to
     *
     * <p>A case is matched to a generated class by the class's simple name, because that is the one part
     * both of XCTest's console dialects spell identically. A case that belongs to none of the batch's
     * classes - the project's own suite, if the filter ever let one through - is left exactly as the
     * runner reported it.
     */
    static TestResult normalizeSuites(TestResult parsed, List<GeneratedTest> tests) {
        Map<String, String> canonicalBySimpleName = new LinkedHashMap<>();
        for (GeneratedTest test : tests) {
            canonicalBySimpleName.put(test.simpleName(), test.className());
        }
        List<TestCaseResult> cases = new ArrayList<>();
        for (TestCaseResult testCase : parsed.cases()) {
            String simple = simpleName(testCase.suite());
            String canonical = canonicalBySimpleName.get(simple);
            if (canonical == null || canonical.equals(testCase.suite())) {
                cases.add(testCase);
                continue;
            }
            cases.add(new TestCaseResult(canonical, testCase.name(), testCase.outcome(),
                    testCase.duration(), testCase.failureType(), testCase.failureMessage(),
                    testCase.stackTrace()));
        }
        return TestResult.from(cases, parsed.duration(), parsed.reports(), parsed.note());
    }

    /** @return the class name without its module prefix, in either dialect's spelling. */
    static String simpleName(String suite) {
        if (suite == null) {
            return "";
        }
        String text = suite.strip();
        int dot = text.lastIndexOf('.');
        return dot < 0 ? text : text.substring(dot + 1);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The command re-runs exactly the generated class the batch wrote, which is a real {@code swift test}
     * invocation a human can paste into a shell. The seed and the case index are carried in the environment
     * as {@code CODEWITNESS_SEED} and {@code CODEWITNESS_CASE}, which is the same channel the Ruby and Rust
     * adapters use and which the generated class reads for itself: {@code swift test} has no seed option
     * that pins a search case, because the search is the generated file's own loop.
     *
     * <p>The filter is {@code <suite>/<method>} where the suite is the class name XCTest reports
     * ({@code <test target module>.<class>}) and the method is the one the generator recorded. The suite is
     * used rather than the bare simple name so the filter names the test target's module as well, and the
     * method is used rather than the class alone so a replay runs one case rather than every case in the
     * class.
     *
     * <p>The common SwiftPM options come from {@link SwiftToolchain#command}, so the environment redirection
     * of the command a counterexample prints is identical to the one the executor ran.
     */
    @Override
    public List<String> reproductionCommand(
            VerificationContext context, List<GeneratedTest> tests, Long seed, Integer caseIndex) {
        if (tests.isEmpty()) {
            return List.of();
        }
        String swift = swift(context);
        if (swift == null || context.projectInfo().testSourceDirs().isEmpty()) {
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
        command.addAll(SwiftToolchain.command(swift, context, List.of("test")));
        for (GeneratedTest test : tests) {
            command.add("--filter");
            // The simple class name, not the module-qualified one: on Linux the listed specifier is built
            // from the class's reflected name, which does not carry the module, and SwiftPM matches this
            // filter against that specifier as a regular expression. The simple name is a substring of
            // either spelling, so one command replays the case on both platforms.
            command.add(test.testMethod() == null
                    ? test.simpleName()
                    : test.simpleName() + "/" + test.testMethod());
        }
        return List.copyOf(command);
    }

    /**
     * @return the {@code swift test} command for a batch, selecting only the classes this batch wrote.
     *
     * <p>One {@code --filter} per class, each naming the simple class name: SwiftPM matches the filter
     * against the test's full identifier, so a class name selects every case XCTest discovered in that
     * class and nothing from the project's own suite. The options every SwiftPM invocation carries come
     * from {@link SwiftToolchain#command}, so this command and the replay command cannot disagree about
     * where the run's caches live.
     */
    static List<String> command(VerificationContext context, String swift, List<GeneratedTest> tests) {
        List<String> command = new ArrayList<>(SwiftToolchain.command(swift, context, List.of("test")));
        for (GeneratedTest test : tests) {
            command.add("--filter");
            command.add(test.simpleName());
        }
        return List.copyOf(command);
    }

    /**
     * @return the {@code swift} executable for this run: the one the adapter resolved and recorded, or a
     *     fresh lookup when the project carries no attribute - a context built without the adapter, or a
     *     run whose inspection could not resolve a toolchain.
     */
    static String swift(VerificationContext context) {
        String recorded = context.projectInfo().attributes().get(SwiftToolchain.ATTR_SWIFT);
        if (recorded != null && !recorded.isBlank()) {
            return recorded.strip();
        }
        return SwiftToolchain.resolve(context.projectRoot(), System.getenv());
    }

    /** @return the reason a batch could not run because no Swift toolchain was found, including the probe's. */
    private static String missingToolchain(ProjectInfo project) {
        String probeFailure = project.attributes().get(SwiftToolchain.ATTR_PROBE_FAILURE);
        String reason = probeFailure == null || probeFailure.isBlank() ? "" : " (" + probeFailure + ")";
        return "the Swift toolchain was not found: no swift executable is recorded for this project and none "
                + "was found on PATH, in SWIFT_EXECUTABLE, or in a conventional toolchain location, so a "
                + "generated XCTest class could not be executed" + reason;
    }

    /**
     * @return the text of the console log this executor just wrote.
     *
     * <p>Read back from the artefact rather than taken from the in-memory result, so the parser reads
     * exactly the bytes the evidence keeps. The two are the same by construction; reading the file is what
     * makes that a fact rather than an assumption.
     */
    private static String readLog(Path log) {
        try {
            return Files.readString(log, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the generated test log " + log, e);
        }
    }

    /**
     * @return {@code true} when at least one parsed case names a generated test.
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
            names.add(test.simpleName());
        }
        for (TestCaseResult testCase : parsed.cases()) {
            // Either the canonical module-qualified name or the simple one counts: a suite that has
            // already been normalised matches exactly, and a raw Linux report matches by simple name.
            if (names.contains(testCase.suite()) || names.contains(simpleName(testCase.suite()))) {
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
}
