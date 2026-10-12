package dev.codewitness.verilog;

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
import java.util.List;

/**
 * Compiles a Verilog design with Icarus Verilog.
 *
 * <p>The compile is the language's first failure: {@code iverilog} parses and elaborates every source
 * it is given, so a module that does not compile is a project problem reported as a failed build - and
 * the engine maps a failed build to {@code BLOCKED} - rather than a defect in the code.
 *
 * <p>Every source file and every testbench is compiled together, because a testbench instantiates the
 * design and a module may instantiate another in a different file. The compile output is written
 * inside the run's artefact directory - never to the project tree's default {@code a.out} - so a
 * verification run leaves the project as it found it.
 */
public final class VerilogBuildProvider implements VerificationTools.Compiler {

    @Override
    public String id() {
        return "verilog-build";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.VERILOG);
    }

    @Override
    public BuildResult build(VerificationContext context) {
        List<Path> sources = VerilogProjectDetector.sourceFiles(context.projectRoot());
        if (sources.isEmpty()) {
            return BuildResult.notAttempted("no Verilog source file was found, so there is nothing to compile");
        }
        String iverilog = VerilogTool.iverilog(context.projectInfo(), context);

        Path output = simulationOutput(context, "build");
        try {
            Files.createDirectories(output.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create the directory for the compiled simulation", e);
        }

        List<String> command = compileCommand(iverilog, sources, output, null);
        ProcessResult result = context.runner().run(ProcessSpec.builder(command, context.projectRoot())
                .environment(VerilogTool.environment(context))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(1_000_000)
                .build());
        if (!result.started()) {
            context.emit("build", "iverilog could not be started: " + result.failureReason());
            return BuildResult.notAttempted(result.failureReason());
        }
        List<CompilerDiagnostic> diagnostics = VerilogDiagnostics.parse(
                result.combinedOutput(), context.projectRoot(), DiagnosticSeverity.ERROR);
        context.emit("build", "iverilog " + result.describeOutcome() + " with "
                + diagnostics.size() + " diagnostic(s)");
        return BuildResult.from(result, diagnostics);
    }

    /**
     * @return the {@code iverilog} invocation that compiles every source into one simulation, selecting
     *     {@code top} as the root when one is given and letting the toolchain choose otherwise.
     */
    public static List<String> compileCommand(String iverilog, List<Path> sources, Path output, String top) {
        List<String> command = new ArrayList<>();
        command.add(iverilog);
        command.add(VerilogTool.DIALECT_FLAG);
        command.add("-o");
        command.add(output.toString());
        if (top != null && !top.isBlank()) {
            command.add("-s");
            command.add(top);
        }
        for (Path source : sources) {
            command.add(source.toString());
        }
        return List.copyOf(command);
    }

    /** @return a path inside the run's artefact directory for a compiled simulation. */
    public static Path simulationOutput(VerificationContext context, String name) {
        return context.artifacts().artifactsDirectory().resolve("verilog").resolve(name + ".vvp");
    }
}
