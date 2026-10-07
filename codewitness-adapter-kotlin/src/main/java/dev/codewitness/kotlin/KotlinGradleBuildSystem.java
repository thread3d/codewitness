package dev.codewitness.kotlin;

import dev.codewitness.core.model.ProjectInfo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Gradle with the Kotlin Gradle plugin.
 *
 * <p>The Kotlin plugin makes {@code compileKotlin} part of {@code classes} and {@code compileTestKotlin}
 * part of {@code testClasses}, so compiling is {@code testClasses} here exactly as it is for Java. Two
 * Gradle behaviours matter afterwards: Gradle does not run a task it considers up to date, so a targeted
 * run always uses {@code --rerun-tasks} - otherwise a generated test that was deleted and rewritten
 * would be reported as passing without ever having run - and Gradle does not forward {@code -D} system
 * properties to the test JVM, so generated tests read their seed from the environment as well.
 */
public final class KotlinGradleBuildSystem implements KotlinBuildSystem {

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
        return List.copyOf(goals);
    }

    @Override
    public List<String> testGoals() {
        List<String> goals = new ArrayList<>(BASE);
        goals.add("test");
        return List.copyOf(goals);
    }

    @Override
    public List<String> mutationGoals() {
        // Gradle already fails `test` when a test fails, so this is the plain test goal.
        return testGoals();
    }

    @Override
    public List<String> targetedTestGoals(List<String> testClassNames, Map<String, String> properties) {
        List<String> goals = new ArrayList<>(BASE);
        goals.add("test");
        goals.add("--rerun-tasks");
        for (String testClass : testClassNames) {
            goals.add("--tests");
            goals.add(testClass);
        }
        goals.add("-i");
        return List.copyOf(goals);
    }

    @Override
    public List<String> lintGoals() {
        List<String> goals = new ArrayList<>(BASE);
        goals.add("compileKotlin");
        goals.add("--rerun-tasks");
        return List.copyOf(goals);
    }

    @Override
    public List<String> testReportDirectories() {
        return List.of("build/test-results/test", "build/test-results/testDebugUnitTest");
    }
}
