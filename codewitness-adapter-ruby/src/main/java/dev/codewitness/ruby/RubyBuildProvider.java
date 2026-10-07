package dev.codewitness.ruby;

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
 * "Builds" a Ruby project by parsing every source file and installing its dependencies.
 *
 * <p>Ruby has no compile step in the Java sense, but it has a first failure that must not be reported as
 * a code defect: a file the interpreter cannot parse. The check uses the interpreter's own parser, so
 * the diagnostics are exact rather than an approximation by a second, differently wrong parser.
 *
 * <p>That check runs before {@code bundle install}, and the order is deliberate. A tree that does not
 * parse is already a broken build, and installing a bundle into it would spend a network round trip and a
 * minute of the run's budget only to report whatever the install failed at instead of the syntax error
 * the project actually has. Ruby, like PHP, is interpreted: nothing below the parse check can rescue a
 * file that does not parse.
 *
 * <p>Both halves are what the engine's {@code BLOCKED} verdict is for. A syntax error is a broken build,
 * not a failed requirement; a Bundler install that cannot reach RubyGems is an infrastructure problem and
 * is reported with Bundler's own output, never as a finding about the project's code.
 *
 * <p>No Bundler install script is run beyond what {@code bundle install} itself does, and the command is
 * never {@code bundle update}: verification must measure the dependency set the project committed to in
 * its lock file, not the one that happens to be newest today.
 */
public final class RubyBuildProvider implements VerificationTools.Compiler {

    /**
     * Bundler arguments that make an install unattended and reproducible.
     *
     * <p>{@code --jobs} is deliberately left at Bundler's own default. The number of parallel workers a
     * machine can usefully run is a property of the machine, and a verifier that pins it either wastes
     * time on a large host or over-subscribes a small one. Nothing else is passed either: an install that
     * refuses to run without a flag would be one whose failure says something about the flag rather than
     * about the project.
     */
    private static final List<String> BUNDLE_INSTALL = List.of("install");

    @Override
    public String id() {
        return "ruby-build";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.RUBY);
    }

    @Override
    public BuildResult build(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        String probeFailure = project.attributes().get(RubyInterpreter.ATTR_PROBE_FAILURE);
        if (probeFailure != null) {
            // Nothing below can run without an interpreter, and a result that blames the project for a
            // missing tool is exactly what the engine's BLOCKED verdict exists to prevent.
            return BuildResult.notAttempted(
                    "the Ruby interpreter probe failed, so no source file could be parsed: " + probeFailure);
        }
        List<Path> files = sourcesToCheck(context);
        if (files.isEmpty()) {
            return BuildResult.notAttempted("no Ruby source file was detected outside the project's test directories");
        }

        // The helper script is written into the run's artefact store, not into the project: a verifier that
        // leaves files in somebody's working tree has changed what it was asked to measure.
        Path script = RubyScripts.write(context).lintScript();
        List<String> command = new ArrayList<>();
        command.add(RubyInterpreter.ruby(project, context));
        command.add(script.toString());
        files.forEach(file -> command.add(file.toString()));
        ProcessResult lint = context.runner().run(ProcessSpec.builder(command, context.projectRoot())
                .environment(RubyInterpreter.environment(project, context.projectRoot(),
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

        Path gemfile = context.projectRoot().resolve("Gemfile");
        if (Files.isRegularFile(gemfile)) {
            List<String> bundler = RubyInterpreter.bundler(project, context);
            if (bundler.isEmpty()) {
                // RubyInterpreter.bundler always offers the interpreter's own `-S bundle`, so this guards
                // the contract rather than a case seen today.
                return BuildResult.notAttempted("Gemfile exists but no Bundler executable was found "
                        + "(project bin/bundle, vendor/bundle, PATH, or the interpreter's own Bundler), so "
                        + "the project's dependencies could not be installed");
            }
            List<String> installCommand = new ArrayList<>(bundler);
            installCommand.addAll(BUNDLE_INSTALL);
            ProcessResult install = context.runner().run(ProcessSpec.builder(installCommand, context.projectRoot())
                    .environment(RubyInterpreter.environment(project, context.projectRoot(),
                            context.artifacts().artifactsDirectory()))
                    .timeout(context.config().limits().timeout())
                    .maxOutputBytes(1_000_000)
                    .build());
            context.emit("build", "bundle install " + install.describeOutcome());
            if (!install.success()) {
                // Bundler failed to install; nothing was learned about the project's code, and the engine
                // reports a failed build as BLOCKED with Bundler's own log attached.
                return BuildResult.from(install);
            }
        }
        return result;
    }

    /**
     * @return the project's own Ruby files, excluding the bundle tree and the test roots.
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
            for (Path file : RubyProjectDetector.rubyFiles(root)) {
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
                context.emit("build", "ruby syntax check " + line.substring("CWSYNTAX|".length()));
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
