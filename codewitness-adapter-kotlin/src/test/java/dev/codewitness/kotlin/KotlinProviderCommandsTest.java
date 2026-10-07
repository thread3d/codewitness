package dev.codewitness.kotlin;

import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.Capability;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.LanguageAdapterRegistry;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationProviderRegistry;
import dev.codewitness.kotlin.generate.KotlinGeneratedTestRunner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the adapter's wiring and for the claims and commands it builds around it.
 *
 * <p>The claims matter as much as the commands here. Kotlin shares Gradle and Maven with Java, so a
 * provider that selected on the build system alone would answer for a Java project's sources - and two
 * providers claiming one project both run. These tests pin the language half of every claim.
 */
class KotlinProviderCommandsTest {

    @Test
    @DisplayName("the adapter identifies itself, claims Kotlin trees and offers every capability it wires")
    void adapterBasics(@TempDir Path project) throws IOException {
        KotlinLanguageAdapter adapter = new KotlinLanguageAdapter();

        assertEquals("kotlin", adapter.id());
        assertFalse(adapter.supports(ProjectRef.of(project)), "an empty directory is not claimed");

        KotlinTestContexts.write(project,
                "src/main/kotlin/example/Ledger.kt", "package example\n\nclass Ledger\n");
        assertTrue(adapter.supports(ProjectRef.of(project)));

        assertEquals(Set.of(Capability.INSPECT, Capability.BUILD, Capability.TEST, Capability.STATIC_ANALYSIS,
                        Capability.COVERAGE, Capability.MUTATION, Capability.PROPERTY_TESTING,
                        Capability.GENERATED_TESTS, Capability.SECURITY_SCAN, Capability.DEPENDENCY_SCAN),
                adapter.capabilities());
    }

    @Test
    @DisplayName("a Gradle build script and a POM are not Kotlin evidence on their own")
    void buildDescriptorsAreNotEvidence(@TempDir Path project) throws IOException {
        KotlinTestContexts.write(project, "build.gradle", "plugins { id 'java' }\n");
        KotlinTestContexts.write(project, "pom.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>java-example</artifactId>
                </project>
                """);
        KotlinTestContexts.write(project, "src/main/java/example/Ledger.java",
                "package example; class Ledger {}\n");

        assertFalse(new KotlinLanguageAdapter().supports(ProjectRef.of(project)),
                "a Java tree is the Java adapter's to verify, whichever build tool it uses");
    }

    @Test
    @DisplayName("the bundle registers a provider of every kind for each build system, and analysis only when asked")
    void bundleRegistration() {
        LanguageAdapterRegistry languages = new LanguageAdapterRegistry();
        VerificationProviderRegistry providers = VerificationProviderRegistry.empty();
        KotlinAdapterBundle.register(languages, providers, true);

        List<String> ids = providers.providerIds();
        assertTrue(ids.contains("compiler:kotlin-maven"), ids.toString());
        assertTrue(ids.contains("compiler:kotlin-gradle"), ids.toString());
        assertTrue(ids.contains("test-runner:kotlin-maven"), ids.toString());
        assertTrue(ids.contains("test-runner:kotlin-gradle"), ids.toString());
        assertTrue(ids.contains("coverage:kotlin-maven") && ids.contains("coverage:kotlin-gradle"),
                ids.toString());
        assertTrue(ids.contains("mutation:kotlin-maven") && ids.contains("mutation:kotlin-gradle"),
                "one mutation provider per build system, each resolving its runner from the context: "
                        + ids);
        assertTrue(ids.contains("dependency:kotlin-maven") && ids.contains("dependency:kotlin-gradle"),
                ids.toString());
        assertTrue(ids.contains("test-generator:kotlin-scenario-generator"), ids.toString());
        assertTrue(ids.contains("generated-test-executor:kotlin-maven"), ids.toString());
        assertTrue(ids.contains("static-analyzer:kotlin-heuristics"), ids.toString());
        assertTrue(ids.contains("security:kotlin-security-analysis"), ids.toString());
        assertEquals(List.of("kotlin"), languages.ids());

        VerificationProviderRegistry withoutAnalysis = VerificationProviderRegistry.empty();
        KotlinAdapterBundle.register(new LanguageAdapterRegistry(), withoutAnalysis, false);
        assertFalse(withoutAnalysis.providerIds().contains("static-analyzer:kotlin-heuristics"));
        assertFalse(withoutAnalysis.providerIds().contains("security:kotlin-security-analysis"));
    }

    @Test
    @DisplayName("the two provider sets claim their own build system and no Java project")
    void providersClaimByLanguageAndBuildSystem(@TempDir Path root) throws IOException {
        KotlinTestContexts.project(root);
        KotlinTestContexts.write(root, "pom.xml", KotlinTestContexts.MAVEN_POM);
        ProjectInfo kotlin = KotlinTestContexts.projectInfo(root);

        assertEquals(LanguageId.KOTLIN, kotlin.language());
        assertEquals(BuildSystemId.MAVEN, kotlin.buildSystem());

        VerificationProviderRegistry providers = VerificationProviderRegistry.empty();
        KotlinAdapterBundle.register(new LanguageAdapterRegistry(), providers, true);

        assertEquals(1, providers.compilers(kotlin).size(), "only the Maven provider claims a Maven project");
        assertEquals("kotlin-maven", providers.compilers(kotlin).getFirst().id());
        assertEquals(1, providers.testRunners(kotlin).size());
        assertEquals(1, providers.coverageProviders(kotlin).size());
        assertEquals(1, providers.mutationTesters(kotlin).size());

        ProjectInfo java = ProjectInfo.builder(kotlin.project())
                .language(LanguageId.JAVA)
                .buildSystem(BuildSystemId.MAVEN)
                .build();
        assertTrue(providers.compilers(java).isEmpty(),
                "a Java project's build is the Java adapter's to run, not this one's");
        assertTrue(providers.coverageProviders(java).isEmpty(),
                "and its coverage too, or two providers would both measure it");
    }

    @Test
    @DisplayName("the build tool is resolved from the project wrapper before the environment or PATH")
    void theWrapperWinsOverTheInstalledTool(@TempDir Path root) throws IOException {
        KotlinTestContexts.project(root);
        KotlinTestContexts.write(root, "pom.xml", KotlinTestContexts.MAVEN_POM);
        KotlinTestContexts.write(root, "mvnw", "#!/bin/sh\n");
        VerificationContext context = KotlinTestContexts.context(root, root.resolve(".codewitness"));

        List<String> resolved = new KotlinBuildTools(context.runner()).resolve(context.projectInfo(), root);

        assertFalse(resolved.isEmpty(), "a project wrapper is always resolvable");
        assertTrue(String.join(" ", resolved).contains("mvnw"),
                "the project's own wrapper is used, not whatever Maven is installed: " + resolved);
    }

    @Test
    @DisplayName("the mutation command fails on a failing test while the test phase does not")
    void theMutationCommandIsNotTheTestCommand(@TempDir Path root) throws IOException {
        KotlinTestContexts.project(root);
        KotlinTestContexts.write(root, "pom.xml", KotlinTestContexts.MAVEN_POM);
        VerificationContext context = KotlinTestContexts.context(root, root.resolve(".codewitness"));
        KotlinMavenBuildSystem maven = new KotlinMavenBuildSystem();

        List<String> test = new KotlinTestProvider(new KotlinBuildTools(context.runner()), maven)
                .testCommand(context);
        List<String> mutation = maven.mutationGoals();

        assertTrue(test.contains("-Dmaven.test.failure.ignore=true"),
                "the test phase keeps the reactor going so every module writes its report: " + test);
        assertFalse(mutation.contains("-Dmaven.test.failure.ignore=true"),
                "a mutation run has only the exit status to decide whether a mutant was killed: " + mutation);
        assertTrue(mutation.contains("test"), mutation.toString());
    }

    @Test
    @DisplayName("the build goals are the Kotlin ones, not the Java ones")
    void buildGoalsAreTheKotlinOnes() {
        assertTrue(new KotlinGradleBuildSystem().compileGoals().contains("testClasses"),
                "the Kotlin plugin makes compileTestKotlin part of testClasses");
        assertTrue(new KotlinGradleBuildSystem().lintGoals().contains("compileKotlin"),
                "compileJava would not compile a Kotlin file at all");
        assertTrue(new KotlinMavenBuildSystem().compileGoals().contains("test-compile"),
                "that is the phase kotlin-maven-plugin binds its executions to");
        assertTrue(new KotlinMavenBuildSystem().compileGoals()
                        .contains("-Dkotlin.compiler.execution.strategy=in-process"),
                "otherwise a compiler daemon outlives the run and holds the workspace open");
    }

    @Test
    @DisplayName("the replay commands name the generated class, with the seed and case pinned")
    void theReplayCommandsAreReproducible(@TempDir Path root, @TempDir Path run) throws IOException {
        KotlinTestContexts.project(root);
        KotlinTestContexts.write(root, "pom.xml", KotlinTestContexts.MAVEN_POM);
        VerificationContext context = KotlinTestContexts.context(root, run);
        KotlinMavenBuildSystem maven = new KotlinMavenBuildSystem();
        KotlinBuildTools tools = new KotlinBuildTools(context.runner());
        TestCaseResult failing = new TestCaseResult("dev.codewitness.generated.Cw_SC_001_OB_001_Test",
                "randomSearch", TestOutcome.FAILED, Duration.ZERO, "AssertionError", "boom", "trace");

        List<String> replay = new KotlinTestProvider(tools, maven).replayCommand(context, failing);
        assertTrue(replay.contains("-Dtest=dev.codewitness.generated.Cw_SC_001_OB_001_Test#randomSearch"),
                replay.toString());
        assertTrue(replay.contains("test"), replay.toString());

        List<String> reproduction = new KotlinGeneratedTestRunner(tools, maven)
                .reproductionCommand(context, List.of(generatedTest()), 42L, 3);
        assertTrue(reproduction.contains("-Dcodewitness.seed=42"), reproduction.toString());
        assertTrue(reproduction.contains("-Dcodewitness.case=3"), reproduction.toString());
        assertTrue(reproduction.contains("-Dtest=Cw_SC_001_OB_001_Test"),
                "the class is selected by its simple name, which is how Surefire takes it: " + reproduction);
    }

    @Test
    @DisplayName("a generated Kotlin test is written as a .kt file, not where the Java convention would put it")
    void generatedTestsAreKotlinFiles() {
        assertEquals("dev/codewitness/generated/Cw_SC_001_OB_001_Test.kt",
                KotlinGeneratedTestRunner.relativeSourcePath(generatedTest()));
        assertTrue(KotlinGeneratedTestRunner.isGeneratedSource(
                        Path.of("/p/src/test/kotlin/dev/codewitness/generated/X.kt")),
                "the analyzer must not report this run's own scaffolding as a finding about the project");
        assertTrue(KotlinGeneratedTestRunner.isGeneratedSource(
                        Path.of("/p/src\\test\\kotlin\\dev\\codewitness\\generated\\X.kt")),
                "and it must recognise the path however the separator is spelled");
        assertFalse(KotlinGeneratedTestRunner.isGeneratedSource(Path.of("/p/src/test/kotlin/LedgerTest.kt")));
    }

    private static GeneratedTest generatedTest() {
        return new GeneratedTest("dev.codewitness.generated.Cw_SC_001_OB_001_Test",
                "package dev.codewitness.generated\n", "randomSearch", List.of("R-001"), List.of("OB-001"),
                "a scenario", 42L, null, true);
    }
}
