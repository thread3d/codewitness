package dev.codewitness.rust;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.util.ArrayList;
import java.util.List;

/**
 * Compiles a Rust project with Cargo.
 *
 * <p>The command is {@code cargo build --all-targets}, which compiles the library, binaries,
 * examples and tests. Compiling the tests is deliberate: a test that does not compile is a project
 * problem, and the engine reports a failed build as {@code BLOCKED} rather than letting a compile
 * error masquerade as a test failure.
 *
 * <p>Nothing is installed and the network is governed by {@code sandbox.allow_network}. A failed
 * build is a statement about the build, never about the correctness of the code.
 */
public final class RustBuildProvider implements VerificationTools.Compiler {

    @Override
    public String id() {
        return "cargo-build";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.RUST);
    }

    @Override
    public BuildResult build(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        if (!Cargo.hasManifest(context)) {
            return BuildResult.notAttempted("no Cargo.toml was found, so the project cannot be built");
        }
        String cargo = Cargo.cargo(project, context);
        List<String> command = new ArrayList<>(List.of(cargo, "build", "--all-targets"));

        ProcessSpec spec = ProcessSpec.builder(command, context.projectRoot())
                .environment(Cargo.environment(context))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(1_000_000)
                .build();
        ProcessResult result = context.runner().run(spec);
        if (!result.started()) {
            context.emit("build", "cargo could not be started: " + result.failureReason());
            return BuildResult.notAttempted(result.failureReason());
        }
        List<CompilerDiagnostic> diagnostics = RustDiagnostics.parse(result.combinedOutput(), context.projectRoot());
        context.emit("build", "cargo build " + result.describeOutcome() + " with "
                + diagnostics.size() + " diagnostic(s)");
        return BuildResult.from(result, diagnostics);
    }
}
