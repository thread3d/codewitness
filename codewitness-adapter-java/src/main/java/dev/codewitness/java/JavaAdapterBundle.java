package dev.codewitness.java;

import dev.codewitness.core.spi.LanguageAdapterRegistry;
import dev.codewitness.core.spi.VerificationProviderRegistry;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.java.analysis.HeuristicSecurityAnalyzer;
import dev.codewitness.java.analysis.HeuristicSourceAnalyzer;
import dev.codewitness.java.analysis.JavacWarningsAnalyzer;
import dev.codewitness.java.build.GradleBuildSystem;
import dev.codewitness.java.build.MavenBuildSystem;
import dev.codewitness.java.coverage.JacocoCoverageProvider;
import dev.codewitness.java.dependency.MavenDependencyAnalyzer;
import dev.codewitness.java.generate.JavaGeneratedTestRunner;
import dev.codewitness.java.generate.ScenarioTestGenerator;
import dev.codewitness.java.mutation.PitMutationTester;

/**
 * Wires the Java adapter and its providers into the verification core.
 *
 * <p>This is the only place that knows which concrete Java tools exist. The engine sees
 * {@code LanguageAdapter} and the provider interfaces, so replacing PIT with another mutation tester
 * or adding Checkstyle next to the built-in analyzer is a change to this class and nothing else.
 */
public final class JavaAdapterBundle {

    private JavaAdapterBundle() {
    }

    /** Registers the Java language adapter and every Java verification provider. */
    public static void register(
            LanguageAdapterRegistry languages, VerificationProviderRegistry providers, boolean includeAnalysis) {

        languages.register(new JavaLanguageAdapter());

        providers.register(new ScenarioTestGenerator());

        // Providers that need a process runner receive the run's sandbox runner from the
        // VerificationContext at execution time, so nothing is captured at wiring time.
        RunnerAwareMavenProviders mavenProviders = new RunnerAwareMavenProviders();
        providers.register((VerificationTools.Compiler) mavenProviders);
        providers.register((VerificationTools.TestRunner) mavenProviders);
        providers.register((VerificationTools.MutationTester) mavenProviders);
        providers.register((VerificationTools.DependencyAnalyzer) mavenProviders);
        providers.register((VerificationTools.GeneratedTestExecutor) mavenProviders);
        providers.register((VerificationTools.CoverageProvider) mavenProviders);

        RunnerAwareGradleProviders gradleProviders = new RunnerAwareGradleProviders();
        providers.register((VerificationTools.Compiler) gradleProviders);
        providers.register((VerificationTools.TestRunner) gradleProviders);
        providers.register((VerificationTools.GeneratedTestExecutor) gradleProviders);

        if (includeAnalysis) {
            providers.register(new StaticAnalyzerAdapter());
            providers.register(new SecurityAnalyzerAdapter());
        }
    }

    /** Static analyzer that receives its sandbox from the context. */
    private static final class StaticAnalyzerAdapter implements dev.codewitness.core.spi.VerificationTools.StaticAnalyzer {
        private final HeuristicSourceAnalyzer heuristics = new HeuristicSourceAnalyzer();

        @Override
        public String id() {
            return "java-static-analysis";
        }

        @Override
        public boolean supports(dev.codewitness.core.model.ProjectInfo project) {
            return project.language().equals(dev.codewitness.core.model.LanguageId.JAVA);
        }

        @Override
        public java.util.List<dev.codewitness.core.model.Finding> analyze(
                dev.codewitness.core.spi.VerificationContext context) {
            java.util.List<dev.codewitness.core.model.Finding> findings =
                    new java.util.ArrayList<>(heuristics.analyze(context));
            var buildSystem = dev.codewitness.java.build.BuildOutputParser.forProject(context.projectInfo());
            if (buildSystem != null && context.config().staticAnalysis().analyzers().contains("javac")) {
                findings.addAll(new JavacWarningsAnalyzer(new BuildToolLauncher(context.runner()), buildSystem)
                        .analyze(context));
            }
            return java.util.List.copyOf(findings);
        }
    }

    /** Security analyzer that shares the rule table with the static analyzer. */
    private static final class SecurityAnalyzerAdapter implements dev.codewitness.core.spi.VerificationTools.SecurityAnalyzer {
        private final HeuristicSecurityAnalyzer delegate = new HeuristicSecurityAnalyzer();

        @Override
        public String id() {
            return "java-security-analysis";
        }

        @Override
        public boolean supports(dev.codewitness.core.model.ProjectInfo project) {
            return project.language().equals(dev.codewitness.core.model.LanguageId.JAVA);
        }

        @Override
        public java.util.List<dev.codewitness.core.model.Finding> analyze(
                dev.codewitness.core.spi.VerificationContext context) {
            return delegate.analyze(context);
        }
    }

    /**
     * Registers the Maven build, test, mutation and dependency providers. They resolve the run's
     * sandbox runner from the context at execution time.
     */
    private static final class RunnerAwareMavenProviders
            implements VerificationTools.Compiler,
                       VerificationTools.TestRunner,
                       VerificationTools.MutationTester,
                       VerificationTools.DependencyAnalyzer,
                       VerificationTools.GeneratedTestExecutor,
                       VerificationTools.CoverageProvider {

        private final MavenBuildSystem buildSystem = new MavenBuildSystem();

        @Override
        public String id() {
            return "maven";
        }

        @Override
        public boolean supports(dev.codewitness.core.model.ProjectInfo project) {
            // The language is part of the claim, not just the build system. Kotlin projects use Maven and
            // Gradle too, and the Kotlin adapter's own providers answer for those sources.
            return project.language().equals(dev.codewitness.core.model.LanguageId.JAVA)
                    && project.buildSystem().id().equals("maven");
        }

        @Override
        public dev.codewitness.core.model.BuildResult build(
                dev.codewitness.core.spi.VerificationContext context) {
            return new JavaBuildProvider(new BuildToolLauncher(context.runner()), buildSystem).build(context);
        }

        @Override
        public dev.codewitness.core.model.TestResult runTests(
                dev.codewitness.core.spi.VerificationContext context) {
            return new JavaTestProvider(new BuildToolLauncher(context.runner()), buildSystem).runTests(context);
        }

        @Override
        public java.util.List<String> testCommand(
                dev.codewitness.core.spi.VerificationContext context) {
            return new JavaTestProvider(new BuildToolLauncher(context.runner()), buildSystem).testCommand(context);
        }

        @Override
        public java.util.List<String> dependencyCommand(
                dev.codewitness.core.spi.VerificationContext context) {
            return new MavenDependencyAnalyzer(new BuildToolLauncher(context.runner())).dependencyCommand(context);
        }

        @Override
        public java.util.List<String> replayCommand(
                dev.codewitness.core.spi.VerificationContext context,
                dev.codewitness.core.model.TestCaseResult testCase) {
            return new JavaTestProvider(new BuildToolLauncher(context.runner()), buildSystem)
                    .replayCommand(context, testCase);
        }

        @Override
        public java.util.List<String> reproductionCommand(
                dev.codewitness.core.spi.VerificationContext context,
                java.util.List<dev.codewitness.core.spi.GeneratedTest> tests,
                Long seed,
                Integer caseIndex) {
            return new JavaGeneratedTestRunner(new BuildToolLauncher(context.runner()), buildSystem)
                    .reproductionCommand(context, tests, seed, caseIndex);
        }

        @Override
        public dev.codewitness.core.model.MutationReport analyse(
                dev.codewitness.core.spi.VerificationContext context) {
            return new PitMutationTester(new BuildToolLauncher(context.runner())).analyse(context);
        }

        @Override
        public dev.codewitness.core.model.DependencyReport analyseDependencies(
                dev.codewitness.core.spi.VerificationContext context) {
            return new MavenDependencyAnalyzer(new BuildToolLauncher(context.runner())).analyseDependencies(context);
        }

        @Override
        public dev.codewitness.core.model.CoverageReport measure(
                dev.codewitness.core.spi.VerificationContext context) {
            return new JacocoCoverageProvider(new BuildToolLauncher(context.runner())).measure(context);
        }

        @Override
        public dev.codewitness.core.spi.GeneratedTestExecution execute(
                dev.codewitness.core.spi.VerificationContext context,
                java.util.List<dev.codewitness.core.spi.GeneratedTest> tests) {
            return new JavaGeneratedTestRunner(new BuildToolLauncher(context.runner()), buildSystem)
                    .execute(context, tests);
        }
    }

    /** Gradle counterparts, minus the tooling that has no Gradle implementation in the MVP. */
    private static final class RunnerAwareGradleProviders
            implements VerificationTools.Compiler,
                       VerificationTools.TestRunner,
                       VerificationTools.GeneratedTestExecutor {

        private final GradleBuildSystem buildSystem = new GradleBuildSystem();

        @Override
        public String id() {
            return "gradle";
        }

        @Override
        public boolean supports(dev.codewitness.core.model.ProjectInfo project) {
            // Gradle builds Kotlin as readily as Java, so the language has to be part of the claim.
            return project.language().equals(dev.codewitness.core.model.LanguageId.JAVA)
                    && project.buildSystem().id().equals("gradle");
        }

        @Override
        public dev.codewitness.core.model.BuildResult build(
                dev.codewitness.core.spi.VerificationContext context) {
            return new JavaBuildProvider(new BuildToolLauncher(context.runner()), buildSystem).build(context);
        }

        @Override
        public dev.codewitness.core.model.TestResult runTests(
                dev.codewitness.core.spi.VerificationContext context) {
            return new JavaTestProvider(new BuildToolLauncher(context.runner()), buildSystem).runTests(context);
        }

        @Override
        public java.util.List<String> testCommand(
                dev.codewitness.core.spi.VerificationContext context) {
            return new JavaTestProvider(new BuildToolLauncher(context.runner()), buildSystem).testCommand(context);
        }

        @Override
        public java.util.List<String> replayCommand(
                dev.codewitness.core.spi.VerificationContext context,
                dev.codewitness.core.model.TestCaseResult testCase) {
            return new JavaTestProvider(new BuildToolLauncher(context.runner()), buildSystem)
                    .replayCommand(context, testCase);
        }

        @Override
        public java.util.List<String> reproductionCommand(
                dev.codewitness.core.spi.VerificationContext context,
                java.util.List<dev.codewitness.core.spi.GeneratedTest> tests,
                Long seed,
                Integer caseIndex) {
            return new JavaGeneratedTestRunner(new BuildToolLauncher(context.runner()), buildSystem)
                    .reproductionCommand(context, tests, seed, caseIndex);
        }

        @Override
        public dev.codewitness.core.spi.GeneratedTestExecution execute(
                dev.codewitness.core.spi.VerificationContext context,
                java.util.List<dev.codewitness.core.spi.GeneratedTest> tests) {
            return new JavaGeneratedTestRunner(new BuildToolLauncher(context.runner()), buildSystem)
                    .execute(context, tests);
        }
    }
}
