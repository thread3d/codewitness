package dev.codewitness.support.mutation;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.MutantStatus;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.SurvivingMutant;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

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
 * Mutation testing by running the project's own build and test command over one mutant at a time.
 *
 * <p>A mutation framework is an optional dependency, and a project should not lose mutation evidence
 * just because it is not installed. An adapter therefore mutates the source text itself through
 * {@link TextMutator}, runs what the project itself runs for each mutant, and reports the mutants the
 * suite could not detect as survivors.
 *
 * <p>The run is bounded three ways: a fixed operator set, a mutant cap and a wall-clock budget. Every
 * file is restored in a {@code finally} block, so a crash cannot leave a mutated source tree behind. A
 * mutant whose build fails is non-viable and is excluded from the score rather than counted as killed,
 * because a compilation error is evidence about the mutant rather than about the strength of the tests.
 * Surviving mutants never change a verdict: they are prominent evidence and a run limitation, exactly
 * like the Java adapter's PIT results.
 *
 * <p>The loop, its bounds and its restore guarantee live once here; the command, the manifest check and
 * the language's own notion of a non-viable mutant arrive through a {@link CommandMutationProfile}.
 */
public final class CommandMutationTester implements VerificationTools.MutationTester {

    /** Upper bound on mutants per run, independent of the wall-clock budget. */
    private static final int MAX_MUTANTS = 16;

    /** Upper bound on source files considered, so a large project cannot dominate a run. */
    private static final int MAX_FILES = 20;

    /** Smallest budget a mutant may be given; a process cannot be started with a non-positive timeout. */
    private static final Duration MINIMUM_MUTANT_BUDGET = Duration.ofMillis(1);

    /** What the language adapter knows and this tester does not. */
    private final CommandMutationProfile profile;

    /** The mutator for the profile's language; its lexical shape decides what is a site at all. */
    private final TextMutator mutator;

    /**
     * @param profile the language-specific details this tester needs; the adapter supplies them
     */
    public CommandMutationTester(CommandMutationProfile profile) {
        this.profile = profile;
        this.mutator = new TextMutator(profile.lexis());
    }

    @Override
    public String id() {
        return profile.mutationTesterId();
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(profile.languageId());
    }

    @Override
    public MutationReport analyse(VerificationContext context) {
        String manifestProblem = profile.manifestProblem(context);
        if (manifestProblem != null) {
            return MutationReport.notMeasured(id(), manifestProblem);
        }
        List<Path> targets = targetFiles(context);
        if (targets.isEmpty()) {
            return MutationReport.notMeasured(id(),
                    "no " + profile.languageName() + " source file was available to mutate");
        }
        context.emit("mutation", "mutating " + targets.size() + " " + profile.languageName()
                + " source file(s) with the built-in mutator");

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
            List<TextMutator.Site> sites = mutator.sites(original);
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
                + " non-viable mutant(s) excluded. " + profile.operatorSetDescription();
        return new MutationReport(true, profile.mutationReportToolId(), note, generated, killed, survived, 0,
                timedOut, survivors, List.of());
    }

    /** What happened when one mutant was built and tested. */
    private enum Status { KILLED, SURVIVED, TIMED_OUT, NON_VIABLE }

    private record Outcome(Status status, String detail) {
    }

    private Outcome runMutant(
            VerificationContext context, Path file, String original, TextMutator.Site site, Duration remaining) {
        // A run may be handed a budget so small it rounds to a zero-length timeout, which no process can
        // be started with; give the mutant the smallest budget that can run at all.
        Duration runBudget = remaining.compareTo(MINIMUM_MUTANT_BUDGET) < 0 ? MINIMUM_MUTANT_BUDGET : remaining;
        try {
            Files.writeString(file, TextMutator.apply(original, site), StandardCharsets.UTF_8);
            ProcessResult result = context.runner().run(ProcessSpec.builder(
                            profile.testCommand(context.projectInfo(), context), context.projectRoot())
                    .environment(profile.environment(context))
                    .timeout(runBudget)
                    .maxOutputBytes(1_000_000)
                    .build());
            if (result.timedOut()) {
                return new Outcome(Status.TIMED_OUT, null);
            }
            String output = result.combinedOutput();
            String nonViable = profile.nonViableReason(output);
            if (nonViable != null) {
                // The mutant did not build, or nothing ran: it is not evidence about test strength.
                return new Outcome(Status.NON_VIABLE, nonViable);
            }
            return new Outcome(result.success() ? Status.SURVIVED : Status.KILLED, null);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write mutant for " + file, e);
        } finally {
            try {
                // Restore the exact original bytes even when the run failed, so a crash cannot leave a
                // mutated tree behind for the next phase to analyse.
                Files.writeString(file, original, StandardCharsets.UTF_8);
            } catch (IOException _) {
                // Restoring is best effort; the workspace is a disposable copy.
            }
        }
    }

    private List<Path> targetFiles(VerificationContext context) {
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
            for (Path file : profile.sourceFiles(root)) {
                if (!profile.isTestPath(relative(context.projectRoot(), file))) {
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

    private static String relative(Path root, Path file) {
        return file.startsWith(root) ? root.relativize(file).toString() : file.getFileName().toString();
    }
}
