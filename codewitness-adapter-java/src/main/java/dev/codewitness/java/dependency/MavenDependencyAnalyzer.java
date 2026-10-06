package dev.codewitness.java.dependency;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.java.BuildToolLauncher;

import java.util.ArrayList;
import java.util.List;

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
        return project.buildSystem().id().equals("maven");
    }

    @Override
    public DependencyReport analyseDependencies(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        List<String> executable = launcher.resolve(project, project.buildRoot());
        if (executable == null) {
            return DependencyReport.notAnalysed(id(), "no Maven executable found");
        }
        List<String> goals = new ArrayList<>(executable);
        goals.add("-B");
        goals.add("-ntp");
        goals.add("dependency:list");
        goals.add("-DincludeScope=runtime");
        goals.add("-DoutputAbsoluteArtifactFilename=false");
        ProcessResult result = context.runner().run(ProcessSpec.builder(goals, project.buildRoot())
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(500_000)
                .build());
        if (!result.success()) {
            return DependencyReport.notAnalysed(id(), "dependency:list failed (" + result.describeOutcome() + ")");
        }
        List<String> dependencies = new ArrayList<>();
        for (String line : result.combinedOutput().split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("[INFO]") && trimmed.contains(":")
                    && trimmed.contains(":jar") || trimmed.contains(":pom") && trimmed.startsWith("[INFO]")) {
                String value = trimmed.substring("[INFO]".length()).strip();
                if (value.contains(":") && value.split(":").length >= 4 && !value.startsWith("Building")
                        && !value.startsWith("The following files")) {
                    dependencies.add(value);
                }
            }
        }
        return new DependencyReport(true, id(),
                "inventory only: no vulnerability database was consulted, so this report does NOT show whether any "
                        + "dependency has known advisories",
                dependencies.size(), dependencies, List.of());
    }
}
