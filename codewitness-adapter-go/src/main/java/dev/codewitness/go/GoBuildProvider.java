package dev.codewitness.go;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.util.List;

/**
 * Compiles a Go module with {@code go build ./...}.
 *
 * <p>The pattern compiles every package in the module, including the ones a binary would not reach,
 * so a package that does not compile is a project problem reported as a failed build rather than a
 * defect in the code. A failed build is a statement about the build, never about the correctness of
 * the code, and the engine maps it to {@code BLOCKED}.
 *
 * <p>Nothing is installed and the network is governed by {@code sandbox.allow_network}. Go test
 * files are compiled by the test phase, where {@code go test} builds them with the same toolchain.
 */
public final class GoBuildProvider implements VerificationTools.Compiler {

    @Override
    public String id() {
        return "go-build";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.GO);
    }

    @Override
    public BuildResult build(VerificationContext context) {
        if (!GoTool.hasManifest(context)) {
            return BuildResult.notAttempted("no go.mod was found, so the module cannot be built");
        }
        String go = GoTool.go(context.projectInfo(), context);
        List<String> command = List.of(go, "build", "./...");

        ProcessSpec spec = ProcessSpec.builder(command, context.projectRoot())
                .environment(GoTool.environment(context))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(1_000_000)
                .build();
        ProcessResult result = context.runner().run(spec);
        if (!result.started()) {
            context.emit("build", "go could not be started: " + result.failureReason());
            return BuildResult.notAttempted(result.failureReason());
        }
        List<CompilerDiagnostic> diagnostics =
                GoDiagnostics.parse(result.combinedOutput(), context.projectRoot(), DiagnosticSeverity.ERROR);
        context.emit("build", "go build " + result.describeOutcome() + " with "
                + diagnostics.size() + " diagnostic(s)");
        return BuildResult.from(result, diagnostics);
    }
}
