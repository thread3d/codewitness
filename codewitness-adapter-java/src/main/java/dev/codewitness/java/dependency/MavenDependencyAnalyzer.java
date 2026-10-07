package dev.codewitness.java.dependency;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.AdvisoryScan;
import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.PackageCoordinate;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.java.BuildToolLauncher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dependency inventory for Maven projects.
 *
 * <p><strong>Limitation, stated plainly:</strong> this provider lists the resolved dependencies. It
 * does <em>not</em> consult a vulnerability database, because the MVP has no network integration for
 * one. The report therefore says "inventory only" and never claims that a project has no vulnerable
 * dependencies.
 */
public final class MavenDependencyAnalyzer implements VerificationTools.DependencyAnalyzer {

    private final BuildToolLauncher launcher;

    public MavenDependencyAnalyzer(BuildToolLauncher launcher) {
        this.launcher = launcher;
    }

    @Override
    public String id() {
        return "maven-dependencies";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(dev.codewitness.core.model.LanguageId.JAVA)
                && project.buildSystem().id().equals("maven");
    }

    @Override
    public DependencyReport analyseDependencies(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        List<String> goals = dependencyCommand(context);
        if (goals.isEmpty()) {
            return DependencyReport.notAnalysed(id(), "no Maven executable found");
        }
        ProcessResult result = context.runner().run(ProcessSpec.builder(goals, project.buildRoot())
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(500_000)
                .build());
        if (!result.success()) {
            // The reason has to travel with the result: this evidence is often the only trace of a
            // dependency phase that produced nothing, and "exit code 1" on its own is not diagnosable.
            return DependencyReport.notAnalysed(id(), "dependency:list failed (" + result.describeOutcome()
                    + "); last output: " + lastLines(result.combinedOutput(), 6));
        }
        Map<String, List<String>> byModule = parseDependenciesByModule(result.combinedOutput());
        List<String> dependencies = parseDependencies(result.combinedOutput());
        return new DependencyReport(true, id(),
                "inventory only: the dependencies Maven resolves for this project; whether any of them has a "
                        + "known advisory is recorded by the advisory scan, which is a separate question from "
                        + "which dependencies exist",
                dependencies.size(), dependencies, byModule, PackageCoordinate.parseMaven(dependencies),
                List.of(), AdvisoryScan.notPerformed());
    }

    /**
     * Splits {@code dependency:list} output into one coordinate list per module.
     *
     * <p>Maven prints a {@code --- dependency:<version>:list (default-cli) @ <module> ---} banner before
     * each module's block, and a module's blocks appear in reactor order. Attributing a coordinate to the
     * module that resolves it is what lets an advisory be tied to the code it actually ships with, so a
     * banner that cannot be read simply ends the current block rather than being guessed at.
     */
    static Map<String, List<String>> parseDependenciesByModule(String output) {
        Map<String, List<String>> byModule = new LinkedHashMap<>();
        String current = null;
        Set<String> seenForModule = new LinkedHashSet<>();
        for (String line : output.split("\\R")) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("[INFO]")) {
                continue;
            }
            String module = moduleBanner(trimmed);
            if (module != null) {
                current = module;
                seenForModule = new LinkedHashSet<>();
                byModule.computeIfAbsent(current, key -> new ArrayList<>());
                continue;
            }
            if (current == null) {
                continue;
            }
            String value = coordinateOf(trimmed);
            if (value != null && seenForModule.add(value)) {
                byModule.get(current).add(value);
            }
        }
        Map<String, List<String>> immutable = new LinkedHashMap<>();
        byModule.forEach((module, coordinates) -> immutable.put(module, List.copyOf(coordinates)));
        return Map.copyOf(immutable);
    }

    /** @return the module name in a plugin banner line, or {@code null} when the line is not one. */
    private static String moduleBanner(String trimmedLine) {
        int marker = trimmedLine.indexOf("(default-cli) @ ");
        if (marker < 0) {
            return null;
        }
        String module = trimmedLine.substring(marker + "(default-cli) @ ".length()).strip();
        int end = module.indexOf(' ');
        if (end > 0) {
            module = module.substring(0, end);
        }
        // Maven appends dashes around the banner; a module name never contains them at the edges.
        module = module.replaceAll("^-+|-+$", "").strip();
        return module.isEmpty() ? null : module;
    }

    /** @return the coordinate on a resolved-artifact line, with module-path notes removed. */
    private static String coordinateOf(String trimmedLine) {
        String value = trimmedLine.substring("[INFO]".length()).strip();
        if (!isResolvedArtifact(value)) {
            return null;
        }
        int moduleNote = value.indexOf(" -- ");
        return moduleNote > 0 ? value.substring(0, moduleNote).strip() : value;
    }

    /**
     * Extracts the resolved coordinates from {@code dependency:list} output.
     *
     * <p>The command runs once per module, so a coordinate appears again for every module that
     * depends on it; the inventory is the set of distinct coordinates, in the order they were first
     * seen. Maven also appends module-path information to some lines, which is not part of the
     * coordinate.
     */
    static List<String> parseDependencies(String output) {
        Set<String> coordinates = new LinkedHashSet<>();
        for (String line : output.split("\\R")) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("[INFO]")) {
                continue;
            }
            String value = coordinateOf(trimmed);
            if (value != null) {
                coordinates.add(value);
            }
        }
        return List.copyOf(coordinates);
    }

    /**
     * @return whether an {@code [INFO]}-stripped line names a resolved artifact rather than build
     *     output that happens to contain colons
     */
    private static boolean isResolvedArtifact(String value) {
        if (value.startsWith("Building") || value.startsWith("The following files")) {
            return false;
        }
        return value.contains(":") && value.split(":").length >= 4
                && (value.contains(":jar") || value.contains(":pom"));
    }

    @Override
    public List<String> dependencyCommand(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        List<String> executable = launcher.resolve(project, project.buildRoot());
        if (executable == null) {
            return List.of();
        }
        List<String> goals = new ArrayList<>(executable);
        goals.add("-B");
        goals.add("-ntp");
        // One lifecycle phase before the goal, and this is load-bearing. `dependency:list` alone is a
        // goal-only invocation, and such an invocation cannot resolve the modules of a reactor: it
        // looks for their jars in the local repository, where nothing puts them until someone runs
        // `install`. On a multi-module project it therefore fails outright with "was not found ...
        // during a previous attempt". Running a phase first makes Maven resolve those modules from
        // the reactor's own output - which is also the more truthful inventory, because it describes
        // the code as it is rather than as it was last installed.
        goals.add("compile");
        goals.add("dependency:list");
        goals.add("-DincludeScope=runtime");
        goals.add("-DoutputAbsoluteArtifactFilename=false");
        return List.copyOf(goals);
    }

    /** @return the last {@code count} lines of a tool's output, joined for a one-line note. */
    private static String lastLines(String text, int count) {
        if (text == null || text.isBlank()) {
            return "(no output)";
        }
        String[] lines = text.strip().split("\\R");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, lines.length - count); i < lines.length; i++) {
            sb.append(lines[i].strip()).append(" | ");
        }
        return sb.toString();
    }
}
