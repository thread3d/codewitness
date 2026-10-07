package dev.codewitness.csharp;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Compiles a .NET project with {@code dotnet build}.
 *
 * <p>The target is the project's own solution when it has one, because that is the project's statement
 * of which projects belong together and one command covers all of them. A tree with no solution has its
 * projects built in turn instead, so no project is silently left uncompiled. Which projects exist and
 * which of them are tests is decided by {@link CSharpProjectDetector}; this class only compiles them.
 *
 * <p>A failed build ends verification as {@code BLOCKED}, never as a code defect: a project that does not
 * compile says nothing about whether its behaviour is correct.
 *
 * <p>The recorded output is a transcript with each command echoed before its own output, because a run
 * that built several projects is not described by naming only the first command. Restore is left on -
 * the .NET SDK needs it on a clean workspace, and the environment is what keeps it from fetching
 * anything the machine does not already have.
 */
public final class CSharpBuildProvider implements VerificationTools.Compiler {

    @Override
    public String id() {
        return "dotnet-build";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.CSHARP);
    }

    @Override
    public BuildResult build(VerificationContext context) {
        List<Path> targets = DotnetTool.buildTargets(context);
        if (targets.isEmpty()) {
            return BuildResult.notAttempted(
                    "no .csproj or .sln was found, so there is nothing for the .NET SDK to build");
        }
        String dotnet = DotnetTool.dotnet(context.projectInfo(), context);
        Path root = context.projectRoot();

        StringBuilder transcript = new StringBuilder();
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        List<String> firstCommand = List.of();
        Duration duration = Duration.ZERO;
        int exitCode = 0;
        boolean started = true;
        boolean timedOut = false;
        String failureReason = null;

        for (Path target : targets) {
            List<String> command = commandFor(dotnet, root, target);
            if (firstCommand.isEmpty()) {
                firstCommand = command;
            }
            ProcessResult result = context.runner().run(ProcessSpec.builder(command, root)
                    .environment(DotnetTool.environment(context))
                    .timeout(context.config().limits().timeout())
                    .maxOutputBytes(2_000_000)
                    .build());
            duration = duration.plus(result.duration());
            transcript.append("$ ").append(String.join(" ", command)).append('\n')
                    .append(result.combinedOutput()).append('\n');
            diagnostics.addAll(CSharpDiagnostics.parse(result.combinedOutput(), root));
            if (!result.started()) {
                // A toolchain that will not start is a fact about the environment, and every later target
                // would fail the same way; the loop stops rather than repeating the same failure.
                started = false;
                failureReason = result.failureReason();
                if (exitCode == 0) {
                    exitCode = -1;
                }
                break;
            }
            if (result.timedOut()) {
                timedOut = true;
            }
            if (!result.success() && exitCode == 0) {
                exitCode = result.exitCode();
            }
        }

        context.emit("build", "dotnet build " + (timedOut ? "timed out" : "exit code " + exitCode)
                + " over " + targets.size() + " target(s) with " + diagnostics.size() + " diagnostic(s)");

        BuildResult.Builder builder = BuildResult.builder()
                .attempted(true)
                .success(started && !timedOut && exitCode == 0)
                .exitCode(exitCode)
                .duration(duration)
                .command(firstCommand)
                .output(transcript.toString().strip())
                .diagnostics(diagnostics)
                .timedOut(timedOut)
                .failureReason(failureReason);
        return builder.build();
    }

    /** @return the argv for building one target, relative to the project root where possible. */
    static List<String> commandFor(String dotnet, Path root, Path target) {
        List<String> command = new ArrayList<>();
        command.add(dotnet);
        command.add("build");
        command.add(relativeArgument(root, target));
        command.addAll(DotnetTool.commonArguments());
        return List.copyOf(command);
    }

    /**
     * @return the target as a path relative to the working directory when it lies inside it, so the
     *     recorded command is one a reader can paste back into the same directory.
     */
    public static String relativeArgument(Path root, Path target) {
        if (target == null) {
            return ".";
        }
        Path normalized = target.toAbsolutePath().normalize();
        if (root != null && normalized.startsWith(root)) {
            Path relative = root.relativize(normalized);
            return relative.toString().isEmpty() ? "." : relative.toString();
        }
        return normalized.toString();
    }
}
