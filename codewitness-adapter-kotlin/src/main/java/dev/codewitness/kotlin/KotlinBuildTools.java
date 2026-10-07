package dev.codewitness.kotlin;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessRunner;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.ProjectInfo;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves and invokes the build tool a Kotlin project is built with.
 *
 * <p>Resolution order is: the project's own wrapper (so the project's pinned build tool version is used),
 * then {@code MAVEN_HOME}/{@code M2_HOME}/{@code GRADLE_HOME}, then {@code PATH}. That order is the reason a
 * Kotlin project which ships a {@code ./gradlew} is built by exactly the Gradle release its author pinned,
 * rather than by whichever one happens to be installed.
 *
 * <p>An absent build tool is reported, never thrown: it is an infrastructure problem, and the affected
 * requirements stay unverified rather than being reported as defects.
 */
public final class KotlinBuildTools {

    private final ProcessRunner runner;
    private final String mavenHome;
    private final String gradleHome;

    public KotlinBuildTools(ProcessRunner runner) {
        this.runner = runner;
        this.mavenHome = firstNonBlank(System.getenv("MAVEN_HOME"), System.getenv("M2_HOME"));
        this.gradleHome = System.getenv("GRADLE_HOME");
    }

    /**
     * Resolves the executable to use.
     *
     * @param project   project being built
     * @param directory directory the command will run in (inside the workspace)
     * @return argv prefix, or {@code null} when no build tool could be found
     */
    public List<String> resolve(ProjectInfo project, Path directory) {
        boolean gradle = project.buildSystem().equals(BuildSystemId.GRADLE);
        String wrapperName = gradle ? "gradlew" : "mvnw";

        // A copied workspace may have lost the wrapper's executable bit, so the wrapper is invoked
        // through the shell when it is not executable.
        Path wrapper = project.buildWrapper().map(directory::resolve).orElseGet(() -> directory.resolve(wrapperName));
        if (Files.isRegularFile(wrapper)) {
            if (Files.isExecutable(wrapper)) {
                return List.of(wrapper.toString());
            }
            return List.of("/bin/sh", wrapper.toString());
        }

        String home = gradle ? gradleHome : mavenHome;
        if (home != null) {
            Path executable = Path.of(home, "bin", gradle ? "gradle" : "mvn");
            if (Files.isExecutable(executable)) {
                return List.of(executable.toString());
            }
        }

        String onPath = findOnPath(gradle ? "gradle" : "mvn");
        return onPath == null ? null : List.of(onPath);
    }

    /** @return the version string of the build tool, or {@code "unknown"}. */
    public String version(List<String> executable, Path directory, Duration timeout) {
        if (executable == null) {
            return "unknown";
        }
        List<String> command = new ArrayList<>(executable);
        command.add("--version");
        ProcessResult result = runner.run(ProcessSpec.builder(command, directory).timeout(timeout).build());
        if (!result.success()) {
            return "unknown";
        }
        String line = result.stdout().lines().findFirst().orElse("").trim();
        return line.isEmpty() ? "unknown" : line;
    }

    /** @return the command that would run the given goals, for reporting and reproduction. */
    public static List<String> command(List<String> executable, List<String> goals) {
        List<String> command = new ArrayList<>(executable);
        command.addAll(goals);
        return command;
    }

    private static String findOnPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String element : path.split(File.pathSeparator)) {
            if (element.isBlank()) {
                continue;
            }
            Path candidate = Path.of(element, name);
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
            Path windowsCandidate = Path.of(element, name + ".cmd");
            if (Files.isExecutable(windowsCandidate)) {
                return windowsCandidate.toString();
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
