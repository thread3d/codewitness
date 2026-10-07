package dev.codewitness.cpp;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Configures and compiles a C or C++ project with CMake.
 *
 * <p>Two commands, in order, both of which must succeed: {@code cmake -S <source> -B <build>} describes
 * the build, and {@code cmake --build <build>} performs it. They are reported as one result, because a
 * project that cannot be configured and a project that cannot be compiled are the same fact about the
 * run - there is nothing to test - and the transcript names which of the two failed.
 *
 * <p>A failed build ends verification as {@code BLOCKED}, never as a code defect: a project that does not
 * compile says nothing about whether its behaviour is correct. The distinction is what
 * {@link BuildResult#success()} carries and the engine is what turns it into a verdict.
 *
 * <p>The compiler's warnings are carried as diagnostics beside its errors rather than being ignored. They
 * are the compiler's own reading of the code, they cost nothing extra to collect, and a report that listed
 * only the errors would silently discard half of what the build learned.
 */
public final class CppBuildProvider implements VerificationTools.Compiler {

    @Override
    public String id() {
        return "cmake-build";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.CPP);
    }

    @Override
    public BuildResult build(VerificationContext context) {
        Path sourceDir = CppProjectDetector.buildRoot(context.projectRoot());
        if (sourceDir == null) {
            return BuildResult.notAttempted(
                    "no CMakeLists.txt declaring a project was found, so there is nothing for CMake to build");
        }
        CppBuildTools tools = new CppBuildTools(context.runner());
        Path buildDir = tools.buildDirectory(context);
        try {
            Files.createDirectories(buildDir);
        } catch (IOException e) {
            return BuildResult.notAttempted("cannot create the build directory " + buildDir + ": " + e);
        }

        String cmake = CppBuildTools.cmake(context.projectInfo(), context);
        StringBuilder transcript = new StringBuilder();
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        Duration duration = Duration.ZERO;
        boolean started = true;
        boolean timedOut = false;
        String failureReason = null;
        int exitCode = 0;

        List<String> configure = CppBuildTools.configureCommand(cmake, sourceDir, buildDir, false);
        ProcessResult configured = context.runner().run(ProcessSpec.builder(configure, context.projectRoot())
                .environment(CppBuildTools.environment(context))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(2_000_000)
                .build());
        duration = duration.plus(configured.duration());
        transcript.append("$ ").append(String.join(" ", configure)).append('\n')
                .append(configured.combinedOutput()).append('\n');
        if (!configured.started()) {
            started = false;
            failureReason = configured.failureReason();
        } else if (!configured.success()) {
            exitCode = configured.exitCode();
            if (configured.timedOut()) {
                timedOut = true;
            }
            context.emit("build", "cmake configure failed with exit code " + exitCode);
        } else {
            context.emit("build", "configured " + sourceDir.getFileName() + " into " + buildDir);
            List<String> build = CppBuildTools.buildCommand(cmake, buildDir);
            ProcessResult compiled = context.runner().run(ProcessSpec.builder(build, context.projectRoot())
                    .environment(CppBuildTools.environment(context))
                    .timeout(context.config().limits().timeout())
                    .maxOutputBytes(4_000_000)
                    .build());
            duration = duration.plus(compiled.duration());
            transcript.append("$ ").append(String.join(" ", build)).append('\n')
                    .append(compiled.combinedOutput()).append('\n');
            diagnostics.addAll(CppDiagnostics.parse(compiled.combinedOutput(), context.projectRoot()));
            if (!compiled.started()) {
                started = false;
                failureReason = compiled.failureReason();
            } else {
                if (!compiled.success()) {
                    exitCode = compiled.exitCode();
                    // A link failure names no source position, and it is the failure a reader is most
                    // likely to be looking for when the compiler reported nothing at all.
                    List<CompilerDiagnostic> linker =
                            CppDiagnostics.linkerErrors(compiled.combinedOutput());
                    if (linker.isEmpty()
                            || diagnostics.stream().noneMatch(
                                    d -> d.severity() == dev.codewitness.core.model.DiagnosticSeverity.ERROR)) {
                        diagnostics.addAll(linker);
                    }
                }
                if (compiled.timedOut()) {
                    timedOut = true;
                }
            }
        }

        context.emit("build", "cmake " + (timedOut ? "timed out" : "exit code " + exitCode) + " with "
                + diagnostics.size() + " diagnostic(s)");

        return BuildResult.builder()
                .attempted(true)
                .success(started && !timedOut && exitCode == 0)
                .exitCode(started ? exitCode : -1)
                .duration(duration)
                .command(configure)
                .output(transcript.toString().strip())
                .diagnostics(diagnostics)
                .timedOut(timedOut)
                .failureReason(failureReason)
                .build();
    }
}
