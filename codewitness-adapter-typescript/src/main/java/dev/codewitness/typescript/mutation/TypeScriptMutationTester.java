package dev.codewitness.typescript.mutation;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.MutantStatus;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.SurvivingMutant;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.support.mutation.TextMutator;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.typescript.NodeRuntime;
import dev.codewitness.typescript.TypeScriptProjectDetector;
import dev.codewitness.typescript.TypeScriptScripts;
import dev.codewitness.typescript.TypeScriptTestProvider;

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
 * Mutation testing for TypeScript using a bounded source mutator.
 *
 * <p>A mutation framework is an optional dependency, and a project should not lose mutation evidence
 * just because it is not installed. The adapter therefore mutates the source text itself through
 * {@link dev.codewitness.support.mutation.TextMutator}, re-runs the project's own test suite for each mutant with
 * {@code node --test}, and reports the mutants the suite could not detect as survivors.
 *
 * <p>A mutant that the runtime's type stripper cannot parse is non-viable and is excluded from the score
 * rather than counted as killed; the parse is checked with the same helper the build uses, so
 * "does not compile" and "was detected" are never confused. The run is bounded three ways: a fixed
 * operator set, a mutant cap and a wall-clock budget, and every file is restored in a {@code finally}
 * block so a crash cannot leave a mutated source tree behind.
 *
 * <p>Surviving mutants never change a verdict: they are prominent evidence and a run limitation,
 * exactly like the Java adapter's PIT results.
 */
public final class TypeScriptMutationTester implements VerificationTools.MutationTester {

    /** Upper bound on mutants per run, independent of the wall-clock budget. */
    private static final int MAX_MUTANTS = 16;

    /** Upper bound on source files considered, so a large project cannot dominate a run. */
    private static final int MAX_FILES = 20;

    @Override
    public String id() {
        return "typescript-text-mutation";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.TYPESCRIPT);
    }

    @Override
    public MutationReport analyse(VerificationContext context) {
        List<Path> tests = TypeScriptTestProvider.testFiles(context);
        if (tests.isEmpty()) {
            return MutationReport.notMeasured(id(),
                    "no Node-runnable test file was detected, so a mutant could not be judged");
        }
        List<Path> targets = targetFiles(context);
        if (targets.isEmpty()) {
            return MutationReport.notMeasured(id(), "no TypeScript source file was available to mutate");
        }
        context.emit("mutation", "mutating " + targets.size() + " TypeScript source file(s) with the built-in mutator");

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
            List<TextMutator.Site> sites = TextMutator.sites(original);
            for (TextMutator.Site site : sites) {
                if (generated >= MAX_MUTANTS || Instant.now().isAfter(deadline)) {
                    break;
                }
                Duration remaining = Duration.between(Instant.now(), deadline);
                if (remaining.isNegative() || remaining.isZero()) {
                    break;
                }
                generated++;
                Status status = runMutant(context, file, original, site, tests, remaining);
                switch (status) {
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
        String note = "source-level mutation over " + targets.size() + " file(s); " + nonViable
                + " non-viable mutant(s) excluded. The operator set is deliberately small (arithmetic, "
                + "comparison boundaries, equality, boolean operators and boolean constants), and only "
                + "operators surrounded by whitespace are considered.";
        return new MutationReport(true, "typescript-text", note, generated, killed, survived, 0, timedOut,
                survivors, List.of());
    }

    /** What happened when one mutant was parsed and tested. */
    private enum Status { KILLED, SURVIVED, TIMED_OUT, NON_VIABLE }

    private Status runMutant(
            VerificationContext context, Path file, String original, TextMutator.Site site,
            List<Path> tests, Duration remaining) {
        try {
            Files.writeString(file, TextMutator.apply(original, site), StandardCharsets.UTF_8);
            ProcessResult result = runTests(context, tests, remaining);
            if (result.timedOut()) {
                return Status.TIMED_OUT;
            }
            if (result.success()) {
                return Status.SURVIVED;
            }
            // A failing run is only "killed" if the mutant was executable at all; a mutant the runtime
            // cannot parse says nothing about test strength.
            return parses(context, file, remaining) ? Status.KILLED : Status.NON_VIABLE;
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

    private ProcessResult runTests(VerificationContext context, List<Path> tests, Duration remaining) {
        String node = NodeRuntime.node(context.projectInfo(), context);
        List<String> command = new ArrayList<>(List.of(node, "--test"));
        tests.forEach(test -> command.add(test.toString()));
        return context.runner().run(ProcessSpec.builder(command, context.projectRoot())
                .environment(NodeRuntime.environment(context))
                .timeout(remaining)
                .maxOutputBytes(1_000_000)
                .build());
    }

    /** @return {@code true} when the mutated file still parses under the runtime's type stripper. */
    private boolean parses(VerificationContext context, Path file, Duration remaining) {
        String node = NodeRuntime.node(context.projectInfo(), context);
        TypeScriptScripts.Scripts scripts = TypeScriptScripts.write(context);
        ProcessResult result = context.runner().run(ProcessSpec.builder(
                        List.of(node, scripts.buildCheck().toString(), file.toString()), context.projectRoot())
                .environment(NodeRuntime.environment(context))
                .timeout(remaining)
                .maxOutputBytes(256_000)
                .build());
        return result.success();
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
            for (Path file : TypeScriptProjectDetector.typeScriptFiles(root)) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".tsx") && !isTestPath(relative(context.projectRoot(), file))) {
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
        String name = normalized.contains("/")
                ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
        return normalized.startsWith("tests/") || normalized.contains("/tests/")
                || normalized.startsWith("test/") || normalized.contains("/test/")
                || normalized.contains("__tests__") || name.contains(".test.") || name.contains(".spec.");
    }

    private static String relative(Path root, Path file) {
        return file.startsWith(root) ? root.relativize(file).toString() : file.getFileName().toString();
    }
}
