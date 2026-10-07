package dev.codewitness.java.build;

import dev.codewitness.core.model.ProjectInfo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Maven build system. */
public final class MavenBuildSystem implements JavaBuildSystem {

    private static final List<String> BASE = List.of("-B", "-ntp", "-Dstyle.color=never");

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
        goals.add("-Dmaven.test.skip=false");
        goals.add("-DskipTests");
        goals.add("test-compile");
        return goals;
    }

    @Override
    public List<String> testGoals() {
        List<String> goals = new ArrayList<>(BASE);
        // The `test` lifecycle phase, not the `surefire:test` plugin goal. A bare goal invocation
        // resolves inter-module dependencies from the local repository instead of the reactor, so on
        // a multi-module project it would test against whatever stale artifacts happen to be
        // installed there. Running the phase keeps the reactor's own modules on the classpath.
        //
        // `-Dmaven.test.failure.ignore=true` keeps the reactor going after a module whose tests fail.
        // Without it Maven stops at the first failure and skips every later module, so their reports
        // are never produced and the run silently covers only part of the project. CodeWitness reads
        // the failures from the reports itself, so a non-zero exit code is not needed to report them.
        goals.add("-Dmaven.test.failure.ignore=true");
        goals.add("test");
        return goals;
    }

    @Override
    public List<String> targetedTestGoals(List<String> testClassNames, Map<String, String> properties) {
        List<String> goals = new ArrayList<>(BASE);
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
        return goals;
    }

    @Override
    public List<String> lintGoals() {
        List<String> goals = new ArrayList<>(BASE);
        goals.add("-Dmaven.compiler.showWarnings=true");
        goals.add("-Dmaven.compiler.showDeprecation=true");
        goals.add("-Dmaven.compiler.compilerArgument=-Xlint:all");
        goals.add("-DskipTests");
        goals.add("test-compile");
        return goals;
    }

    @Override
    public List<String> testReportDirectories() {
        return List.of("target/surefire-reports", "target/failsafe-reports");
    }
}
