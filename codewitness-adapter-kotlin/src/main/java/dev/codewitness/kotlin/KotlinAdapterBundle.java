package dev.codewitness.kotlin;

import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.GeneratedTestExecution;
import dev.codewitness.core.spi.LanguageAdapterRegistry;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationProviderRegistry;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.kotlin.analysis.KotlinHeuristicAnalyzer;
import dev.codewitness.kotlin.analysis.KotlinSecurityAnalyzer;
import dev.codewitness.kotlin.coverage.KotlinCoverageProvider;
import dev.codewitness.kotlin.dependency.KotlinDependencyAnalyzer;
import dev.codewitness.kotlin.generate.KotlinGeneratedTestRunner;
import dev.codewitness.kotlin.generate.KotlinScenarioTestGenerator;
import dev.codewitness.kotlin.mutation.KotlinMutationTester;

import java.util.List;

/**
 * Wires the Kotlin adapter and its providers into the verification core.
 *
 * <p>This is the only place that knows which concrete Kotlin tools exist. The engine sees
 * {@link dev.codewitness.core.spi.LanguageAdapter} and the provider interfaces, so substituting another
 * mutation tester or adding an external analyzer next to the built-in rule table is a change to this class
 * and nothing else.
 *
 * <p>Maven and Gradle are registered as two provider sets, each claiming only Kotlin projects that use it.
 * That matters more here than anywhere else in the codebase: a Java project and a Kotlin project can both be
 * built by Gradle, so a provider that selected on the build system alone would answer for the other
 * language's sources.
 */
public final class KotlinAdapterBundle {

    private KotlinAdapterBundle() {
    }

    /** Registers the Kotlin language adapter and every Kotlin verification provider. */
    public static void register(
            LanguageAdapterRegistry languages, VerificationProviderRegistry providers, boolean includeAnalysis) {

        languages.register(new KotlinLanguageAdapter());

        providers.register(new KotlinScenarioTestGenerator());

        // Providers that need a process runner receive the run's sandbox runner from the VerificationContext at
        // execution time, so nothing is captured at wiring time.
        BuildSystemProviders maven = new BuildSystemProviders(new KotlinMavenBuildSystem());
        providers.register((VerificationTools.Compiler) maven);
        providers.register((VerificationTools.TestRunner) maven);
        providers.register((VerificationTools.MutationTester) maven);
        providers.register((VerificationTools.DependencyAnalyzer) maven);
        providers.register((VerificationTools.GeneratedTestExecutor) maven);
        providers.register((VerificationTools.CoverageProvider) maven);

        BuildSystemProviders gradle = new BuildSystemProviders(new KotlinGradleBuildSystem());
        providers.register((VerificationTools.Compiler) gradle);
        providers.register((VerificationTools.TestRunner) gradle);
        providers.register((VerificationTools.MutationTester) gradle);
        providers.register((VerificationTools.DependencyAnalyzer) gradle);
        providers.register((VerificationTools.GeneratedTestExecutor) gradle);
        providers.register((VerificationTools.CoverageProvider) gradle);

        if (includeAnalysis) {
            providers.register((VerificationTools.StaticAnalyzer) new KotlinHeuristicAnalyzer());
            providers.register((VerificationTools.SecurityAnalyzer) new KotlinSecurityAnalyzer());
        }
    }

    /**
     * Every provider for one build system, each resolving the run's sandbox runner from the context at
     * execution time.
     */
    private static final class BuildSystemProviders
            implements VerificationTools.Compiler,
                       VerificationTools.TestRunner,
                       VerificationTools.MutationTester,
                       VerificationTools.DependencyAnalyzer,
                       VerificationTools.GeneratedTestExecutor,
                       VerificationTools.CoverageProvider {

        private final KotlinBuildSystem buildSystem;

        private BuildSystemProviders(KotlinBuildSystem buildSystem) {
            this.buildSystem = buildSystem;
        }

        private KotlinBuildTools tools(VerificationContext context) {
            return new KotlinBuildTools(context.runner());
        }

        @Override
        public String id() {
            return "kotlin-" + buildSystem.id();
        }

        @Override
        public boolean supports(ProjectInfo project) {
            return project.language().equals(dev.codewitness.core.model.LanguageId.KOTLIN)
                    && project.buildSystem().id().equals(buildSystem.id());
        }

        @Override
        public BuildResult build(VerificationContext context) {
            return new KotlinBuildProvider(tools(context), buildSystem).build(context);
        }

        @Override
        public TestResult runTests(VerificationContext context) {
            return new KotlinTestProvider(tools(context), buildSystem).runTests(context);
        }

        @Override
        public List<String> testCommand(VerificationContext context) {
            return new KotlinTestProvider(tools(context), buildSystem).testCommand(context);
        }

        @Override
        public List<String> replayCommand(VerificationContext context, TestCaseResult testCase) {
            return new KotlinTestProvider(tools(context), buildSystem).replayCommand(context, testCase);
        }

        @Override
        public MutationReport analyse(VerificationContext context) {
            return new KotlinMutationTester(tools(context)).analyse(context);
        }

        @Override
        public DependencyReport analyseDependencies(VerificationContext context) {
            return new KotlinDependencyAnalyzer(tools(context)).analyseDependencies(context);
        }

        @Override
        public List<String> dependencyCommand(VerificationContext context) {
            return new KotlinDependencyAnalyzer(tools(context)).dependencyCommand(context);
        }

        // The advisory hook has to be forwarded, not inherited: this wrapper is what the provider registry
        // actually holds, so a default here would answer "no ecosystem" for every Maven advisory and the
        // reproduction recipe would lose the recipe the analyzer knows how to build. Both build systems
        // report the ecosystem; only the Maven one has a command to build for it, which the analyzer checks.
        @Override
        public java.util.Set<String> ecosystems() {
            return KotlinDependencyAnalyzer.ECOSYSTEMS;
        }

        @Override
        public List<String> dependencyPathCommand(
                VerificationContext context, dev.codewitness.core.model.PackageCoordinate coordinate) {
            return new KotlinDependencyAnalyzer(tools(context)).dependencyPathCommand(context, coordinate);
        }

        @Override
        public CoverageReport measure(VerificationContext context) {
            return new KotlinCoverageProvider(tools(context)).measure(context);
        }

        @Override
        public GeneratedTestExecution execute(VerificationContext context, List<GeneratedTest> tests) {
            return new KotlinGeneratedTestRunner(tools(context), buildSystem).execute(context, tests);
        }

        @Override
        public List<String> reproductionCommand(
                VerificationContext context, List<GeneratedTest> tests, Long seed, Integer caseIndex) {
            return new KotlinGeneratedTestRunner(tools(context), buildSystem)
                    .reproductionCommand(context, tests, seed, caseIndex);
        }
    }
}
