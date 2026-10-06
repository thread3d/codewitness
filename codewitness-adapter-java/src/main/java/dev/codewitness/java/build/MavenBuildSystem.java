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
        goals.add("surefire:test");
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
