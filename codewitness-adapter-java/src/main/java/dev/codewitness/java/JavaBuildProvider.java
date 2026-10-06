package dev.codewitness.java;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.java.build.BuildOutputParser;
import dev.codewitness.java.build.JavaBuildSystem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Compiles Java projects through their own build tool.
 *
 * <p>The command is exactly what a developer would type, minus colours, and the raw output is kept
 * as evidence. A compiler failure is reported as a failed build - {@code BLOCKED} downstream - never
 * as a code defect.
 */
public final class JavaBuildProvider implements VerificationTools.Compiler {

    private final BuildToolLauncher launcher;
    private final JavaBuildSystem buildSystem;

    public JavaBuildProvider(BuildToolLauncher launcher, JavaBuildSystem buildSystem) {
        this.launcher = launcher;
        this.buildSystem = buildSystem;
    }

    @Override
    public String id() {
        return "java-build-" + buildSystem.id();
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.buildSystem().id().equals(buildSystem.id());
    }

    @Override
    public BuildResult build(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        List<String> executable = launcher.resolve(project, buildSystem.buildRoot(project));
        if (executable == null) {
            return BuildResult.notAttempted("no " + buildSystem.id() + " executable found: neither a project "
                    + "wrapper, nor MAVEN_HOME/M2_HOME/GRADLE_HOME, nor PATH provides one");
        }

        List<String> command = BuildToolLauncher.command(executable, buildSystem.compileGoals());
        ProcessSpec spec = ProcessSpec.builder(command, buildSystem.buildRoot(project))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(1_000_000)
                .build();
        ProcessResult result = context.runner().run(spec);
        BuildResult build = BuildOutputParser.toBuildResult(result, context.projectRoot());
        if (!result.started()) {
            return BuildResult.notAttempted(result.failureReason());
        }
        return build;
    }

    /** @return the artifacts a successful build produced, for the evidence record. */
    public static List<Path> findArtifacts(ProjectRef project) {
        List<Path> artifacts = new ArrayList<>();
        for (String relative : List.of("target/classes", "build/classes/java/main")) {
            Path directory = project.root().resolve(relative);
            if (Files.isDirectory(directory)) {
                artifacts.add(directory);
            }
        }
        return artifacts;
    }
}
