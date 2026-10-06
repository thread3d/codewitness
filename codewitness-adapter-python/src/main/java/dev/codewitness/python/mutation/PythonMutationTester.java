package dev.codewitness.python.mutation;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.MutantStatus;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.SurvivingMutant;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.python.PythonInterpreter;
import dev.codewitness.python.PythonProjectDetector;
import dev.codewitness.python.PythonScripts;
import dev.codewitness.python.PythonTestProvider;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Mutation testing for Python using the interpreter's own AST.
 *
 * <p>A surviving mutant is the most direct evidence that a test suite does not constrain the
 * behaviour it appears to constrain, and it should not be unavailable just because a mutation
 * framework is not installed. This tester therefore mutates through the standard library's
 * {@code ast} module: arithmetic operators, comparison boundaries, boolean operators and boolean
 * constants are flipped one at a time, the existing test suite is re-run for each, and mutants the
 * suite cannot detect are reported as survivors.
 *
 * <p>It is deliberately bounded - a fixed operator set, a mutant cap and a wall-clock budget - and
 * the report says so. Surviving mutants never change a verdict: they are reported as prominent
 * evidence and as a run limitation, exactly like the Java adapter's PIT results.
 */
public final class PythonMutationTester implements VerificationTools.MutationTester {

    /** Upper bound on mutants per run, independent of the wall-clock budget. */
    private static final int MAX_MUTANTS = 24;

    @Override
    public String id() {
        return "python-ast-mutation";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(dev.codewitness.core.model.LanguageId.PYTHON);
    }

    @Override
    public MutationReport analyse(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        List<Path> testDirs = project.testSourceDirs();
        if (testDirs.isEmpty()) {
            return MutationReport.notMeasured(id(), "no Python test directory was detected");
        }
        List<Path> targets = targetFiles(context);
        if (targets.isEmpty()) {
            return MutationReport.notMeasured(id(), "no Python source file was available to mutate");
        }
        String interpreter = PythonTestProvider.interpreter(project, context);
        PythonScripts.Scripts scripts = PythonScripts.write(context);
        long seconds = Math.max(1, context.config().mutation().timeout().toSeconds());

        List<String> command = new ArrayList<>();
        command.add(interpreter);
        command.add(scripts.mutation().toString());
        command.add("--root");
        command.add(context.projectRoot().toString());
        command.add("--max");
        command.add(String.valueOf(MAX_MUTANTS));
        command.add("--max-seconds");
        command.add(String.valueOf(seconds));
        command.add("--per-test-timeout");
        command.add(String.valueOf(seconds));
        for (Path file : targets) {
            command.add("--file");
            command.add(file.toString());
        }
        for (Path directory : testDirs) {
            command.add("--test");
            command.add(directory.toString());
        }

        ProcessResult result = context.runner().run(ProcessSpec.builder(command, context.projectRoot())
                .environment(PythonInterpreter.environment(project, context.projectRoot(), List.of()))
                .timeout(context.config().mutation().timeout())
                .maxOutputBytes(1_000_000)
                .build());
        if (!result.started()) {
            return MutationReport.notMeasured(id(), result.failureReason());
        }
        return parse(result, targets.size(), context.projectRoot());
    }

    private static List<Path> targetFiles(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        List<String> configured = context.config().mutation().targetClasses();
        Set<Path> files = new LinkedHashSet<>();
        if (configured != null && !configured.isEmpty()) {
            for (String target : configured) {
                Path candidate = context.projectRoot().resolve(target);
                if (java.nio.file.Files.isRegularFile(candidate)) {
                    files.add(candidate);
                }
            }
            return List.copyOf(files);
        }
        for (Path root : project.sourceDirs()) {
            for (Path file : PythonProjectDetector.pythonFiles(root)) {
                String relative = relative(context.projectRoot(), file);
                if (isTestPath(relative)) {
                    continue;
                }
                files.add(file);
            }
        }
        List<Path> result = new ArrayList<>(files);
        if (result.size() > 20) {
            result = result.subList(0, 20);
        }
        return List.copyOf(result);
    }

    private static boolean isTestPath(String relative) {
        String normalized = relative.replace('\\', '/').toLowerCase(Locale.ROOT);
        String name = normalized.contains("/") ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
        return normalized.startsWith("tests/") || normalized.contains("/tests/")
                || name.startsWith("test_") || name.endsWith("_test.py");
    }

    /** Parses the {@code CWMUTANT} and {@code CMSUMMARY} lines the mutation script emits. */
    static MutationReport parse(ProcessResult result, int fileCount, Path projectRoot) {
        int generated = 0;
        int killed = 0;
        int survived = 0;
        int timedOut = 0;
        List<SurvivingMutant> survivors = new ArrayList<>();
        for (String raw : result.combinedOutput().split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("CWMUTANT|")) {
                String[] parts = line.split("\\|", 6);
                if (parts.length < 6) {
                    continue;
                }
                String status = parts[1];
                String file = parts[2];
                int at = integer(parts[3]);
                String mutator = parts[4];
                String detail = parts[5];
                generated++;
                switch (status) {
                    case "KILLED" -> killed++;
                    case "TIMEOUT" -> timedOut++;
                    default -> {
                        survived++;
                        survivors.add(new SurvivingMutant(
                                String.format(Locale.ROOT, "MUT-%03d", generated),
                                MutantStatus.SURVIVED, mutator, file, null, at,
                                mutator + " " + detail, null));
                    }
                }
            } else if (line.startsWith("CMSUMMARY|")) {
                for (String field : line.substring("CMSUMMARY|".length()).split("\\|")) {
                    int equals = field.indexOf('=');
                    if (equals <= 0) {
                        continue;
                    }
                    String key = field.substring(0, equals);
                    int value = integer(field.substring(equals + 1));
                    switch (key) {
                        case "generated" -> generated = Math.max(generated, value);
                        case "killed" -> killed = Math.max(killed, value);
                        case "survived" -> survived = Math.max(survived, value);
                        case "timedOut" -> timedOut = Math.max(timedOut, value);
                        default -> {
                            // unknown summary field
                        }
                    }
                }
            }
        }
        if (generated == 0) {
            return MutationReport.notMeasured("python-ast",
                    "no mutatable statement was found in the selected sources; last output: "
                            + PythonTestProvider.lastLines(result.combinedOutput(), 3));
        }
        String note = "AST mutation over " + fileCount + " file(s); " + result.describeOutcome()
                + ". The operator set is deliberately small (arithmetic, comparison boundaries, boolean "
                + "operators and boolean constants).";
        return new MutationReport(true, "python-ast", note, generated, killed, survived, 0, timedOut,
                survivors, List.of());
    }

    private static int integer(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String relative(Path root, Path file) {
        return file.startsWith(root) ? root.relativize(file).toString() : file.getFileName().toString();
    }
}
