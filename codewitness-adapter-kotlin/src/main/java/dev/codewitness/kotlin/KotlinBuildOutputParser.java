package dev.codewitness.kotlin;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Kotlin compiler diagnostics out of Maven and Gradle output.
 *
 * <p>The Kotlin compiler has its own output format, and reading it is the only way a Kotlin compile
 * failure becomes a compiler diagnostic rather than an unlabelled wall of text. The shapes are:
 *
 * <ul>
 *   <li>{@code [ERROR] file:///path/File.kt:4:23 Return type mismatch: ...} - kotlin-maven-plugin 2.x,
 *       which prints the file as a URI and does not repeat the word "error" inside the message;</li>
 *   <li>{@code [ERROR] /path/File.kt: (4, 23): message} - older kotlin-maven-plugin releases, which
 *       bracket the position and end it with a colon;</li>
 *   <li>{@code e: file:///path/File.kt:4:23 message} - the Gradle Kotlin plugin and the command line
 *       compiler, which prefix the severity with a single letter;</li>
 *   <li>{@code /path/File.kt:4:23: error: message} - {@code kotlinc} itself.</li>
 * </ul>
 *
 * <p>A line that matches none of these is not a diagnostic and is left in the raw output, where the
 * evidence record keeps it.
 */
public final class KotlinBuildOutputParser {

    /** {@code [ERROR] file:///path/File.kt:4:23 message} - kotlin-maven-plugin 2.x. */
    private static final Pattern MAVEN_URI = Pattern.compile(
            "^\\[(ERROR|WARNING|INFO)]\\s+(?:file://)?(.+?\\.kt[s]?):(\\d+):(\\d+):?\\s*(.*)$");

    /** {@code [ERROR] /path/File.kt: (4, 23): message} - older kotlin-maven-plugin releases. */
    private static final Pattern MAVEN_BRACKET = Pattern.compile(
            "^\\[(ERROR|WARNING|INFO)]\\s+(?:file://)?(.+?\\.kt[s]?):\\s*\\((\\d+),\\s*(\\d+)\\):?\\s*(.*)$");

    /** {@code e: file:///path/File.kt:4:23 message} - Gradle and the command line compiler. */
    private static final Pattern GRADLE_LETTER = Pattern.compile(
            "^([ewi]):\\s+(?:file://)?(.+?\\.kt[s]?):(\\d+):(\\d+):?\\s*(.*)$");

    /** {@code /path/File.kt:4:23: error: message} - kotlinc itself. */
    private static final Pattern KOTLINC = Pattern.compile(
            "^(?:file://)?(.+?\\.kt[s]?):(\\d+):(\\d+):\\s*(error|warning):\\s*(.*)$");

    private KotlinBuildOutputParser() {
    }

    /** @return every compiler diagnostic found in the output, in order, de-duplicated. */
    public static List<CompilerDiagnostic> parse(String output, Path projectRoot) {
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        if (output == null) {
            return diagnostics;
        }
        for (String rawLine : output.split("\\R")) {
            String line = rawLine.strip();
            Matcher bracket = MAVEN_BRACKET.matcher(line);
            if (bracket.matches()) {
                add(diagnostics, projectRoot, severityOf(bracket.group(1)), bracket.group(2), bracket.group(3),
                        bracket.group(4), bracket.group(5));
                continue;
            }
            Matcher maven = MAVEN_URI.matcher(line);
            if (maven.matches()) {
                add(diagnostics, projectRoot, severityOf(maven.group(1)), maven.group(2), maven.group(3),
                        maven.group(4), maven.group(5));
                continue;
            }
            Matcher gradle = GRADLE_LETTER.matcher(line);
            if (gradle.matches()) {
                add(diagnostics, projectRoot, severityOf(gradle.group(1)), gradle.group(2), gradle.group(3),
                        gradle.group(4), gradle.group(5));
                continue;
            }
            Matcher kotlinc = KOTLINC.matcher(line);
            if (kotlinc.matches()) {
                add(diagnostics, projectRoot, severityOf(kotlinc.group(4)), kotlinc.group(1), kotlinc.group(2),
                        kotlinc.group(3), kotlinc.group(5));
            }
        }
        return deduplicate(diagnostics);
    }

    private static DiagnosticSeverity severityOf(String text) {
        return switch (text.toUpperCase(java.util.Locale.ROOT)) {
            case "ERROR", "E" -> DiagnosticSeverity.ERROR;
            case "WARNING", "W" -> DiagnosticSeverity.WARNING;
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
        diagnostics.add(new CompilerDiagnostic(severity, relativize(projectRoot, file), toInteger(line),
                toInteger(column), message == null ? "" : message.strip(), null));
    }

    private static String relativize(Path projectRoot, String file) {
        if (file == null) {
            return null;
        }
        String cleaned = file.startsWith("file://") ? file.substring("file://".length()) : file;
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
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException _) {
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
    public static BuildResult toBuildResult(ProcessResult process, Path projectRoot) {
        return BuildResult.from(process, parse(process.combinedOutput(), projectRoot));
    }

    /** @return the default build system for a project, or {@code null} when none is supported. */
    public static KotlinBuildSystem forProject(dev.codewitness.core.model.ProjectInfo project) {
        if (project.buildSystem().equals(dev.codewitness.core.model.BuildSystemId.MAVEN)) {
            return new KotlinMavenBuildSystem();
        }
        if (project.buildSystem().equals(dev.codewitness.core.model.BuildSystemId.GRADLE)) {
            return new KotlinGradleBuildSystem();
        }
        return null;
    }
}
