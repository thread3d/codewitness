package dev.codewitness.java.analysis;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.java.BuildToolLauncher;
import dev.codewitness.java.build.BuildOutputParser;
import dev.codewitness.java.build.JavaBuildSystem;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the compiler's own warnings into static analysis findings.
 *
 * <p>Rather than re-implementing a Java front end, this analyzer asks the project's build tool to
 * compile with {@code -Xlint:all} and parses the resulting diagnostics. The compiler is the most
 * reliable Java analyzer in existence and it is already installed, so using it is both cheaper and
 * more honest than an approximation.
 *
 * <p>The pass is incremental: because the build phase already compiled the project, re-running
 * {@code test-compile} with extra flags normally produces diagnostics without recompiling.
 */
public final class JavacWarningsAnalyzer implements VerificationTools.StaticAnalyzer {

    private final BuildToolLauncher launcher;
    private final JavaBuildSystem buildSystem;

    public JavacWarningsAnalyzer(BuildToolLauncher launcher, JavaBuildSystem buildSystem) {
        this.launcher = launcher;
        this.buildSystem = buildSystem;
    }

    @Override
    public String id() {
        return "javac";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.buildSystem().id().equals(buildSystem.id());
    }

    @Override
    public List<Finding> analyze(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        List<String> executable = launcher.resolve(project, buildSystem.buildRoot(project));
        if (executable == null) {
            return List.of();
        }
        ProcessResult result = context.runner().run(
                ProcessSpec.builder(BuildToolLauncher.command(executable, buildSystem.lintGoals()),
                                buildSystem.buildRoot(project))
                        .timeout(context.config().limits().timeout())
                        .maxOutputBytes(1_000_000)
                        .build());

        List<Finding> findings = new ArrayList<>();
        for (var diagnostic : BuildOutputParser.parse(result.combinedOutput(), context.projectRoot())) {
            Severity severity = switch (diagnostic.severity()) {
                case ERROR -> Severity.CRITICAL;
                case WARNING -> Severity.LOW;
                default -> Severity.INFO;
            };
            if (severity == Severity.INFO) {
                continue;
            }
            findings.add(new Finding("javac", diagnostic.code(), severity,
                    severity == Severity.CRITICAL ? FindingCategory.BUG : FindingCategory.MAINTAINABILITY,
                    diagnostic.message(), diagnostic.file(), diagnostic.line(), null,
                    java.util.Map.of("source", id())));
        }
        return findings;
    }
}
