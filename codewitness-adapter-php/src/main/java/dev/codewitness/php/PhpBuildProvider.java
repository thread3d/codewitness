package dev.codewitness.php;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * "Builds" a PHP project by parsing every source file and installing its dependencies.
 *
 * <p>PHP has no compile step in the Java sense, but it has a first failure that must not be reported as
 * a code defect: a file the interpreter cannot parse. The check uses the interpreter's own parser, so
 * the diagnostics are exact rather than an approximation by a second, differently wrong parser.
 *
 * <p>Both halves are what the engine's {@code BLOCKED} verdict is for. A syntax error is a broken build,
 * not a failed requirement; a Composer install that cannot reach Packagist is an infrastructure problem
 * and is reported with Composer's own output, never as a finding about the project's code.
 *
 * <p>{@code --no-scripts} is deliberate. Composer's install scripts run arbitrary project code as part
 * of installing dependencies, and a verifier that runs a project's install scripts is running code the
 * project did not ask it to run at that point; the sources under verification are executed only in the
 * test and mutation phases, where that is the whole purpose.
 */
public final class PhpBuildProvider implements VerificationTools.Compiler {

    /** Composer arguments that make an install unattended and reproducible; never {@code update}. */
    private static final List<String> COMPOSER_INSTALL = List.of(
            "install", "--no-interaction", "--no-progress", "--no-scripts", "--prefer-dist");

    @Override
    public String id() {
        return "php-build";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.PHP);
    }

    @Override
    public BuildResult build(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        String probeFailure = project.attributes().get(PhpInterpreter.ATTR_PROBE_FAILURE);
        if (probeFailure != null) {
            // Nothing below can run without an interpreter, and a result that blames the project for a
            // missing tool is exactly what the engine's BLOCKED verdict exists to prevent.
            return BuildResult.notAttempted(
                    "the PHP interpreter probe failed, so no source file could be parsed: " + probeFailure);
        }
        List<Path> files = sourcesToCheck(context);
        if (files.isEmpty()) {
            return BuildResult.notAttempted("no PHP source file was detected outside the project's test directories");
        }

        Path script = PhpScripts.write(context).lintScript();
        List<String> command = new ArrayList<>();
        command.add(PhpInterpreter.php(project, context));
        command.add(script.toString());
        files.forEach(file -> command.add(file.toString()));
        ProcessResult lint = context.runner().run(ProcessSpec.builder(command, context.projectRoot())
                .environment(PhpInterpreter.environment(project, context.projectRoot(),
                        context.artifacts().artifactsDirectory()))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(1_000_000)
                .build());
        if (!lint.started()) {
            return BuildResult.notAttempted(lint.failureReason());
        }
        List<CompilerDiagnostic> diagnostics = parseDiagnostics(lint.combinedOutput(), context.projectRoot());
        BuildResult result = BuildResult.from(lint, diagnostics);
        recordSyntaxSummary(context, lint);
        if (!result.success()) {
            return result;
        }

        Path composerManifest = context.projectRoot().resolve("composer.json");
        if (Files.isRegularFile(composerManifest)) {
            List<String> composer = PhpInterpreter.composer(project, context);
            if (composer.isEmpty()) {
                return BuildResult.notAttempted("composer.json exists but no Composer executable was found "
                        + "(project composer.phar, PATH, or .tools/php/composer.phar), so the project's "
                        + "dependencies could not be installed");
            }
            List<String> installCommand = new ArrayList<>(composer);
            installCommand.addAll(COMPOSER_INSTALL);
            ProcessResult install = context.runner().run(ProcessSpec.builder(installCommand, context.projectRoot())
                    .environment(PhpInterpreter.environment(project, context.projectRoot(),
                            context.artifacts().artifactsDirectory()))
                    .timeout(context.config().limits().timeout())
                    .maxOutputBytes(1_000_000)
                    .build());
            context.emit("build", "composer install " + install.describeOutcome());
            if (!install.success()) {
                // Composer failed to install; nothing was learned about the project's code, and the
                // engine reports a failed build as BLOCKED with Composer's own log attached.
                return BuildResult.from(install);
            }
        }
        return result;
    }

    /**
     * @return the project's own PHP files, excluding the vendor tree, the caches and the test roots.
     *
     * <p>Test sources are left out because they are parsed by the test phase's own run; checking them
     * here would attribute a broken test file to the build of the code it tests.
     */
    private static List<Path> sourcesToCheck(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        List<Path> testRoots = project.testSourceDirs();
        List<Path> roots = new ArrayList<>(project.sourceDirs());
        roots.add(context.projectRoot());
        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            for (Path file : PhpProjectDetector.phpFiles(root)) {
                if (!files.contains(file) && !insideAny(file, testRoots)) {
                    files.add(file);
                }
            }
        }
        return files;
    }

    private static boolean insideAny(Path file, List<Path> directories) {
        Path normalized = file.toAbsolutePath().normalize();
        for (Path directory : directories) {
            if (directory != null && normalized.startsWith(directory.toAbsolutePath().normalize())) {
                return true;
            }
        }
        return false;
    }

    private static void recordSyntaxSummary(VerificationContext context, ProcessResult result) {
        for (String line : result.combinedOutput().split("\\R")) {
            if (line.startsWith("CWSYNTAX|")) {
                context.emit("build", "php syntax check " + line.substring("CWSYNTAX|".length()));
                return;
            }
        }
    }

    /**
     * Parses the {@code CWDIAG|SEVERITY|file|line|message} lines emitted by the checker.
     *
     * <p>Only lines with the full field count become diagnostics: a truncated line is a checker that was
     * interrupted, and turning it into a diagnostic would invent a location for it. The column is
     * always {@code null} because the interpreter's parse error reports a line, not a column.
     */
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
            String[] parts = line.split("\\|", 5);
            if (parts.length < 5) {
                continue;
            }
            DiagnosticSeverity severity = parts[1].equalsIgnoreCase("ERROR")
                    ? DiagnosticSeverity.ERROR : DiagnosticSeverity.WARNING;
            diagnostics.add(new CompilerDiagnostic(severity, relativize(projectRoot, parts[2]),
                    integer(parts[3]), null, parts[4], null));
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
        } catch (NumberFormatException _) {
            return null;
        }
    }
}
