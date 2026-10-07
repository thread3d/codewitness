package dev.codewitness.go.mutation;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.MutantStatus;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.SurvivingMutant;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.go.GoProjectDetector;
import dev.codewitness.go.GoTool;
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
import java.util.Set;

/**
 * Mutation testing for Go using a bounded source mutator.
 *
 * <p>Go has no universally installed mutation tool, and a project should not lose mutation evidence
 * just because one is absent. The adapter therefore mutates the source text itself through
 * {@link TextMutator}, re-runs the project's own test suite with {@code go test} for each mutant, and
 * reports the mutants the suite could not detect as survivors.
 *
 * <p>The run is bounded three ways: a fixed operator set, a mutant cap and a wall-clock budget. Every
 * file is restored in a {@code finally} block, so a crash cannot leave a mutated source tree behind.
 * A mutant that fails to build is non-viable and is excluded from the score rather than counted as
 * killed: a compilation error is evidence about the mutant, not about the strength of the tests. The
 * same convention is used for the Java adapter's PIT results, so the mutation score means the same
 * thing in every language. Surviving mutants never change a verdict: they are prominent evidence and
 * a run limitation.
 */
public final class GoMutationTester implements VerificationTools.MutationTester {

    /** Upper bound on mutants per run, independent of the wall-clock budget. */
    private static final int MAX_MUTANTS = 16;

    /** Upper bound on source files considered, so a large module cannot dominate a run. */
    private static final int MAX_FILES = 20;

    /** Smallest budget a mutant may be given; a process cannot be started with a non-positive timeout. */
    private static final Duration MINIMUM_MUTANT_BUDGET = Duration.ofMillis(1);

    /** The mutator over Go's own lexis; the loop below is language independent. */
    private static final TextMutator MUTATOR = new TextMutator(GoLexis.INSTANCE);

    @Override
    public String id() {
        return "go-text-mutation";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.GO);
    }

    @Override
    public MutationReport analyse(VerificationContext context) {
        if (!GoTool.hasManifest(context)) {
            return MutationReport.notMeasured(id(), "no go.mod was found, so mutants cannot be compiled");
        }
        List<Path> targets = targetFiles(context);
        if (targets.isEmpty()) {
            return MutationReport.notMeasured(id(), "no Go source file was available to mutate");
        }
        context.emit("mutation", "mutating " + targets.size() + " Go source file(s) with the built-in mutator");

        Duration budget = context.config().mutation().timeout();
        Instant deadline = Instant.now().plus(budget);
        int generated = 0;
        int killed = 0;
        int survived = 0;
        int timedOut = 0;
        int nonViable = 0;
        List<SurvivingMutant> survivors = new ArrayList<>();

        for (Path file : targets) {
            if (generated >= MAX_MUTANTS || Instant.now().isAfter(deadline)) {
                break;
            }
            String original;
            try {
                original = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException _) {
                continue;
            }
            List<TextMutator.Site> sites = MUTATOR.sites(original);
            for (TextMutator.Site site : sites) {
                if (generated >= MAX_MUTANTS || Instant.now().isAfter(deadline)) {
                    break;
                }
                Duration remaining = Duration.between(Instant.now(), deadline);
                if (remaining.isNegative() || remaining.isZero()) {
                    break;
                }
                generated++;
                Outcome outcome = runMutant(context, file, original, site, remaining);
                switch (outcome.status()) {
                    case KILLED -> killed++;
                    case TIMED_OUT -> timedOut++;
                    case NON_VIABLE -> {
                        nonViable++;
                        context.emit("mutation", "mutant " + site.describe() + " at "
                                + relative(context.projectRoot(), file) + ":" + site.line()
                                + " did not build and is excluded: " + outcome.detail());
                    }
                    default -> {
                        survived++;
                        String relative = relative(context.projectRoot(), file);
                        String description = "the test suite did not detect " + site.describe()
                                + " at " + relative + ":" + site.line();
                        survivors.add(new SurvivingMutant(
                                String.format(Locale.ROOT, "MUT-%03d", generated),
                                MutantStatus.SURVIVED, site.describe(), relative, null, site.line(),
                                description,
                                null));
                    }
                }
            }
        }

        if (generated == 0) {
            return MutationReport.notMeasured(id(),
                    "no mutatable operator was found in the selected sources");
        }
        String note = "source-level mutation over " + targets.size() + " file(s); " + nonViable
                + " non-viable mutant(s) excluded. The operator set is deliberately small (arithmetic, "
                + "comparison boundaries, boolean operators and the predeclared boolean constants), and "
                + "only operators surrounded by whitespace are considered.";
        return new MutationReport(true, "go-text", note, generated, killed, survived, 0, timedOut,
                survivors, List.of());
    }

    /** What happened when one mutant was built and tested. */
    private enum Status { KILLED, SURVIVED, TIMED_OUT, NON_VIABLE }

    private record Outcome(Status status, String detail) {

        Outcome(Status status) {
            this(status, null);
        }
    }

    private Outcome runMutant(
            VerificationContext context, Path file, String original, TextMutator.Site site, Duration remaining) {
        Path working = file;
        Duration runBudget = remaining.compareTo(MINIMUM_MUTANT_BUDGET) < 0 ? MINIMUM_MUTANT_BUDGET : remaining;
        try {
            Files.writeString(working, TextMutator.apply(original, site), StandardCharsets.UTF_8);
            String go = GoTool.go(context.projectInfo(), context);
            ProcessResult result = context.runner().run(ProcessSpec.builder(
                            List.of(go, "test", "-count=1", "./..."), context.projectRoot())
                    .environment(GoTool.environment(context))
                    .timeout(runBudget)
                    .maxOutputBytes(1_000_000)
                    .build());
            if (result.timedOut()) {
                return new Outcome(Status.TIMED_OUT);
            }
            String output = result.combinedOutput();
            if (output.contains("[build failed]") || output.contains("[setup failed]") || output.contains("no Go files")) {
                // The mutant did not compile, or no package ran: it is not evidence about test strength.
                return new Outcome(Status.NON_VIABLE, GoTool.lastLines(output, 3));
            }
            return new Outcome(result.success() ? Status.SURVIVED : Status.KILLED);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write mutant for " + file, e);
        } finally {
            try {
                // Restore the exact original bytes even when the run failed, so a crash cannot leave a
                // mutated tree behind for the next phase to analyse.
                Files.writeString(working, original, StandardCharsets.UTF_8);
            } catch (IOException _) {
                // Restoring is best effort; the workspace is a disposable copy.
            }
        }
    }

    private static List<Path> targetFiles(VerificationContext context) {
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
        } else {
            // Go has one source tree: tests sit next to the code as *_test.go, so the same roots yield
            // both and test files must be filtered out explicitly.
            for (Path root : project.sourceDirs()) {
                for (Path file : GoProjectDetector.goFiles(root)) {
                    if (!isTestPath(relative(context.projectRoot(), file))) {
                        files.add(file);
                    }
                }
            }
        }
        List<Path> result = new ArrayList<>(files);
        if (result.size() > MAX_FILES) {
            result = result.subList(0, MAX_FILES);
        }
        return List.copyOf(result);
    }

    private static boolean isTestPath(String relative) {
        String normalized = relative.replace('\\', '/').toLowerCase(Locale.ROOT);
        String name = normalized.contains("/") ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
        return name.endsWith("_test.go")
                || normalized.equals("testdata")
                || normalized.startsWith("testdata/")
                || normalized.contains("/testdata/");
    }

    private static String relative(Path root, Path file) {
        return file.startsWith(root) ? root.relativize(file).toString() : file.getFileName().toString();
    }
}
