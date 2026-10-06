package dev.codewitness.java;

import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.Capability;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.LanguageAdapter;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.java.analysis.HeuristicSourceAnalyzer;
import dev.codewitness.java.analysis.JavacWarningsAnalyzer;
import dev.codewitness.java.build.BuildOutputParser;
import dev.codewitness.java.build.JavaBuildSystem;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The Java language adapter.
 *
 * <p>Implements the whole {@link LanguageAdapter} contract for Java 17+, Maven and Gradle, JUnit 5.
 * The individual tools it uses - compiler, test runner, analyzer, coverage, mutation - are separate
 * providers registered in {@link JavaAdapterBundle}; this class only wires detection and provides
 * the adapter level entry points the engine calls.
 */
public final class JavaLanguageAdapter implements LanguageAdapter {

    @Override
    public String id() {
        return "java";
    }

    @Override
    public boolean supports(ProjectRef project) {
        return JavaProjectDetector.looksLikeJavaProject(project.root());
    }

    @Override
    public ProjectInfo inspect(ProjectRef project, VerificationContext context) {
        // Detection runs against the isolated workspace, but the reported project information keeps
        // the original project reference so reports describe the user's project, not a copy of it.
        ProjectRef workspaceRef = new ProjectRef(context.projectRoot(), project.name(), project.identifier());
        ProjectInfo detected = JavaProjectDetector.inspect(workspaceRef);
        ProjectInfo.Builder builder = ProjectInfo.builder(project)
                .language(detected.language())
                .buildSystem(detected.buildSystem())
                .buildRoot(detected.buildRoot())
                .javaVersion(detected.javaVersion().orElse(null))
                .attributes(detected.attributes())
                .modules(detected.modules());
        detected.buildWrapper().ifPresent(builder::buildWrapper);
        detected.detectedDescriptors().forEach(builder::addDetectedDescriptor);
        detected.testFrameworks().forEach(builder::addTestFramework);
        ProjectInfo info = builder.build();

        BuildToolLauncher launcher = new BuildToolLauncher(context.runner());
        JavaBuildSystem buildSystem = BuildOutputParser.forProject(info);
        if (buildSystem != null) {
            String version = launcher.version(launcher.resolve(info, info.buildRoot()), info.buildRoot(),
                    java.time.Duration.ofSeconds(60));
            return ProjectInfo.builder(project)
                    .language(info.language())
                    .buildSystem(info.buildSystem())
                    .buildRoot(info.buildRoot())
                    .javaVersion(info.javaVersion().orElse(null))
                    .attributes(info.attributes())
                    .modules(info.modules())
                    .buildWrapper(info.buildWrapper().orElse(null))
                    .attribute("buildToolVersion", version)
                    .build();
        }
        return info;
    }

    @Override
    public BuildResult build(VerificationContext context) {
        JavaBuildSystem buildSystem = BuildOutputParser.forProject(context.projectInfo());
        if (buildSystem == null) {
            return BuildResult.notAttempted("no supported build system detected for this project");
        }
        return new JavaBuildProvider(new BuildToolLauncher(context.runner()), buildSystem).build(context);
    }

    @Override
    public TestResult runTests(VerificationContext context) {
        JavaBuildSystem buildSystem = BuildOutputParser.forProject(context.projectInfo());
        if (buildSystem == null) {
            return TestResult.notExecuted("no supported build system detected for this project");
        }
        return new JavaTestProvider(new BuildToolLauncher(context.runner()), buildSystem).runTests(context);
    }

    @Override
    public List<Finding> analyze(VerificationContext context) {
        List<Finding> findings = new ArrayList<>(new HeuristicSourceAnalyzer().analyze(context));
        JavaBuildSystem buildSystem = BuildOutputParser.forProject(context.projectInfo());
        if (buildSystem != null && context.config().staticAnalysis().analyzers().contains("javac")) {
            findings.addAll(new JavacWarningsAnalyzer(new BuildToolLauncher(context.runner()), buildSystem)
                    .analyze(context));
        }
        return List.copyOf(findings);
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.INSPECT, Capability.BUILD, Capability.TEST, Capability.STATIC_ANALYSIS,
                Capability.COVERAGE, Capability.MUTATION, Capability.PROPERTY_TESTING,
                Capability.GENERATED_TESTS, Capability.SECURITY_SCAN, Capability.DEPENDENCY_SCAN);
    }
}
