package dev.codewitness.swift.mutation;

import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.support.Text;
import dev.codewitness.support.mutation.CommandMutationProfile;
import dev.codewitness.support.mutation.CommandMutationTester;
import dev.codewitness.support.mutation.LanguageLexis;
import dev.codewitness.support.mutation.TextMutator;
import dev.codewitness.swift.SwiftProjectDetector;
import dev.codewitness.swift.SwiftToolchain;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Mutation testing for Swift using the shared bounded source mutator.
 *
 * <p>A surviving mutant is the most direct evidence that a test suite does not constrain the behaviour
 * it appears to constrain, and it should not be unavailable just because a mutation framework is not
 * installed. Swift has no mutation tool that ships with the toolchain, and the third-party ones are
 * packages a project would have to add to its manifest, so this tester uses {@link TextMutator} - the
 * same text-level mutator the JavaScript, TypeScript, Rust, Go, Ruby, C#, Kotlin and C++ adapters use -
 * with {@link SwiftLexis} supplying the Swift half. The operator set, the mutant sites and the literal
 * handling therefore live in exactly one place per language rather than in a copy of the loop.
 *
 * <p>The loop, its bounds and its restore guarantee live once, in {@link CommandMutationTester}: at most
 * sixteen mutants over at most twenty files, inside the configured mutation budget, with every file
 * written back in a {@code finally} block. The workspace a run mutates is a disposable copy, but the
 * restore is what makes the guarantee true even so. This class supplies only the Swift half through the
 * nested profile: the toolchain the build resolved, the sources, the test target's naming and the
 * lexis.
 *
 * <p>Swift is compiled, so the command is the whole story: {@code swift test} builds the package and
 * runs its XCTest suite, and running the tests is also what compiles the mutant. SwiftPM refuses to run
 * a test target whose sources do not compile, so a syntactically or type-broken mutant fails the build
 * before a single case runs. That is why {@link #nonViableReason} exists and why it is conservative:
 * without it, every mutant the compiler rejects would exit non-zero and be counted as a kill the suite
 * never made.
 *
 * <p>A surviving mutant proves that the suite did not notice the change. It does not prove that the
 * changed behaviour is untested, because an equivalent mutant cannot be told apart by running tests:
 * {@code a * b} and {@code a / b} agree wherever {@code b} is one, and {@code ==} and {@code ===} agree
 * for the value types a test usually uses. Survivors are therefore reported as prominent evidence and as
 * a run limitation, and they never change a verdict, exactly like the Java adapter's PIT results.
 */
public final class SwiftMutationTester implements VerificationTools.MutationTester {

    /** The SPI id this tester publishes, the way the registry and the reports name it. */
    private static final String ID = "swift-mutation";

    /** The tool id written on a report that was measured, so a reader can see which tester produced it. */
    private static final String REPORT_TOOL_ID = "swift-text";

    /** How the report states the operator set, in the adapter's own words. */
    private static final String OPERATOR_SET_DESCRIPTION =
            "The operator set is deliberately small (arithmetic, comparison boundaries, equality and "
                    + "the identity pair, the boolean operators and the boolean constants), and only "
                    + "binary operators surrounded by whitespace are considered.";

    /** What swiftc puts on the line of a compile error, as its console output spells it. */
    private static final String[] BUILD_ERROR_MARKERS = {": error:", "error: "};

    /** The shared loop; its lexical shape is Swift's, not a C-family approximation. */
    private final CommandMutationTester delegate = new CommandMutationTester(new SwiftMutationProfile());

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
     * failed toolchain probe, a package with no test target, and a {@code swift} that cannot be
     * resolved. Each is a reason rather than a zero, because a generated count of zero over a suite that
     * could not run is indistinguishable in a report from a suite that killed nothing.
     */
    @Override
    public MutationReport analyse(VerificationContext context) {
        return delegate.analyse(context);
    }

    /**
     * @return why no mutant could be compiled and judged in this project, or {@code null} when the run
     *     may proceed.
     *
     * <p>The probe is checked first because it is the one failure that makes every later answer
     * meaningless: without a toolchain there is no compilation of the mutant and no test run, so
     * reporting a score would be the exact failure the probe attribute exists to prevent. A package with
     * no test target is refused next, because the budget spent mutating it could only produce survivors -
     * no test exists that could ever kill one. The executable is resolved last, from the attribute the
     * inspection recorded and then from the machine, because a run whose workspace differs from the
     * inspected tree may still find the same toolchain.
     */
    static String manifestProblem(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        String probeFailure = project.attributes().get(SwiftToolchain.ATTR_PROBE_FAILURE);
        if (probeFailure != null) {
            return "the Swift toolchain probe failed, so no mutant could be compiled or tested: "
                    + probeFailure;
        }
        if (!project.hasTests()) {
            return "the package declares no test target, so no test could ever kill a mutant";
        }
        if (swift(project, context) == null) {
            return "no swift executable was found, so no mutant could be compiled or tested: neither one "
                    + "recorded by inspection, nor SWIFT_EXECUTABLE, nor a toolchain in a conventional "
                    + "location, nor swift on PATH";
        }
        return null;
    }

    /**
     * @return the {@code swift} driver this run should drive: the one inspection resolved and recorded,
     *     or a fresh resolution against the workspace when the attribute is absent, or {@code null} when
     *     neither finds one.
     */
    static String swift(ProjectInfo project, VerificationContext context) {
        String recorded = project.attributes().get(SwiftToolchain.ATTR_SWIFT);
        if (recorded != null && !recorded.isBlank()) {
            return recorded;
        }
        return SwiftToolchain.resolve(context.projectRoot(), System.getenv());
    }

    /**
     * @return the command that builds one mutant and runs the package's own XCTest suite over it: the
     *     resolved {@code swift} and the {@code test} subcommand, with SwiftPM's caches redirected
     *     inside the run by {@link SwiftToolchain#command}.
     *
     * <p>The whole suite is run rather than a selected target, because SwiftPM decides which files
     * belong to a test target from the manifest and a file outside it would not be compiled. Running
     * the tests is also what compiles the mutant: SwiftPM refuses to run a test target whose sources do
     * not compile, so a broken mutant never reaches a case and is read as non-viable rather than killed.
     */
    static List<String> testCommand(ProjectInfo project, VerificationContext context) {
        String swift = swift(project, context);
        if (swift == null) {
            return List.of();
        }
        return SwiftToolchain.command(swift, context, List.of("test"));
    }

    /**
     * @param relative a project-relative path
     * @return {@code true} when the path holds tests rather than the code they test.
     *
     * <p>Mutating a test file would be mutating the oracle, which is not what a mutation score measures.
     * SwiftPM's conventions answer this: a test target lives under a {@code Tests} directory (or the
     * singular {@code Test}) and its files are conventionally named {@code SomethingTests.swift} or
     * {@code SomethingTest.swift}. Both spellings are recognized because the detector falls back to the
     * package root as its source root when a manifest declares its targets with explicit paths, which
     * would otherwise expose the test target's own files as mutable sources.
     */
    static boolean isTestPath(String relative) {
        String normalized = relative.replace('\\', '/');
        String name = normalized.contains("/")
                ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
        if (name.endsWith("Tests.swift") || name.endsWith("Test.swift")) {
            return true;
        }
        for (String element : normalized.split("/")) {
            if (element.equals("Tests") || element.equals("Test")) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param output the combined output of one mutant's test run
     * @return the reason that output is not a test result, or {@code null} when XCTest ran the suite
     *     and the exit status decides the mutant.
     *
     * <p>Swift compiles before it tests, so a mutant the compiler rejects fails the build and exits
     * non-zero exactly like a real test failure. Without this check every unbuildable mutant would be
     * reported as killed. The exit status is therefore trusted only once the output shows that cases
     * actually ran: a compiler error with no XCTest line at all names the build failure, and an output
     * with no {@code Test Case '} line is non-viable whatever else it says, because no case was observed
     * about the suite. Only the suite banner, {@code Test Suite 'All tests'}, is accepted as evidence
     * that a build failure was not total; it is never accepted as evidence that anything was tested.
     *
     * <p>The words are read from the toolchain's output, so a run whose test output happens to contain
     * {@code error: } is read as a build failure and excluded. That is the conservative direction: it can
     * lose a kill, while trusting the exit status alone would credit a compile failure as a kill the
     * suite never made. A run that built and then had a case fail contains its {@code Test Case '} lines
     * and is left to the exit status.
     */
    static String nonViableReason(String output) {
        if (output == null || output.isEmpty()) {
            return "swift test produced no output at all, so no test result was observed for this mutant";
        }
        boolean buildFailed = containsBuildError(output);
        if (buildFailed && !hasXCTestLine(output)) {
            return "the mutant did not build, so SwiftPM never ran the tests against it: "
                    + diagnostic(output);
        }
        if (!hasTestCaseLine(output)) {
            return "swift test reported no XCTest case at all, so nothing was observed about the suite: "
                    + Text.lastLines(output, 4);
        }
        return null;
    }

    /**
     * @return {@code true} when the output carries any line an XCTest run prints, the suite banner
     *     included. This is the weaker question, asked only to decide whether a build failure was total.
     */
    private static boolean hasXCTestLine(String output) {
        for (String line : output.split("\\R")) {
            String stripped = line.stripLeading();
            if (stripped.startsWith("Test Case '") || stripped.startsWith("Test Suite 'All tests'")) {
                return true;
            }
        }
        return false;
    }

    /** @return {@code true} when at least one case ran, which is what makes the exit status meaningful. */
    private static boolean hasTestCaseLine(String output) {
        for (String line : output.split("\\R")) {
            if (line.stripLeading().startsWith("Test Case '")) {
                return true;
            }
        }
        return false;
    }

    /** @return {@code true} when swiftc reported a compile error anywhere in the output. */
    private static boolean containsBuildError(String output) {
        for (String marker : BUILD_ERROR_MARKERS) {
            if (output.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the compiler's own words for the failure: the line that carries the error marker and the
     *     line after it, which is where swiftc puts the note or the offending source, or the tail of the
     *     output when the marker is not on a line of its own.
     */
    private static String diagnostic(String output) {
        String[] lines = output.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            for (String marker : BUILD_ERROR_MARKERS) {
                if (lines[i].contains(marker)) {
                    String first = lines[i].strip();
                    String next = i + 1 < lines.length ? lines[i + 1].strip() : "";
                    return next.isEmpty() ? first : first + " | " + next;
                }
            }
        }
        return Text.lastLines(output, 4);
    }

    /** The Swift half of the shared mutation loop: its toolchain, its sources, its command and its lexis. */
    private static final class SwiftMutationProfile implements CommandMutationProfile {

        @Override
        public String languageName() {
            return "Swift";
        }

        @Override
        public LanguageId languageId() {
            return LanguageId.SWIFT;
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
            return SwiftLexis.INSTANCE;
        }

        @Override
        public String manifestProblem(VerificationContext context) {
            return SwiftMutationTester.manifestProblem(context);
        }

        @Override
        public List<Path> sourceFiles(Path sourceRoot) {
            return SwiftProjectDetector.swiftFiles(sourceRoot);
        }

        @Override
        public boolean isTestPath(String relative) {
            return SwiftMutationTester.isTestPath(relative);
        }

        @Override
        public List<String> testCommand(ProjectInfo project, VerificationContext context) {
            return SwiftMutationTester.testCommand(project, context);
        }

        /**
         * {@inheritDoc}
         *
         * <p>The toolchain's own environment is the channel through which SwiftPM's caches are pointed
         * inside the run. It is the same environment the build and the ordinary test phase ran with, so a
         * mutant is compiled with the same module cache as the code around it.
         */
        @Override
        public Map<String, String> environment(VerificationContext context) {
            return SwiftToolchain.environment(context);
        }

        @Override
        public String nonViableReason(String output) {
            return SwiftMutationTester.nonViableReason(output);
        }

        @Override
        public String operatorSetDescription() {
            return OPERATOR_SET_DESCRIPTION;
        }
    }
}
