package dev.codewitness.vhdl;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Analyses and elaborates a VHDL design with GHDL.
 *
 * <p>VHDL's first failure is analysis: {@code ghdl -a} compiles each design unit into a work library,
 * and a unit the analyser rejects is a project problem reported as a failed build - which the engine
 * maps to {@code BLOCKED} - rather than a defect in the code. Analysis is order-sensitive, so
 * {@link VhdlAnalyzer} runs it in dependency order rather than in file order.
 *
 * <p>Elaboration is the second half of what "the design builds" means in VHDL: analysis can succeed on
 * a design whose components cannot be bound, and only {@code ghdl -e} catches that. When the tree
 * declares a design entity with ports, the build elaborates it as well, so an unbound component or a
 * port mismatch is a failed build rather than a surprise at simulation time.
 *
 * <p>Every source is analysed together, because a testbench instantiates the design and a design unit
 * may instantiate another across files. The work library is written inside the run's artefact
 * directory - never to the project tree's default {@code work-obj08.cf} - so a verification run leaves
 * the project as it found it.
 */
public final class VhdlBuildProvider implements VerificationTools.Compiler {

    @Override
    public String id() {
        return "vhdl-build";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.VHDL);
    }

    @Override
    public BuildResult build(VerificationContext context) {
        List<Path> sources = VhdlProjectDetector.sourceFiles(context.projectRoot());
        if (sources.isEmpty()) {
            return BuildResult.notAttempted("no VHDL source file was found, so there is nothing to analyse");
        }
        String ghdl = VhdlTool.ghdl(context.projectInfo(), context);
        Path workDirectory = workLibrary(context, "build");

        VhdlAnalyzer.Analysis analysis = VhdlAnalyzer.analyze(context, ghdl, workDirectory, sources);
        Duration duration = analysis.duration();
        if (!analysis.success()) {
            context.emit("build", "ghdl analysis failed with " + analysis.diagnostics().size() + " diagnostic(s)");
            return BuildResult.from(analysis.representative(), analysis.diagnostics());
        }
        context.emit("build", "ghdl analysed " + sources.size() + " source file(s)");

        VhdlDesignUnit top = VhdlProjectDetector.topEntity(context.projectRoot());
        if (top == null) {
            return success(duration, List.of());
        }
        ProcessResult elaborated = context.runner().run(ProcessSpec.builder(
                        elaborateCommand(ghdl, workDirectory, top.name()), context.projectRoot())
                .environment(VhdlTool.environment(context))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(1_000_000)
                .build());
        duration = duration.plus(elaborated.duration());
        if (!elaborated.started()) {
            context.emit("build", "ghdl elaboration could not be started: " + elaborated.failureReason());
            return BuildResult.notAttempted(elaborated.failureReason());
        }
        if (!elaborated.success()) {
            List<CompilerDiagnostic> diagnostics =
                    VhdlDiagnostics.parse(elaborated.combinedOutput(), context.projectRoot(), DiagnosticSeverity.ERROR);
            context.emit("build", "ghdl elaboration failed with " + diagnostics.size() + " diagnostic(s)");
            return BuildResult.from(elaborated, diagnostics);
        }
        context.emit("build", "ghdl elaborated " + top.name());
        return success(duration, List.of());
    }

    private static BuildResult success(Duration duration, List<CompilerDiagnostic> diagnostics) {
        return BuildResult.builder()
                .attempted(true)
                .success(true)
                .exitCode(0)
                .duration(duration)
                .output("")
                .diagnostics(diagnostics)
                .build();
    }

    /** @return the {@code ghdl -a} invocation that analyses one design unit into a work library. */
    public static List<String> analyzeCommand(String ghdl, Path workDirectory, Path source) {
        List<String> command = new ArrayList<>();
        command.add(ghdl);
        command.add("-a");
        command.add(VhdlTool.STD_FLAG);
        command.add("--workdir=" + workDirectory);
        command.add(source.toString());
        return List.copyOf(command);
    }

    /** @return the {@code ghdl -e} invocation that elaborates a top-level entity. */
    public static List<String> elaborateCommand(String ghdl, Path workDirectory, String top) {
        List<String> command = new ArrayList<>();
        command.add(ghdl);
        command.add("-e");
        command.add(VhdlTool.STD_FLAG);
        command.add("--workdir=" + workDirectory);
        command.add(top);
        return List.copyOf(command);
    }

    /** @return the {@code ghdl -r} invocation that simulates a testbench, failing on any assertion error. */
    public static List<String> runCommand(String ghdl, Path workDirectory, String testbench) {
        List<String> command = new ArrayList<>();
        command.add(ghdl);
        command.add("-r");
        command.add(VhdlTool.STD_FLAG);
        command.add("--workdir=" + workDirectory);
        command.add(testbench);
        // Without this, GHDL prints an assertion error and still exits zero, so a failing design would
        // be reported as a passing test. The option is a simulation option and must follow the unit name.
        command.add("--assert-level=error");
        return List.copyOf(command);
    }

    /** @return a work library path inside the run's artefact directory. */
    public static Path workLibrary(VerificationContext context, String name) {
        return context.artifacts().artifactsDirectory().resolve("vhdl").resolve(name);
    }
}
