package dev.codewitness.support.adapter;

import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.Capability;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationProviderRegistry;
import dev.codewitness.core.spi.VerificationTools;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The set of tools one language adapter uses, declared once.
 *
 * <p>Every adapter had the same three problems written out by hand. Its {@code *AdapterBundle} registered a list of
 * providers; its {@code build}, {@code runTests} and {@code analyze} were one-line delegations to providers that were
 * <em>also</em> registered; and its {@code capabilities()} restated the registration list as a second, independent
 * hand-maintained set. Nothing connected the three, so a provider could be registered and the phase silently skipped
 * for want of a capability — the failure mode the 0.1.15 capability guard reports.
 *
 * <p>This class is the one list. The bundle registers it, the adapter derives its behaviour and its capabilities from
 * it, and a capability cannot disagree with a registration because it is computed from it:
 *
 * <ul>
 *   <li>a provider that is present contributes its capability;</li>
 *   <li>every adapter can inspect, so {@link Capability#INSPECT} is always present;</li>
 *   <li>{@link Capability#PROPERTY_TESTING} and {@link Capability#GENERATED_TESTS} follow the generator and the
 *       executor, because a scenario that cannot be compiled into the project is not a test.</li>
 * </ul>
 *
 * <p>Analysis is optional because a bundle may be asked to register without it — that is what the
 * {@code includeAnalysis} argument to every bundle means, and a capability set that claimed analysis anyway would be
 * promising a phase that cannot run.
 */
public final class AdapterProviders {

    private final VerificationTools.Compiler compiler;
    private final VerificationTools.TestRunner testRunner;
    private final VerificationTools.StaticAnalyzer staticAnalyzer;
    private final VerificationTools.SecurityAnalyzer securityAnalyzer;
    private final VerificationTools.CoverageProvider coverage;
    private final VerificationTools.MutationTester mutation;
    private final VerificationTools.DependencyAnalyzer dependencies;
    private final VerificationTools.TestGenerator generator;
    private final VerificationTools.GeneratedTestExecutor executor;

    private AdapterProviders(Builder builder) {
        this.compiler = builder.compiler;
        this.testRunner = builder.testRunner;
        this.staticAnalyzer = builder.staticAnalyzer;
        this.securityAnalyzer = builder.securityAnalyzer;
        this.coverage = builder.coverage;
        this.mutation = builder.mutation;
        this.dependencies = builder.dependencies;
        this.generator = builder.generator;
        this.executor = builder.executor;
    }

    /** @return a builder with nothing declared. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return the capabilities these providers can actually serve, derived rather than declared
     */
    public Set<Capability> capabilities() {
        Set<Capability> capabilities = EnumSet.of(Capability.INSPECT);
        if (compiler != null) {
            capabilities.add(Capability.BUILD);
        }
        if (testRunner != null) {
            capabilities.add(Capability.TEST);
        }
        if (staticAnalyzer != null) {
            capabilities.add(Capability.STATIC_ANALYSIS);
        }
        if (securityAnalyzer != null) {
            capabilities.add(Capability.SECURITY_SCAN);
        }
        if (coverage != null) {
            capabilities.add(Capability.COVERAGE);
        }
        if (mutation != null) {
            capabilities.add(Capability.MUTATION);
        }
        if (dependencies != null) {
            capabilities.add(Capability.DEPENDENCY_SCAN);
        }
        if (generator != null) {
            // A generator without an executor cannot run what it generates; requiring both is what stops an adapter
            // from advertising generated tests it has no way to execute.
            capabilities.add(Capability.PROPERTY_TESTING);
            if (executor != null) {
                capabilities.add(Capability.GENERATED_TESTS);
            }
        }
        return Set.copyOf(capabilities);
    }

    /**
     * Registers every provider in this set.
     *
     * @param providers       the registry to fill
     * @param includeAnalysis whether to register the analysis providers
     */
    public void registerInto(VerificationProviderRegistry providers, boolean includeAnalysis) {
        Objects.requireNonNull(providers, "providers");
        if (compiler != null) {
            providers.register(compiler);
        }
        if (testRunner != null) {
            providers.register(testRunner);
        }
        if (coverage != null) {
            providers.register(coverage);
        }
        if (mutation != null) {
            providers.register(mutation);
        }
        if (dependencies != null) {
            providers.register(dependencies);
        }
        if (generator != null) {
            providers.register(generator);
        }
        if (executor != null) {
            providers.register(executor);
        }
        if (includeAnalysis) {
            if (staticAnalyzer != null) {
                providers.register(staticAnalyzer);
            }
            if (securityAnalyzer != null) {
                providers.register(securityAnalyzer);
            }
        }
    }

    /** @return the compiler, when one is declared. */
    public Optional<VerificationTools.Compiler> compiler() {
        return Optional.ofNullable(compiler);
    }

    /** @return the test runner, when one is declared. */
    public Optional<VerificationTools.TestRunner> testRunner() {
        return Optional.ofNullable(testRunner);
    }

    /**
     * Compiles, through the declared compiler.
     *
     * @param context verification context
     * @return the build result
     * @throws IllegalStateException when no compiler is declared, which a caller should have prevented by consulting
     *     {@link #capabilities()}
     */
    public BuildResult build(VerificationContext context) {
        if (compiler == null) {
            throw new IllegalStateException("this adapter declares no compiler, so it cannot build; the engine "
                    + "schedules the build phase from Capability.BUILD");
        }
        return compiler.build(context);
    }

    /**
     * Runs the declared test runner.
     *
     * @param context verification context
     * @return the test result
     * @throws IllegalStateException when no runner is declared
     */
    public TestResult runTests(VerificationContext context) {
        if (testRunner == null) {
            throw new IllegalStateException("this adapter declares no test runner, so it cannot run tests; the engine "
                    + "schedules the test phase from Capability.TEST");
        }
        return testRunner.runTests(context);
    }

    /**
     * Runs the declared static analyzer.
     *
     * @param context verification context
     * @return findings, empty when no analyzer is declared
     */
    public List<Finding> analyze(VerificationContext context) {
        return staticAnalyzer == null ? List.of() : staticAnalyzer.analyze(context);
    }

    /** Collects the providers one adapter declares. */
    public static final class Builder {

        private VerificationTools.Compiler compiler;
        private VerificationTools.TestRunner testRunner;
        private VerificationTools.StaticAnalyzer staticAnalyzer;
        private VerificationTools.SecurityAnalyzer securityAnalyzer;
        private VerificationTools.CoverageProvider coverage;
        private VerificationTools.MutationTester mutation;
        private VerificationTools.DependencyAnalyzer dependencies;
        private VerificationTools.TestGenerator generator;
        private VerificationTools.GeneratedTestExecutor executor;

        private Builder() {
        }

        /** @param value the language's compiler @return this builder */
        public Builder compiler(VerificationTools.Compiler value) {
            this.compiler = value;
            return this;
        }

        /** @param value the language's test runner @return this builder */
        public Builder testRunner(VerificationTools.TestRunner value) {
            this.testRunner = value;
            return this;
        }

        /** @param value the language's static analyzer @return this builder */
        public Builder staticAnalyzer(VerificationTools.StaticAnalyzer value) {
            this.staticAnalyzer = value;
            return this;
        }

        /** @param value the security rule table @return this builder */
        public Builder securityAnalyzer(VerificationTools.SecurityAnalyzer value) {
            this.securityAnalyzer = value;
            return this;
        }

        /** @param value the coverage provider @return this builder */
        public Builder coverage(VerificationTools.CoverageProvider value) {
            this.coverage = value;
            return this;
        }

        /** @param value the mutation tester @return this builder */
        public Builder mutation(VerificationTools.MutationTester value) {
            this.mutation = value;
            return this;
        }

        /** @param value the dependency analyzer @return this builder */
        public Builder dependencies(VerificationTools.DependencyAnalyzer value) {
            this.dependencies = value;
            return this;
        }

        /** @param value the scenario test generator @return this builder */
        public Builder generator(VerificationTools.TestGenerator value) {
            this.generator = value;
            return this;
        }

        /** @param value the generated-test executor @return this builder */
        public Builder executor(VerificationTools.GeneratedTestExecutor value) {
            this.executor = value;
            return this;
        }

        /** @return the immutable set. */
        public AdapterProviders build() {
            return new AdapterProviders(this);
        }
    }
}
