package dev.codewitness.core.spi;

import dev.codewitness.core.model.ProjectInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Registry of verification providers.
 *
 * <p>Tool selection is data driven, exactly like language adapter selection: the engine asks each
 * registered provider whether it supports the project, then runs the phases for which at least one
 * provider claims support.
 */
public final class VerificationProviderRegistry {

    private final List<VerificationTools.Compiler> compilers = new ArrayList<>();
    private final List<VerificationTools.TestRunner> testRunners = new ArrayList<>();
    private final List<VerificationTools.StaticAnalyzer> staticAnalyzers = new ArrayList<>();
    private final List<VerificationTools.CoverageProvider> coverageProviders = new ArrayList<>();
    private final List<VerificationTools.MutationTester> mutationTesters = new ArrayList<>();
    private final List<VerificationTools.SecurityAnalyzer> securityAnalyzers = new ArrayList<>();
    private final List<VerificationTools.DependencyAnalyzer> dependencyAnalyzers = new ArrayList<>();
    private final List<VerificationTools.TestGenerator> testGenerators = new ArrayList<>();
    private final List<VerificationTools.GeneratedTestExecutor> generatedTestExecutors = new ArrayList<>();
    private final List<VerificationStrategy> strategies = new ArrayList<>();

    public static VerificationProviderRegistry empty() {
        return new VerificationProviderRegistry();
    }

    public VerificationProviderRegistry register(VerificationTools.Compiler compiler) {
        compilers.add(compiler);
        return this;
    }

    public VerificationProviderRegistry register(VerificationTools.TestRunner runner) {
        testRunners.add(runner);
        return this;
    }

    public VerificationProviderRegistry register(VerificationTools.StaticAnalyzer analyzer) {
        staticAnalyzers.add(analyzer);
        return this;
    }

    public VerificationProviderRegistry register(VerificationTools.CoverageProvider provider) {
        coverageProviders.add(provider);
        return this;
    }

    public VerificationProviderRegistry register(VerificationTools.MutationTester tester) {
        mutationTesters.add(tester);
        return this;
    }

    public VerificationProviderRegistry register(VerificationTools.SecurityAnalyzer analyzer) {
        securityAnalyzers.add(analyzer);
        return this;
    }

    public VerificationProviderRegistry register(VerificationTools.DependencyAnalyzer analyzer) {
        dependencyAnalyzers.add(analyzer);
        return this;
    }

    public VerificationProviderRegistry register(VerificationTools.TestGenerator generator) {
        testGenerators.add(generator);
        return this;
    }

    public VerificationProviderRegistry register(VerificationTools.GeneratedTestExecutor executor) {
        generatedTestExecutors.add(executor);
        return this;
    }

    public VerificationProviderRegistry register(VerificationStrategy strategy) {
        strategies.add(strategy);
        return this;
    }

    public List<VerificationTools.Compiler> compilers(ProjectInfo project) {
        return compilers.stream().filter(c -> c.supports(project)).toList();
    }

    public List<VerificationTools.TestRunner> testRunners(ProjectInfo project) {
        return testRunners.stream().filter(c -> c.supports(project)).toList();
    }

    public List<VerificationTools.StaticAnalyzer> staticAnalyzers(ProjectInfo project) {
        return staticAnalyzers.stream().filter(c -> c.supports(project)).toList();
    }

    public List<VerificationTools.CoverageProvider> coverageProviders(ProjectInfo project) {
        return coverageProviders.stream().filter(c -> c.supports(project)).toList();
    }

    public List<VerificationTools.MutationTester> mutationTesters(ProjectInfo project) {
        return mutationTesters.stream().filter(c -> c.supports(project)).toList();
    }

    public List<VerificationTools.SecurityAnalyzer> securityAnalyzers(ProjectInfo project) {
        return securityAnalyzers.stream().filter(c -> c.supports(project)).toList();
    }

    public List<VerificationTools.DependencyAnalyzer> dependencyAnalyzers(ProjectInfo project) {
        return dependencyAnalyzers.stream().filter(c -> c.supports(project)).toList();
    }

    /**
     * @return every registered dependency analyzer, unfiltered by project.
     *
     * <p>The advisory strategy cannot select by project the way a verification phase does. An advisory is
     * attributed on the merged dependency report, whose project may be polyglot, while each analyzer's
     * {@link VerificationTools.DependencyAnalyzer#supports(ProjectInfo)} answers for a single language.
     * The ecosystem the advisory names is what identifies the analyzer that knows how to explain it, so
     * the lookup is by ecosystem rather than by project.
     */
    public List<VerificationTools.DependencyAnalyzer> dependencyAnalyzers() {
        return List.copyOf(dependencyAnalyzers);
    }

    /**
     * @param ecosystem an advisory ecosystem id, exactly as the advisory service spells it
     * @return the first registered analyzer that claims that ecosystem, or empty when none does
     */
    public Optional<VerificationTools.DependencyAnalyzer> dependencyAnalyzerFor(String ecosystem) {
        return dependencyAnalyzers.stream()
                .filter(analyzer -> analyzer.ecosystems().contains(ecosystem))
                .findFirst();
    }

    public List<VerificationTools.TestGenerator> testGenerators(ProjectInfo project) {
        return testGenerators.stream().filter(c -> c.supports(project)).toList();
    }

    public List<VerificationTools.GeneratedTestExecutor> generatedTestExecutors(ProjectInfo project) {
        return generatedTestExecutors.stream().filter(c -> c.supports(project)).toList();
    }

    public List<VerificationStrategy> strategies(ProjectInfo project) {
        return strategies.stream().filter(c -> c.supports(project)).toList();
    }

    /**
     * @return {@code true} when any strategy has been registered, regardless of whether it claims the
     *     project.
     *
     * <p>The distinction from {@link #strategies(ProjectInfo)} matters to the engine's default: an empty
     * registry means the caller wired no strategies and the built-in set should run, while a registry with
     * strategies that all decline this project means the caller chose a set on purpose and the engine must
     * honour that choice rather than quietly substituting its own.
     */
    public boolean hasStrategies() {
        return !strategies.isEmpty();
    }

    /** @return every registered strategy, unfiltered by project, in registration order. */
    public List<VerificationStrategy> strategies() {
        return List.copyOf(strategies);
    }

    public List<String> providerIds() {
        List<String> ids = new ArrayList<>();
        compilers.forEach(c -> ids.add("compiler:" + c.id()));
        testRunners.forEach(c -> ids.add("test-runner:" + c.id()));
        staticAnalyzers.forEach(c -> ids.add("static-analyzer:" + c.id()));
        coverageProviders.forEach(c -> ids.add("coverage:" + c.id()));
        mutationTesters.forEach(c -> ids.add("mutation:" + c.id()));
        securityAnalyzers.forEach(c -> ids.add("security:" + c.id()));
        dependencyAnalyzers.forEach(c -> ids.add("dependency:" + c.id()));
        testGenerators.forEach(c -> ids.add("test-generator:" + c.id()));
        generatedTestExecutors.forEach(c -> ids.add("generated-test-executor:" + c.id()));
        strategies.forEach(c -> ids.add("strategy:" + c.id()));
        return List.copyOf(ids);
    }
}
