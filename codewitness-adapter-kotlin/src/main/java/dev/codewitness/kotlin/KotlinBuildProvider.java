package dev.codewitness.kotlin;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.util.List;

/**
 * Compiles Kotlin projects through their own build tool.
 *
 * <p>The command is the one a developer would type - {@code ./gradlew testClasses} or {@code mvnw -DskipTests
 * test-compile} - and its raw output is kept as evidence. A compilation failure is reported as a failed
 * build, which the engine turns into {@code BLOCKED}: a Kotlin file that does not compile says nothing about
 * whether the requirement behind it holds.
 *
 * <p>Support is claimed by language as well as by build system. A Kotlin project and a Java project can both
 * use Gradle, and the two adapters must not answer for each other's sources.
 */
public final class KotlinBuildProvider implements VerificationTools.Compiler {

    private final KotlinBuildTools tools;
    private final KotlinBuildSystem buildSystem;

    public KotlinBuildProvider(KotlinBuildTools tools, KotlinBuildSystem buildSystem) {
        this.tools = tools;
        this.buildSystem = buildSystem;
    }

    @Override
    public String id() {
        return "kotlin-build-" + buildSystem.id();
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.KOTLIN)
                && project.buildSystem().id().equals(buildSystem.id());
    }

    @Override
    public BuildResult build(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        List<String> executable = tools.resolve(project, buildSystem.buildRoot(project));
        if (executable == null) {
            return BuildResult.notAttempted("no " + buildSystem.id() + " executable found: neither a project "
                    + "wrapper, nor MAVEN_HOME/M2_HOME/GRADLE_HOME, nor PATH provides one");
        }

        List<String> command = KotlinBuildTools.command(executable, buildSystem.compileGoals());
        ProcessResult result = context.runner().run(ProcessSpec.builder(command, buildSystem.buildRoot(project))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(1_000_000)
                .build());
        if (!result.started()) {
            return BuildResult.notAttempted(result.failureReason());
        }
        return KotlinBuildOutputParser.toBuildResult(result, context.projectRoot());
    }
}
