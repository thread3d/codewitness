package dev.codewitness.swift;

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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles a Swift package with Swift Package Manager.
 *
 * <p>The command is {@code swift build --build-tests}, which compiles the package's own targets and its
 * test targets. Compiling the tests is deliberate: a test that does not compile is a package problem,
 * and the engine reports a failed build as {@code BLOCKED} rather than letting a compile error
 * masquerade as a test failure. Swift has a real compilation step, so unlike PHP or Ruby there is no
 * "parse the file with the interpreter" phase - the compiler's own diagnostics are the build.
 *
 * <p>Nothing is installed and the network is governed by {@code sandbox.allow_network}. A failed build
 * is a statement about the build, never about the correctness of the code. SwiftPM's own nested sandbox
 * is disabled and its caches are redirected inside the run; {@link SwiftToolchain} explains why.
 */
public final class SwiftBuildProvider implements VerificationTools.Compiler {

    @Override
    public String id() {
        return "swiftpm-build";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.SWIFT);
    }

    @Override
    public BuildResult build(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        if (!SwiftProjectDetector.looksLikeSwiftProject(context.projectRoot())) {
            return BuildResult.notAttempted("no Package.swift was found, so SwiftPM cannot build this tree");
        }
        String probeFailure = project.attributes().get(SwiftToolchain.ATTR_PROBE_FAILURE);
        if (probeFailure != null) {
            return BuildResult.notAttempted("the Swift toolchain probe failed: " + probeFailure);
        }
        String swift = project.attributes().get(SwiftToolchain.ATTR_SWIFT);
        if (swift == null || !Files.isExecutable(java.nio.file.Path.of(swift))) {
            return BuildResult.notAttempted("no swift executable was resolved for this run");
        }
        SwiftToolchain.prepareDirectories(context);
        List<String> command = SwiftToolchain.command(swift, context, List.of("build", "--build-tests"));
        ProcessResult result = context.runner().run(ProcessSpec.builder(command, context.projectRoot())
                .environment(SwiftToolchain.environment(context))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(1_000_000)
                .build());
        if (!result.started()) {
            context.emit("build", "swift build could not be started: " + result.failureReason());
            return BuildResult.notAttempted(result.failureReason());
        }
        List<CompilerDiagnostic> diagnostics = diagnostics(result.combinedOutput(), context.projectRoot());
        context.emit("build", "swift build " + result.describeOutcome() + " with "
                + diagnostics.size() + " diagnostic(s)");
        return BuildResult.from(result, diagnostics);
    }

    /**
     * @param output the compiler's combined output
     * @param root the project root, so a path inside it is reported relative to the project
     * @return the diagnostics the compiler printed, in order
     *
     * <p>Swift's canonical diagnostic line is {@code path:line:column: severity: message}. The compiler
     * also prints module-level lines with no location ({@code error: 'Ledger': Invalid manifest ...}),
     * and it annotates each error with a source snippet and a caret line whose own text contains
     * {@code error:}; only the canonical line is read, so a fragment of the snippet is never mistaken for
     * a second diagnostic.
     */
    static List<CompilerDiagnostic> diagnostics(String output, java.nio.file.Path root) {
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return List.copyOf(diagnostics);
        }
        for (String line : output.split("\\R")) {
            Matcher located = LOCATED.matcher(line);
            if (located.matches()) {
                diagnostics.add(new CompilerDiagnostic(
                        severity(located.group("severity")),
                        relative(root, located.group("file")),
                        integer(located.group("line")),
                        integer(located.group("column")),
                        located.group("message").strip(),
                        null));
                continue;
            }
            Matcher bare = BARE.matcher(line.strip());
            if (bare.matches()) {
                diagnostics.add(new CompilerDiagnostic(
                        severity(bare.group("severity")), null, null, null, bare.group("message").strip(), null));
            }
        }
        return List.copyOf(diagnostics);
    }

    /** {@code /path/File.swift:12:9: error: message}. */
    private static final Pattern LOCATED = Pattern.compile(
            "^(?<file>.+?):(?<line>\\d+):(?<column>\\d+):\\s*(?<severity>error|warning|note):\\s*(?<message>.*)$");

    /** A module-level line with no location: {@code error: message}. */
    private static final Pattern BARE = Pattern.compile(
            "^(?<severity>error|warning|note):\\s*(?<message>.+)$");

    private static DiagnosticSeverity severity(String value) {
        return switch (value) {
            case "error" -> DiagnosticSeverity.ERROR;
            case "warning" -> DiagnosticSeverity.WARNING;
            case "note" -> DiagnosticSeverity.NOTE;
            default -> DiagnosticSeverity.OTHER;
        };
    }

    private static Integer integer(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException _) {
            return null;
        }
    }

    /** @return the path relative to the project root when it lies under it, otherwise as printed. */
    private static String relative(java.nio.file.Path root, String file) {
        if (root == null || file == null) {
            return file;
        }
        try {
            java.nio.file.Path path = java.nio.file.Path.of(file);
            if (path.isAbsolute() && path.normalize().startsWith(root.normalize())) {
                return root.normalize().relativize(path.normalize()).toString();
            }
        } catch (RuntimeException _) {
            // not a path this platform can parse: it is reported as it was written
        }
        return file;
    }
}
