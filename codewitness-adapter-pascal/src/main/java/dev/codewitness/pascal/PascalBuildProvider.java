package dev.codewitness.pascal;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Compiles a Pascal project with Free Pascal.
 *
 * <p>A project that declares a program is compiled through that program, which pulls in every unit it
 * uses and produces a real executable. A project of units alone has no entry point, so each unit is
 * compiled on its own as the language's first-failure check: a unit the compiler rejects is a failed
 * build, and the engine maps that to {@code BLOCKED} rather than to a defect.
 *
 * <p>Unit and executable output are directed below the run's artifact directory, so a verification never
 * leaves {@code .ppu}, {@code .o} or compiled binaries in the project tree.
 */
public final class PascalBuildProvider implements VerificationTools.Compiler {

    @Override
    public String id() {
        return "pascal-build";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return LanguageId.PASCAL.equals(project.language());
    }

    @Override
    public BuildResult build(VerificationContext context) {
        Path root = context.projectRoot();
        List<Path> sources = PascalProjectDetector.sourceFiles(root);
        if (sources.isEmpty()) {
            return BuildResult.notAttempted("no Pascal source file was found, so there is nothing to compile");
        }
        String fpc = PascalTool.fpc(context.projectInfo(), context);
        List<Path> targets = new ArrayList<>();
        for (PascalModule program : PascalProjectDetector.programs(root)) {
            targets.add(program.file());
        }
        if (targets.isEmpty()) {
            targets.addAll(sources);
        }

        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        List<String> outputs = new ArrayList<>();
        List<String> lastCommand = List.of();
        int lastExitCode = 0;
        java.time.Duration duration = java.time.Duration.ZERO;
        boolean success = true;
        boolean started = true;
        String failureReason = null;
        boolean timedOut = false;

        for (Path target : targets) {
            Path executable = executableOutput(context, PascalSourceParser.stem(target));
            List<String> command = compileCommand(fpc, root, context, target, executable, true);
            context.emit("build", "compiling " + root.relativize(target));
            ProcessResult result = context.runner().run(ProcessSpec.builder(command, root)
                    .environment(PascalTool.environment(context))
                    .timeout(context.config().limits().timeout())
                    .maxOutputBytes(1_000_000)
                    .build());
            lastCommand = result.command();
            duration = duration.plus(result.duration());
            if (!result.started()) {
                started = false;
                failureReason = result.failureReason();
                break;
            }
            diagnostics.addAll(PascalDiagnostics.parse(result.combinedOutput(), root, DiagnosticSeverity.ERROR));
            outputs.add(result.combinedOutput().strip());
            if (!result.success()) {
                success = false;
                lastExitCode = result.exitCode();
                timedOut = result.timedOut();
                break;
            }
        }

        if (!started) {
            context.emit("build", "fpc could not be started: " + failureReason);
            return BuildResult.notAttempted(failureReason == null
                    ? "the Free Pascal compiler could not be started"
                    : failureReason);
        }

        context.emit("build", "fpc " + (success ? "compiled " + targets.size() + " target(s)" : "failed")
                + " with " + diagnostics.size() + " diagnostic(s)");
        String output = String.join(System.lineSeparator(), outputs);
        if (success) {
            return BuildResult.builder()
                    .attempted(true)
                    .success(true)
                    .exitCode(0)
                    .duration(duration)
                    .command(lastCommand)
                    .output(output)
                    .diagnostics(diagnostics)
                    .build();
        }
        return BuildResult.builder()
                .attempted(true)
                .success(false)
                .exitCode(lastExitCode)
                .duration(duration)
                .command(lastCommand)
                .output(output)
                .diagnostics(diagnostics)
                .timedOut(timedOut)
                .build();
    }

    /**
     * @param fpc        the compiler to invoke
     * @param root       project root, which every command runs from
     * @param context    run context, providing the artifact directory
     * @param target     the source file to compile
     * @param executable the executable to produce
     * @param link       when {@code false} the target is compiled only, which is what a unit allows
     * @return the command line
     */
    public static List<String> compileCommand(
            String fpc, Path root, VerificationContext context, Path target, Path executable, boolean link) {
        List<String> command = new ArrayList<>();
        command.add(fpc);
        Set<Path> unitDirectories = new LinkedHashSet<>(PascalProjectDetector.sourceDirectories(root));
        for (Path directory : unitDirectories) {
            command.add("-Fu" + directory);
        }
        command.add("-FU" + unitOutputDirectory(context));
        if (link) {
            // The output path already names the directory, so it is stated once rather than as a
            // separate output directory the compiler would have to reconcile with it.
            command.add("-o" + executable);
        }
        command.add(target.toString());
        return List.copyOf(command);
    }

    /** @return the directory unit output ({@code .ppu}, {@code .o}) is written to. */
    public static Path unitOutputDirectory(VerificationContext context) {
        Path directory = context.artifacts().artifactsDirectory().resolve("pascal").resolve("units");
        createDirectories(directory);
        return directory;
    }

    /** @return the directory executables are written to. */
    public static Path executableOutputDirectory(VerificationContext context) {
        Path directory = context.artifacts().artifactsDirectory().resolve("pascal").resolve("build");
        createDirectories(directory);
        return directory;
    }

    /** @return the executable path for a program compiled during the build phase. */
    static Path executableOutput(VerificationContext context, String name) {
        return executableOutputDirectory(context).resolve(safeName(name) + executableSuffix());
    }

    /** @return the host's executable suffix, empty except on Windows. */
    public static String executableSuffix() {
        return PascalTool.isWindows() ? ".exe" : "";
    }

    /** @return {@code value} reduced to the characters a file name and a report can carry. */
    public static String safeName(String value) {
        StringBuilder safe = new StringBuilder();
        for (char c : value.toCharArray()) {
            safe.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' ? c : '_');
        }
        return safe.length() == 0 ? "unnamed" : safe.toString().toLowerCase(java.util.Locale.ROOT);
    }

    private static void createDirectories(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create " + directory, e);
        }
    }
}
