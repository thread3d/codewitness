package dev.codewitness.core.engine.strategy;

import dev.codewitness.core.spi.VerificationProviderRegistry;
import dev.codewitness.core.spi.VerificationStrategy;

import java.util.List;

/**
 * The verification strategies CodeWitness ships, as a factory rather than a hard coded list.
 *
 * <p>Strategies are providers exactly like {@link dev.codewitness.core.spi.VerificationTools}: the
 * composition root registers the ones it wants and the engine runs whatever is registered. This class
 * is the default set, kept in its own type so that "which strategies ship" is a fact stated once - by
 * the composition root, next to where it registers the language adapters - instead of a list the engine
 * builds for itself.
 *
 * <p>The order is the order the engine has always run them in, and it matters: existing tests are
 * consulted before generated ones, because a failing test the project already had is the strongest
 * counterexample, and the mutation verdict is reached only after the evidence that could excuse a
 * surviving mutant has been recorded.
 *
 * <p>A strategy reads the requirement-to-code mapping from
 * {@link dev.codewitness.core.spi.VerificationContext#codeMap()} rather than being constructed with
 * it, which is what makes registration here possible at all: the mapping is a per-run fact the engine
 * creates after the project is inspected.
 */
public final class DefaultStrategies {

    private DefaultStrategies() {
    }

    /**
     * @param providers the registry the run's strategies read their tools from
     * @return the built-in strategies, in the order they must run
     */
    public static List<VerificationStrategy> create(VerificationProviderRegistry providers) {
        return List.of(
                new ExistingTestsStrategy(providers),
                new StaticAnalysisStrategy(),
                new DependencyAdvisoryStrategy(providers),
                // Before the generated-test strategy: a process scenario is a declared experiment like any other, and
                // one that passes should discharge its obligation rather than leaving it for a generator that cannot
                // express it.
                new ProcessScenarioStrategy(),
                new GeneratedTestsStrategy(providers),
                new MutationStrategy(),
                new LlmAdvisoryStrategy());
    }
}
