package dev.codewitness.python;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * "Builds" a Python project by compiling every source file.
 *
 * <p>Python has no compile step in the Java sense, but it does have a well defined first failure:
 * a file that cannot be parsed. Checking that before anything else runs is what lets the engine
 * report {@code BLOCKED} for a syntax error instead of letting an import error masquerade as a test
 * failure. The check uses the interpreter’s own compiler through a small script, so it produces
 * exact file, line and column diagnostics.
 *
 * <p>Nothing is installed and no package metadata is consulted: a project that cannot be installed
 * can still be verified if its sources are importable from the detected layout.
 */
public final class PythonBuildProvider implements VerificationTools.Compiler {

    @Override
    public String id() {
        return "python-build";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(dev.codewitness.core.model.LanguageId.PYTHON);
    }

    @Override
    public BuildResult build(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        List<Path> roots = project.sourceDirs();
        if (roots.isEmpty()) {
            return BuildResult.notAttempted("no Python source directory was detected");
        }
        String interpreter = project.attributes().getOrDefault(PythonInterpreter.ATTR_INTERPRETER,
                PythonInterpreter.resolve(context.projectRoot(), System.getenv()));
        PythonScripts.Scripts scripts = PythonScripts.write(context);

        List<String> command = new ArrayList<>();
        command.add(interpreter);
        command.add(scripts.buildCheck().toString());
        roots.forEach(root -> command.add(root.toString()));

        ProcessSpec spec = ProcessSpec.builder(command, context.projectRoot())
                .environment(PythonInterpreter.environment(project, context.projectRoot(), List.of()))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(1_000_000)
                .build();
        ProcessResult result = context.runner().run(spec);
        if (!result.started()) {
            return BuildResult.notAttempted(result.failureReason());
        }
        List<CompilerDiagnostic> diagnostics = parseDiagnostics(result.combinedOutput(), context.projectRoot());
        BuildResult build = BuildResult.from(result, diagnostics);
        recordSyntaxSummary(context, result);
        return build;
    }

    private static void recordSyntaxSummary(VerificationContext context, ProcessResult result) {
        for (String line : result.combinedOutput().split("\\R")) {
            if (line.startsWith("CWSYNTAX|")) {
                context.emit("build", "python syntax check " + line.substring("CWSYNTAX|".length()));
                return;
            }
        }
    }

    /** Parses {@code CWDIAG|SEVERITY|file|line|column|message} lines emitted by the checker. */
    static List<CompilerDiagnostic> parseDiagnostics(String output, Path projectRoot) {
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        if (output == null) {
            return diagnostics;
        }
        for (String raw : output.split("\\R")) {
            String line = raw.strip();
            if (!line.startsWith("CWDIAG|")) {
                continue;
            }
            String[] parts = line.split("\\|", 6);
            if (parts.length < 6) {
                continue;
            }
            DiagnosticSeverity severity = parts[1].equalsIgnoreCase("ERROR")
                    ? DiagnosticSeverity.ERROR : DiagnosticSeverity.WARNING;
            diagnostics.add(new CompilerDiagnostic(severity, relativize(projectRoot, parts[2]),
                    integer(parts[3]), integer(parts[4]), parts[5], null));
        }
        return diagnostics;
    }

    private static String relativize(Path projectRoot, String file) {
        try {
            Path path = Path.of(file);
            if (projectRoot != null && path.isAbsolute() && path.startsWith(projectRoot)) {
                return projectRoot.relativize(path).toString();
            }
        } catch (RuntimeException _) {
            // not a usable path: keep the original text
        }
        return file;
    }

    private static Integer integer(String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
