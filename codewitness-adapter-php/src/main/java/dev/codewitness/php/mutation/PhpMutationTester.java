package dev.codewitness.php.mutation;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.MutantStatus;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.SurvivingMutant;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.php.PhpInterpreter;
import dev.codewitness.php.PhpProjectDetector;
import dev.codewitness.php.PhpScripts;
import dev.codewitness.php.PhpTestProvider;
import dev.codewitness.php.PhpTestReportParser;
import dev.codewitness.support.mutation.TextMutator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Mutation testing for PHP using the shared bounded source mutator.
 *
 * <p>A surviving mutant is the most direct evidence that a test suite does not constrain the behaviour
 * it appears to constrain, and it should not be unavailable just because a mutation framework is not
 * installed. PHP has no standard-library mutation tool and its ecosystem's (Infection) is another
 * Composer dependency, so this tester uses {@link TextMutator}, the same text-level mutator the
 * JavaScript, TypeScript, Rust, Go, C#, Kotlin and C++ adapters use, with {@link PhpLexis} supplying the
 * PHP half. The operator set, the mutant sites and the literal handling therefore live in exactly one
 * place per language rather than in a copy of the loop.
 *
 * <p>The loop is written here rather than delegated to
 * {@link dev.codewitness.support.mutation.TextMutationTester} because that tester is a Node tester in
 * everything but its name: it runs {@code node --test}, checks a mutant by loading it into Node, and
 * takes its sources, tests and environment from a Node-shaped profile. PHP's test runner is PHPUnit via
 * Composer and its parse check is the interpreter's own parser, so the two things this class must share
 * with the build and test phases are {@link PhpScripts#write} and {@link PhpTestProvider}. Its bounds and
 * its restore guarantee are the shared tester's: at most {@value #MAX_MUTANTS} mutants over at most
 * {@value #MAX_FILES} files, inside the configured mutation budget, with every file written back in a
 * {@code finally} block. The workspace a run mutates is a disposable copy, but the restore is what makes
 * the guarantee true even so.
 *
 * <p>A mutant the interpreter cannot parse is non-viable and is excluded from the score rather than
 * counted as killed: the check runs the same {@code php-lint.php} the build runs, so "does not parse" and
 * "was detected" are never confused. A mutant whose test run produced no JUnit report at all is
 * non-viable for the same reason - nothing was observed about test strength. Surviving mutants never
 * change a verdict; they are reported as prominent evidence and as a run limitation.
 */
public final class PhpMutationTester implements VerificationTools.MutationTester {

    /** Upper bound on mutants per run, independent of the wall-clock budget. */
    private static final int MAX_MUTANTS = 16;

    /** Upper bound on source files considered, so a large project cannot dominate a run. */
    private static final int MAX_FILES = 20;

    /** The mutator this tester reads sites with; its lexical shape is PHP's, not a C-family approximation. */
    private final TextMutator mutator = new TextMutator(PhpLexis.INSTANCE);

    @Override
    public String id() {
        return "php-text-mutation";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.PHP);
    }

    @Override
    public MutationReport analyse(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        String probeFailure = project.attributes().get(PhpInterpreter.ATTR_PROBE_FAILURE);
        if (probeFailure != null) {
            // Without an interpreter there is no parse check and no test run, so nothing can be judged.
            // Reporting a score here would be the exact failure the probe attribute exists to prevent.
            return MutationReport.notMeasured(id(),
                    "the PHP interpreter probe failed, so no mutant could be parsed or tested: " + probeFailure);
        }
        List<String> testCommand = new PhpTestProvider().testCommand(context);
        if (testCommand.isEmpty()) {
            return MutationReport.notMeasured(id(),
                    "PHPUnit was not found, so no mutant could be judged: neither vendor/bin/phpunit (created "
                            + "by the build's composer install), nor the runner the probe recorded, nor "
                            + "phpunit.phar next to the interpreter");
        }
        Path report = junitReport(testCommand);
        if (report == null) {
            return MutationReport.notMeasured(id(),
                    "the test command names no JUnit report, so a mutant's outcome could not be observed "
                            + "from the runner's own output");
        }
        List<Path> targets = targetFiles(context);
        if (targets.isEmpty()) {
            return MutationReport.notMeasured(id(), "no PHP source file was available to mutate");
        }
        Path lintScript = PhpScripts.write(context).lintScript();
        context.emit("mutation", "mutating " + targets.size() + " PHP source file(s) with the built-in mutator");

        Duration budget = context.config().mutation().timeout();
        Instant deadline = Instant.now().plus(budget);
        int generated = 0;
        int killed = 0;
        int survived = 0;
        int timedOut = 0;
        int nonViable = 0;
        List<SurvivingMutant> survivors = new ArrayList<>();

        for (Path file : targets) {
            if (generated >= MAX_MUTANTS || ended(deadline)) {
                break;
            }
            String original;
            try {
                original = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException _) {
                continue;
            }
            for (TextMutator.Site site : mutator.sites(original)) {
                if (generated >= MAX_MUTANTS || ended(deadline)) {
                    break;
                }
                Duration remaining = Duration.between(Instant.now(), deadline);
                if (remaining.isNegative() || remaining.isZero()) {
                    break;
                }
                generated++;
                switch (runMutant(context, file, original, site, testCommand, report, lintScript, remaining)) {
                    case KILLED -> killed++;
                    case TIMED_OUT -> timedOut++;
                    case NON_VIABLE -> nonViable++;
                    default -> {
                        survived++;
                        String relative = relative(context.projectRoot(), file);
                        survivors.add(new SurvivingMutant(
                                String.format(Locale.ROOT, "MUT-%03d", generated),
                                MutantStatus.SURVIVED, site.describe(), relative, null, site.line(),
                                "the test suite did not detect " + site.describe() + " at " + relative
                                        + ":" + site.line(),
                                null));
                    }
                }
            }
        }

        if (generated == 0) {
            return MutationReport.notMeasured(id(), "no mutatable operator was found in the selected sources");
        }
        String note = "source-level mutation over " + targets.size() + " PHP file(s) with the adapter's own "
                + "lexer; " + nonViable + " non-viable mutant(s) excluded. The operator set is deliberately "
                + "small (arithmetic, concatenation, comparison boundaries, equality including the strict "
                + "pair, boolean operators and boolean constants), and only operators surrounded by "
                + "whitespace are considered.";
        return new MutationReport(true, "php-text", note, generated, killed, survived, 0, timedOut,
                survivors, List.of());
    }

    /** What happened when one mutant was parsed and tested. */
    private enum Status { KILLED, SURVIVED, TIMED_OUT, NON_VIABLE }

    /**
     * Writes one mutant, runs the project's tests against it and restores the file.
     *
     * <p>The report is deleted before each run rather than after: PHPUnit appends nothing when it cannot
     * start, so a report left over from the previous mutant would be read as this one's outcome and every
     * later mutant would look identical to the first.
     */
    private Status runMutant(
            VerificationContext context, Path file, String original, TextMutator.Site site,
            List<String> testCommand, Path report, Path lintScript, Duration remaining) {
        try {
            Files.writeString(file, TextMutator.apply(original, site), StandardCharsets.UTF_8);
            PhpTestReportParser.deleteIfExists(report);
            ProcessResult result = context.runner().run(ProcessSpec.builder(testCommand, context.projectRoot())
                    .environment(environment(context))
                    .timeout(remaining)
                    .maxOutputBytes(1_000_000)
                    .build());
            if (result.timedOut()) {
                return Status.TIMED_OUT;
            }
            if (!Files.isRegularFile(report)) {
                // PHPUnit never got far enough to write a result. That says nothing about the test suite,
                // so it is not a kill; the lint check below is what decides whether the mutant was even
                // executable.
                return Status.NON_VIABLE;
            }
            TestResult tests = PhpTestReportParser.parse(List.of(report), context.projectRoot(),
                    "mutant run (" + result.describeOutcome() + ")");
            if (!tests.executed()) {
                return Status.NON_VIABLE;
            }
            if (!tests.anyFailure()) {
                return Status.SURVIVED;
            }
            // A failing run is only "killed" if the mutant was executable at all; a mutant the interpreter
            // cannot parse says nothing about test strength.
            return parses(context, file, lintScript, remaining) ? Status.KILLED : Status.NON_VIABLE;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write mutant for " + file, e);
        } finally {
            try {
                Files.writeString(file, original, StandardCharsets.UTF_8);
            } catch (IOException _) {
                // Restoring is best effort; the workspace is a disposable copy.
            }
        }
    }

    /**
     * @return {@code true} when the mutated file still parses under the interpreter's own parser.
     *
     * <p>This is the same helper program the build phase runs, so a mutant that is discarded as
     * non-viable is discarded for the reason the build would report, not by a second and differently
     * wrong parser.
     */
    private boolean parses(VerificationContext context, Path file, Path lintScript, Duration remaining) {
        ProcessResult result = context.runner().run(ProcessSpec.builder(
                        List.of(PhpInterpreter.php(context.projectInfo(), context), lintScript.toString(),
                                file.toString()), context.projectRoot())
                .environment(environment(context))
                .timeout(remaining)
                .maxOutputBytes(256_000)
                .build());
        return result.success();
    }

    private static Map<String, String> environment(VerificationContext context) {
        return PhpInterpreter.environment(context.projectInfo(), context.projectRoot(),
                context.artifacts().artifactsDirectory());
    }

    /**
     * @return the file the given test command writes its JUnit report to, or {@code null} when it names
     *     none.
     *
     * <p>The path is read out of the provider's own argv instead of being rebuilt here: the report the
     * tester parses is then, by construction, the report that command writes, and the two cannot drift
     * apart when the provider's layout changes.
     */
    static Path junitReport(List<String> testCommand) {
        for (int i = 0; i < testCommand.size() - 1; i++) {
            if ("--log-junit".equals(testCommand.get(i))) {
                return Path.of(testCommand.get(i + 1));
            }
        }
        return null;
    }

    /**
     * @return the source files a mutant is generated in: the configured targets when the run names any,
     *     otherwise the project's own sources, with test files left out and the list capped.
     *
     * <p>Mutating a test file would be mutating the oracle, which is not what a mutation score measures.
     */
    static List<Path> targetFiles(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        List<String> configured = context.config().mutation().targetClasses();
        Set<Path> files = new LinkedHashSet<>();
        if (configured != null && !configured.isEmpty()) {
            for (String target : configured) {
                Path candidate = context.projectRoot().resolve(target);
                if (Files.isRegularFile(candidate)) {
                    files.add(candidate);
                }
            }
            return List.copyOf(files);
        }
        for (Path root : project.sourceDirs()) {
            for (Path file : PhpProjectDetector.phpFiles(root)) {
                if (!isTestFile(project, file)) {
                    files.add(file);
                }
            }
        }
        List<Path> result = new ArrayList<>(files);
        if (result.size() > MAX_FILES) {
            result = result.subList(0, MAX_FILES);
        }
        return List.copyOf(result);
    }

    /**
     * @return {@code true} when the file is the project's own test rather than the code under test.
     *
     * <p>The detector's test roots answer this for a conventional layout, and the naming convention -
     * {@code *Test.php}, {@code Spec.php} - answers it for the flat layout where a project's root is both
     * its source root and its test root.
     */
    static boolean isTestFile(ProjectInfo project, Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        for (Path testRoot : project.testSourceDirs()) {
            if (testRoot != null && normalized.startsWith(testRoot.toAbsolutePath().normalize())) {
                return true;
            }
        }
        String name = file.getFileName().toString();
        return name.endsWith("Test.php") || name.endsWith("Spec.php") || name.endsWith("_test.php");
    }

    private static boolean ended(Instant deadline) {
        return Instant.now().isAfter(deadline);
    }

    private static String relative(Path root, Path file) {
        return file.startsWith(root) ? root.relativize(file).toString() : file.getFileName().toString();
    }
}
