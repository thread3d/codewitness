package dev.codewitness.kotlin;

import dev.codewitness.core.model.ProjectInfo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Maven with the {@code kotlin-maven-plugin}.
 *
 * <p>Three details are Kotlin's rather than Java's. Compiling means {@code test-compile}, because that is
 * the phase the plugin binds its {@code compile} and {@code test-compile} executions to; a Kotlin file the
 * plugin never sees is a file nothing checks. The Kotlin compiler's in-process execution strategy is asked
 * for explicitly: the plugin otherwise starts a compiler daemon that outlives the run by hours and holds
 * file handles inside the disposable workspace. And the test goal keeps
 * {@code -Dmaven.test.failure.ignore=true}, so that a module whose tests fail does not stop the reactor
 * before the modules after it have produced their reports - CodeWitness reads failures from the reports,
 * not from the exit code.
 */
public final class KotlinMavenBuildSystem implements KotlinBuildSystem {

    private static final List<String> BASE = List.of("-B", "-ntp", "-Dstyle.color=never");

    /**
     * The Kotlin compiler's own execution strategy.
     *
     * <p>{@code in-process} compiles inside the Maven JVM. The default, {@code daemon}, starts a compiler
     * daemon that keeps running for hours after the run and reads sources from the workspace that the run
     * is about to delete; a verifier should not leave that behind.
     */
    private static final String IN_PROCESS = "-Dkotlin.compiler.execution.strategy=in-process";

    @Override
    public String id() {
        return "maven";
    }

    @Override
    public Path buildRoot(ProjectInfo project) {
        return project.buildRoot();
    }

    @Override
    public List<String> compileGoals() {
        List<String> goals = new ArrayList<>(BASE);
        goals.add(IN_PROCESS);
        goals.add("-DskipTests");
        goals.add("test-compile");
        return List.copyOf(goals);
    }

    @Override
    public List<String> testGoals() {
        List<String> goals = new ArrayList<>(BASE);
        goals.add(IN_PROCESS);
        // The `test` lifecycle phase, not the `surefire:test` plugin goal: a bare goal invocation resolves
        // inter-module dependencies from the local repository instead of the reactor, so on a multi-module
        // project it would test against whatever stale artifacts happen to be installed there.
        goals.add("-Dmaven.test.failure.ignore=true");
        goals.add("test");
        return List.copyOf(goals);
    }

    @Override
    public List<String> mutationGoals() {
        List<String> goals = new ArrayList<>(BASE);
        goals.add(IN_PROCESS);
        // No -Dmaven.test.failure.ignore here: see KotlinBuildSystem.mutationGoals. The exit status is the
        // only signal a mutation run has, and this is the one invocation where it has to mean something.
        goals.add("test");
        return List.copyOf(goals);
    }

    @Override
    public List<String> targetedTestGoals(List<String> testClassNames, Map<String, String> properties) {
        List<String> goals = new ArrayList<>(BASE);
        goals.add(IN_PROCESS);
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            goals.add("-D" + entry.getKey() + "=" + entry.getValue());
        }
        goals.add("-Dtest=" + testClassNames.stream()
                .map(name -> name.substring(name.lastIndexOf('.') + 1))
                .collect(Collectors.joining(",")));
        goals.add("-DfailIfNoSpecifiedTests=false");
        goals.add("-Dsurefire.failIfNoSpecifiedTests=false");
        goals.add("-Dmaven.test.failure.ignore=true");
        goals.add("test");
        return List.copyOf(goals);
    }

    @Override
    public List<String> lintGoals() {
        List<String> goals = new ArrayList<>(BASE);
        goals.add(IN_PROCESS);
        goals.add("-DskipTests");
        goals.add("test-compile");
        return List.copyOf(goals);
    }

    @Override
    public List<String> testReportDirectories() {
        return List.of("target/surefire-reports", "target/failsafe-reports");
    }
}
