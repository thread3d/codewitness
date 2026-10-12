package dev.codewitness.core.spi;

import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.VerificationObligation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registry is where strategies became pluggable, so its two queries have to be distinct:
 * "did anyone register a strategy?" decides whether the engine runs the built-in set, while "which
 * strategies claim this project?" decides what actually runs. Collapsing them would let a caller's
 * deliberate empty-for-this-project choice silently turn into the engine's own six strategies.
 */
class VerificationProviderRegistryTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("strategies are registered, listed in order, and filtered by project")
    void strategiesAreRegisteredAndQueriedInOrder() {
        VerificationProviderRegistry providers = VerificationProviderRegistry.empty();
        assertFalse(providers.hasStrategies(), "a fresh registry has registered nothing");
        assertTrue(providers.strategies().isEmpty());

        providers.register(new NamedStrategy("first", true));
        providers.register(new NamedStrategy("second", false));

        assertTrue(providers.hasStrategies());
        assertEquals(List.of("first", "second"), providers.strategies().stream()
                .map(VerificationStrategy::id).toList(), "unfiltered listing keeps registration order");

        ProjectInfo project = ProjectInfo.builder(ProjectRef.of(tmp)).build();
        assertEquals(List.of("first"), providers.strategies(project).stream()
                .map(VerificationStrategy::id).toList(),
                "a strategy that declines the project is not a strategy for it");
    }

    /** A strategy whose id and claim are the test's, and which is never executed. */
    private record NamedStrategy(String id, boolean claims) implements VerificationStrategy {

        @Override
        public boolean supports(ProjectInfo project) {
            return claims;
        }

        @Override
        public StrategyOutcome verify(VerificationContext context, Requirement requirement,
                List<VerificationObligation> obligations) {
            throw new AssertionError("this test asserts registration, not execution");
        }
    }
}
