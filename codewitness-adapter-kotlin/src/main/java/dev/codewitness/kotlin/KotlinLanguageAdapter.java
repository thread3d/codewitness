package dev.codewitness.kotlin;

import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.Capability;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.LanguageAdapter;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.kotlin.analysis.KotlinHeuristicAnalyzer;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * The Kotlin language adapter.
 *
 * <p>Implements the whole {@link LanguageAdapter} contract for Kotlin on the JVM, built by the project's own
 * Gradle or Maven build with the Kotlin Gradle plugin or {@code kotlin-maven-plugin}. The individual tools it
 * uses - compiler, test runner, analyzers, coverage, mutation, dependencies, generated tests - are separate
 * providers registered in {@link KotlinAdapterBundle}; this class wires detection and resolves the build tool
 * every later phase will use.
 *
 * <p>Nothing is installed beyond the toolchain the project already builds with, and no third-party package is
 * required by the adapter itself: a fixture project needs JUnit because it is a JUnit project, not because
 * CodeWitness does.
 *
 * <p>Kotlin shares a platform with Java and none of its language. A tree that contains both is polyglot, and
 * each adapter claims only the sources written in its own language, which is what keeps a Kotlin project
 * built with Gradle from being reported as a Java project that happens to use Kotlin.
 */
public final class KotlinLanguageAdapter implements LanguageAdapter {

    @Override
    public String id() {
        return "kotlin";
    }

    @Override
    public boolean supports(ProjectRef project) {
        return KotlinProjectDetector.looksLikeKotlinProject(project.root());
    }

    @Override
    public ProjectInfo inspect(ProjectRef project, VerificationContext context) {
        // Detection runs against the isolated workspace, but the reported project information keeps the
        // original project reference so reports describe the user's project, not a copy of it.
        ProjectRef workspaceRef = new ProjectRef(context.projectRoot(), project.name(), project.identifier());
        ProjectInfo detected = KotlinProjectDetector.inspect(workspaceRef);
        KotlinBuildSystem buildSystem = KotlinBuildOutputParser.forProject(detected);
        if (buildSystem == null) {
            // Kotlin sources with no build descriptor: the layout is reported, and the warning the detector
            // recorded travels with it. Everything the detector found is carried over - test frameworks,
            // descriptors and wrapper included - because rebuilding the ProjectInfo without them would
            // silently drop them from every report.
            ProjectInfo.Builder unbuilt = ProjectInfo.builder(project)
                    .language(detected.language())
                    .buildSystem(detected.buildSystem())
                    .buildRoot(detected.buildRoot())
                    .sourceExtensions(detected.sourceExtensions())
                    .attributes(detected.attributes())
                    .modules(detected.modules());
            detected.buildWrapper().ifPresent(unbuilt::buildWrapper);
            detected.testFrameworks().forEach(unbuilt::addTestFramework);
            detected.detectedDescriptors().forEach(unbuilt::addDetectedDescriptor);
            return unbuilt.build();
        }

        // The build tool version is probed here rather than in the detector because it needs a process run.
        // Everything else discovered so far must be carried over unchanged: rebuilding the ProjectInfo
        // without the test frameworks silently loses them from every report.
        KotlinBuildTools tools = new KotlinBuildTools(context.runner());
        String version = tools.version(tools.resolve(detected, detected.buildRoot()), detected.buildRoot(),
                Duration.ofSeconds(60));
        ProjectInfo.Builder complete = ProjectInfo.builder(project)
                .language(detected.language())
                .buildSystem(detected.buildSystem())
                .buildRoot(detected.buildRoot())
                .sourceExtensions(detected.sourceExtensions())
                .attributes(detected.attributes())
                .modules(detected.modules())
                .attribute("buildToolVersion", version);
        detected.buildWrapper().ifPresent(complete::buildWrapper);
        detected.testFrameworks().forEach(complete::addTestFramework);
        detected.detectedDescriptors().forEach(complete::addDetectedDescriptor);
        String kotlinVersion = detected.attributes().get(KotlinProjectDetector.ATTR_KOTLIN_VERSION);
        if (kotlinVersion != null) {
            // ProjectInfo.describe reads this, so a report says "kotlin project using gradle, Kotlin 2.2.20"
            // rather than leaving the toolchain out of the sentence a reader sees first.
            complete.attribute("runtimeVersion", "Kotlin " + kotlinVersion);
        }
        return complete.build();
    }

    @Override
    public BuildResult build(VerificationContext context) {
        KotlinBuildSystem buildSystem = KotlinBuildOutputParser.forProject(context.projectInfo());
        if (buildSystem == null) {
            return BuildResult.notAttempted("no supported Kotlin build system detected for this project");
        }
        return new KotlinBuildProvider(new KotlinBuildTools(context.runner()), buildSystem).build(context);
    }

    @Override
    public TestResult runTests(VerificationContext context) {
        KotlinBuildSystem buildSystem = KotlinBuildOutputParser.forProject(context.projectInfo());
        if (buildSystem == null) {
            return TestResult.notExecuted("no supported Kotlin build system detected for this project");
        }
        return new KotlinTestProvider(new KotlinBuildTools(context.runner()), buildSystem).runTests(context);
    }

    @Override
    public List<Finding> analyze(VerificationContext context) {
        return new KotlinHeuristicAnalyzer().analyze(context);
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.INSPECT, Capability.BUILD, Capability.TEST, Capability.STATIC_ANALYSIS,
                Capability.COVERAGE, Capability.MUTATION, Capability.PROPERTY_TESTING,
                Capability.GENERATED_TESTS, Capability.SECURITY_SCAN, Capability.DEPENDENCY_SCAN);
    }
}
