package dev.codewitness.java.build;

import dev.codewitness.core.model.ProjectInfo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Gradle build system.
 *
 * <p>Two differences from Maven are worth knowing about. Gradle does not run tests when it considers
 * the task up to date, so the targeted test command always uses {@code --rerun-tasks} for the test
 * task; and Gradle does not forward {@code -D} system properties to the test JVM automatically, so
 * generated tests read their seed from the environment as well as from a system property.
 */
public final class GradleBuildSystem implements JavaBuildSystem {

    private static final List<String> BASE = List.of("--console=plain", "--no-daemon");

    @Override
    public String id() {
        return "gradle";
    }

    @Override
    public Path buildRoot(ProjectInfo project) {
        return project.buildRoot();
    }

    @Override
    public List<String> compileGoals() {
        List<String> goals = new ArrayList<>(BASE);
        goals.add("testClasses");
        return goals;
    }

    @Override
    public List<String> testGoals() {
        List<String> goals = new ArrayList<>(BASE);
        goals.add("test");
        return goals;
    }

    @Override
    public List<String> targetedTestGoals(List<String> testClassNames, Map<String, String> properties) {
        List<String> goals = new ArrayList<>(BASE);
        goals.add("test");
        goals.add("--rerun-tasks");
        goals.add("-Dcodewitness.seed=" + properties.getOrDefault("codewitness.seed", ""));
        for (String testClass : testClassNames) {
            goals.add("--tests");
            goals.add(testClass);
        }
        goals.add("-i");
        return goals;
    }

    @Override
    public List<String> lintGoals() {
        List<String> goals = new ArrayList<>(BASE);
        goals.add("compileJava");
        goals.add("--rerun-tasks");
        goals.add("-Dorg.gradle.jvmargs=-Xlint:all");
        return goals;
    }

    @Override
    public List<String> testReportDirectories() {
        return List.of("build/test-results/test", "build/test-results/testDebugUnitTest");
    }
}
