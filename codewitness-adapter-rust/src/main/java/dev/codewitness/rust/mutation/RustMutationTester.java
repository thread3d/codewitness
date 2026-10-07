package dev.codewitness.rust.mutation;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.MutantStatus;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.SurvivingMutant;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.rust.Cargo;
import dev.codewitness.rust.RustProjectDetector;
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
 * Mutation testing for Rust using a bounded source mutator.
 *
 * <p>{@code cargo-mutants} is an optional tool, and a project should not lose mutation evidence just
 * because it is not installed. The adapter therefore mutates the source text itself through
 * {@link TextMutator}, recompiles and re-runs the project's own test suite for each mutant, and
 * reports the mutants the suite could not detect as survivors.
 *
 * <p>The run is bounded three ways: a fixed operator set, a mutant cap and a wall-clock budget. Every
 * file is restored in a {@code finally} block, so a crash cannot leave a mutated source tree behind.
 * A mutant that fails to compile is non-viable and is excluded from the score rather than counted as
 * killed. Surviving mutants never change a verdict: they are prominent evidence and a run
 * limitation, exactly like the Java adapter's PIT results.
 */
public final class RustMutationTester implements VerificationTools.MutationTester {

    /** Upper bound on mutants per run, independent of the wall-clock budget. */
    private static final int MAX_MUTANTS = 16;

    /** Upper bound on source files considered, so a large crate cannot dominate a run. */
    private static final int MAX_FILES = 20;

    /** The mutator over Rust's own lexis; the loop below is language independent. */
    private static final TextMutator MUTATOR = new TextMutator(RustLexis.INSTANCE);

    @Override
    public String id() {
        return "rust-text-mutation";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.RUST);
    }

    @Override
    public MutationReport analyse(VerificationContext context) {
        if (!Cargo.hasManifest(context)) {
            return MutationReport.notMeasured(id(), "no Cargo.toml was found, so mutants cannot be compiled");
        }
        List<Path> targets = targetFiles(context);
        if (targets.isEmpty()) {
            return MutationReport.notMeasured(id(), "no Rust source file was available to mutate");
        }
        context.emit("mutation", "mutating " + targets.size() + " Rust source file(s) with the built-in mutator");

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
            return MutationReport.notMeasured(id(),
                    "no mutatable operator was found in the selected sources");
        }
        String note = "source-level mutation over " + targets.size() + " file(s); " + nonViable
                + " non-viable mutant(s) excluded. The operator set is deliberately small (arithmetic, "
                + "comparison boundaries, boolean operators and boolean constants), and only operators "
                + "surrounded by whitespace are considered.";
        return new MutationReport(true, "rust-text", note, generated, killed, survived, 0, timedOut,
                survivors, List.of());
    }

    /** What happened when one mutant was compiled and tested. */
    private enum Status { KILLED, SURVIVED, TIMED_OUT, NON_VIABLE }

    private record Outcome(Status status) {
    }

    private Outcome runMutant(
            VerificationContext context, Path file, String original, TextMutator.Site site, Duration remaining) {
        Path working = file;
        try {
            Files.writeString(working, TextMutator.apply(original, site), StandardCharsets.UTF_8);
            String cargo = Cargo.cargo(context.projectInfo(), context);
            ProcessResult result = context.runner().run(ProcessSpec.builder(
                            List.of(cargo, "test", "--no-fail-fast", "--quiet"), context.projectRoot())
                    .environment(Cargo.environment(context))
                    .timeout(remaining)
                    .maxOutputBytes(1_000_000)
                    .build());
            if (result.timedOut()) {
                return new Outcome(Status.TIMED_OUT);
            }
            String output = result.combinedOutput();
            boolean testsRan = output.contains("test result:");
            if (!testsRan) {
                // The mutant did not compile, or nothing ran: it is not evidence about test strength.
                return new Outcome(Status.NON_VIABLE);
            }
            return new Outcome(result.success() ? Status.SURVIVED : Status.KILLED);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write mutant for " + file, e);
        } finally {
            try {
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
            return List.copyOf(files);
        }
        for (Path root : project.sourceDirs()) {
            for (Path file : RustProjectDetector.rustFiles(root)) {
                if (!isTestPath(relative(context.projectRoot(), file))) {
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

    private static boolean isTestPath(String relative) {
        String normalized = relative.replace('\\', '/').toLowerCase(Locale.ROOT);
        String name = normalized.contains("/") ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
        return normalized.startsWith("tests/") || normalized.contains("/tests/") || name.endsWith("_test.rs");
    }

    private static String relative(Path root, Path file) {
        return file.startsWith(root) ? root.relativize(file).toString() : file.getFileName().toString();
    }
}
