package dev.codewitness.java.build;

import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;
import dev.codewitness.core.model.ProjectInfo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses compiler diagnostics out of Maven and Gradle output. */
public final class BuildOutputParser {

    /** {@code [ERROR] /path/File.java:[12,34] message} - Maven with javac. */
    private static final Pattern MAVEN_DIAGNOSTIC = Pattern.compile(
            "^\\[(ERROR|WARNING|INFO)]\\s+(.*?\\.java):\\[(\\d+)(?:,(\\d+))?]\\s*(.*)$");

    /** {@code /path/File.java:12: error: message} - javac and Gradle's compiler output. */
    private static final Pattern JAVAC_DIAGNOSTIC = Pattern.compile(
            "^(.*?\\.java):(\\d+):\\s*(error|warning|note):\\s*(.*)$");

    /** {@code e: file:///path/File.java:12:34 message} - Kotlin/Gradle compiler output. */
    private static final Pattern GRADLE_DIAGNOSTIC = Pattern.compile(
            "^([ewi]):\\s+file://(.*?\\.java):(\\d+):(\\d+)\\s+(.*)$");

    private BuildOutputParser() {
    }

    /** @return every compiler diagnostic found in the output, in order, de-duplicated. */
    public static List<CompilerDiagnostic> parse(String output, Path projectRoot) {
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        if (output == null) {
            return diagnostics;
        }
        for (String rawLine : output.split("\\R")) {
            String line = rawLine.strip();
            Matcher maven = MAVEN_DIAGNOSTIC.matcher(line);
            if (maven.matches()) {
                add(diagnostics, projectRoot, maven.group(1).equals("ERROR") ? DiagnosticSeverity.ERROR
                                : maven.group(1).equals("WARNING") ? DiagnosticSeverity.WARNING : DiagnosticSeverity.NOTE,
                        maven.group(2), maven.group(3), maven.group(4), maven.group(5));
                continue;
            }
            Matcher javac = JAVAC_DIAGNOSTIC.matcher(line);
            if (javac.matches()) {
                add(diagnostics, projectRoot, severityOf(javac.group(3)), javac.group(1), javac.group(2), null,
                        javac.group(4));
                continue;
            }
            Matcher gradle = GRADLE_DIAGNOSTIC.matcher(line);
            if (gradle.matches()) {
                DiagnosticSeverity severity = switch (gradle.group(1)) {
                    case "e" -> DiagnosticSeverity.ERROR;
                    case "w" -> DiagnosticSeverity.WARNING;
                    default -> DiagnosticSeverity.NOTE;
                };
                add(diagnostics, projectRoot, severity, gradle.group(2), gradle.group(3), gradle.group(4),
                        gradle.group(5));
            }
        }
        return deduplicate(diagnostics);
    }

    private static DiagnosticSeverity severityOf(String text) {
        return switch (text) {
            case "error" -> DiagnosticSeverity.ERROR;
            case "warning" -> DiagnosticSeverity.WARNING;
            default -> DiagnosticSeverity.NOTE;
        };
    }

    private static void add(
            List<CompilerDiagnostic> diagnostics,
            Path projectRoot,
            DiagnosticSeverity severity,
            String file,
            String line,
            String column,
            String message) {
        String relative = relativize(projectRoot, file);
        diagnostics.add(new CompilerDiagnostic(severity, relative, toInteger(line), toInteger(column),
                message == null ? "" : message.strip(), null));
    }

    private static String relativize(Path projectRoot, String file) {
        if (file == null) {
            return null;
        }
        String cleaned = file;
        if (cleaned.startsWith("file://")) {
            cleaned = cleaned.substring("file://".length());
        }
        try {
            Path path = Path.of(cleaned);
            if (projectRoot != null && path.isAbsolute() && path.startsWith(projectRoot)) {
                return projectRoot.relativize(path).toString();
            }
        } catch (RuntimeException _) {
            // not a usable path: keep the original text
        }
        return cleaned;
    }

    private static Integer toInteger(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<CompilerDiagnostic> deduplicate(List<CompilerDiagnostic> diagnostics) {
        List<CompilerDiagnostic> unique = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (CompilerDiagnostic diagnostic : diagnostics) {
            String key = diagnostic.severity() + "|" + diagnostic.file() + "|" + diagnostic.line() + "|"
                    + diagnostic.message();
            if (!seen.contains(key)) {
                seen.add(key);
                unique.add(diagnostic);
            }
        }
        return unique;
    }

    /** Builds a {@link BuildResult} from raw process data. */
    public static BuildResult toBuildResult(
            dev.codewitness.core.exec.ProcessResult process, Path projectRoot) {
        return BuildResult.from(process, parse(process.combinedOutput(), projectRoot));
    }

    /** @return the default build system for a project, or {@code null} when it is unsupported. */
    public static JavaBuildSystem forProject(ProjectInfo project) {
        if (project.buildSystem().equals(BuildSystemId.MAVEN)) {
            return new MavenBuildSystem();
        }
        if (project.buildSystem().equals(BuildSystemId.GRADLE)) {
            return new GradleBuildSystem();
        }
        return null;
    }
}
