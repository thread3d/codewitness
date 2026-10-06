package dev.codewitness.javascript;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds a JavaScript project.
 *
 * <p>JavaScript has no compile step, so the build is a syntax check of every source and test file with
 * {@code node --check} — the same parser that will execute them. It catches syntax errors but checks no
 * types, and the build message says exactly that rather than implying a type check happened.
 *
 * <p>Tests are checked as well as sources, deliberately: a test that cannot be parsed is a project
 * problem, and the engine reports a failed build as {@code BLOCKED} rather than letting a parse error
 * masquerade as a test failure. Nothing is installed: a project without {@code node_modules} is built by
 * the runtime the environment already has.
 */
public final class JavaScriptBuildProvider implements VerificationTools.Compiler {

    @Override
    public String id() {
        return "javascript-build";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.JAVASCRIPT);
    }

    @Override
    public BuildResult build(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        List<Path> roots = checkRoots(project);
        if (roots.isEmpty()) {
            return BuildResult.notAttempted("no JavaScript source directory was detected");
        }
        return syntaxCheck(context, project, roots);
    }

    /**
     * @return the distinct roots the syntax check must walk: every source directory plus any test
     *     directory that is not already inside one.
     *
     * <p>A test directory is normally separate from the source root. When a project has no {@code src/}
     * directory its source root is the project root, which already contains the test directory; passing
     * both would make the checker walk the same file twice and report a duplicate diagnostic for a test
     * that does not parse.
     */
    static List<Path> checkRoots(ProjectInfo project) {
        Set<Path> roots = new LinkedHashSet<>(project.sourceDirs());
        for (Path testDirectory : project.testSourceDirs()) {
            boolean alreadyCovered = project.sourceDirs().stream().anyMatch(testDirectory::startsWith);
            if (!alreadyCovered) {
                roots.add(testDirectory);
            }
        }
        return List.copyOf(roots);
    }

    /** Parses every JavaScript file with the runtime's own parser. */
    private BuildResult syntaxCheck(VerificationContext context, ProjectInfo project, List<Path> roots) {
        String node = NodeRuntime.node(project, context);
        JavaScriptScripts.Scripts scripts = JavaScriptScripts.write(context);
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
        List<CompilerDiagnostic> diagnostics = JavaScriptDiagnostics.parseHelper(result.combinedOutput(),
                context.projectRoot());
        String summary = JavaScriptDiagnostics.syntaxSummary(result.combinedOutput());
        // The build note is emitted as a phase message: BuildResult has no note field, and a reader of
        // the report must be able to see that only syntax was checked rather than assume types were.
        context.emit("build", "node syntax check " + result.describeOutcome()
                + "; JavaScript has no type checker, so no type check was performed"
                + (summary == null ? "" : " (" + summary + ")")
                + "; " + diagnostics.size() + " diagnostic(s)");
        return BuildResult.from(result, diagnostics);
    }
}
