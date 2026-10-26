package dev.codewitness.core.engine.strategy;

import dev.codewitness.core.spi.VerificationProviderRegistry;
import dev.codewitness.core.spi.VerificationStrategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The default strategy set is now a registration rather than a list the engine writes itself, so the
 * order it ships in has to be pinned here: a change to it changes which evidence is produced first for
 * every requirement, and the verdict rules are sensitive to that.
 */
class DefaultStrategiesTest {

    @Test
    @DisplayName("the built-in strategies are created in the order the engine has always run them")
    void builtInStrategiesKeepTheirOrder() {
        List<String> ids = DefaultStrategies.create(VerificationProviderRegistry.empty()).stream()
                .map(VerificationStrategy::id)
                .toList();

        assertEquals(List.of(
                "existing-tests",
                "static-analysis",
                "dependency-advisories",
                "process-scenarios",
                "generated-tests",
                "mutation-testing",
                "llm-advisory"), ids);
    }
}
