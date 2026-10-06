package dev.codewitness.typescript;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds a TypeScript project.
 *
 * <p>Two builds are possible, and which one ran is stated in every report:
 *
 * <ul>
 *   <li>when the project pins a TypeScript compiler and a {@code tsconfig.json}, the build is
 *       {@code tsc --noEmit --pretty false}: a real type check, with the compiler's own diagnostics;</li>
 *   <li>otherwise the build is Node's own type stripper over every source and test file, which is the
 *       same parser that will execute them. It catches syntax errors but does not check types, and the
 *       build note says exactly that.</li>
 * </ul>
 *
 * <p>Tests are checked as well as sources, deliberately: a test that cannot be parsed is a project
 * problem, and the engine reports a failed build as {@code BLOCKED} rather than letting a parse error
 * masquerade as a test failure. Nothing is installed: a project without {@code node_modules} is built
 * by the runtime the environment already has.
 */
public final class TypeScriptBuildProvider implements VerificationTools.Compiler {

    @Override
    public String id() {
        return "typescript-build";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.TYPESCRIPT);
    }

    @Override
    public BuildResult build(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        Set<Path> roots = new LinkedHashSet<>(project.sourceDirs());
        roots.addAll(project.testSourceDirs());
        if (roots.isEmpty()) {
            return BuildResult.notAttempted("no TypeScript source directory was detected");
        }

        String tsc = project.attributes().get(NodeRuntime.ATTR_TSC);
        boolean hasConfig = Files.isRegularFile(context.projectRoot().resolve("tsconfig.json"));
        if (tsc != null && !tsc.isBlank() && hasConfig) {
            return typeCheck(context, tsc);
        }
        return syntaxCheck(context, project, List.copyOf(roots));
    }

    /** Runs the project's own TypeScript compiler over its configuration. */
    private BuildResult typeCheck(VerificationContext context, String tsc) {
        List<String> command = List.of(tsc, "--noEmit", "--pretty", "false");
        ProcessResult result = context.runner().run(ProcessSpec.builder(command, context.projectRoot())
                .environment(NodeRuntime.environment(context))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(1_000_000)
                .build());
        if (!result.started()) {
            context.emit("build", "tsc could not be started: " + result.failureReason());
            return BuildResult.notAttempted(result.failureReason());
        }
        List<CompilerDiagnostic> diagnostics = TypeScriptDiagnostics.parseTsc(result.combinedOutput(),
                context.projectRoot());
        context.emit("build", "tsc --noEmit " + result.describeOutcome() + " with "
                + diagnostics.size() + " diagnostic(s)");
        return BuildResult.from(result, diagnostics);
    }

    /** Parses every TypeScript file with the runtime's own type stripper. */
    private BuildResult syntaxCheck(VerificationContext context, ProjectInfo project, List<Path> roots) {
        String node = NodeRuntime.node(project, context);
        TypeScriptScripts.Scripts scripts = TypeScriptScripts.write(context);
        List<String> command = new ArrayList<>();
        command.add(node);
        command.add(scripts.buildCheck().toString());
        roots.forEach(root -> command.add(root.toString()));

        ProcessResult result = context.runner().run(ProcessSpec.builder(command, context.projectRoot())
                .environment(NodeRuntime.environment(context))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(1_000_000)
                .build());
        if (!result.started()) {
            context.emit("build", "node could not be started: " + result.failureReason());
            return BuildResult.notAttempted(result.failureReason());
        }
        List<CompilerDiagnostic> diagnostics = TypeScriptDiagnostics.parseHelper(result.combinedOutput(),
                context.projectRoot());
        String summary = TypeScriptDiagnostics.syntaxSummary(result.combinedOutput());
        // The build note is emitted as a phase message: BuildResult has no note field, and a reader of
        // the report must be able to see that types were not checked rather than assume they were.
        context.emit("build", "node type-stripping syntax check " + result.describeOutcome()
                + "; types were not checked"
                + (summary == null ? "" : " (" + summary + ")")
                + "; " + diagnostics.size() + " diagnostic(s)");
        return BuildResult.from(result, diagnostics);
    }
}
