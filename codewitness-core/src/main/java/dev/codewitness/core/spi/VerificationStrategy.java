package dev.codewitness.core.spi;

import dev.codewitness.core.evidence.Evidence;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.result.Counterexample;
import dev.codewitness.core.result.ObligationOutcome;

import java.util.List;

/**
 * A strategy that attempts to discharge a requirement's obligations.
 *
 * <p>Strategies are the "experiment" step of the evidence chain. They may only report observations
 * they actually made; the verdict itself is computed afterwards by the engine from all evidence.
 */
public interface VerificationStrategy {

    /** @return identifier of the strategy, for example {@code generated-tests} or {@code mutation-testing}. */
    String id();

    /** @return {@code true} when this strategy can run against the project. */
    boolean supports(ProjectInfo project);

    /**
     * Attempts the obligations of one requirement.
     *
     * @param context     verification context
     * @param requirement requirement being verified
     * @param obligations obligations to attempt
     * @return evidence, counterexamples and per-obligation outcomes
     */
    StrategyOutcome verify(
            VerificationContext context,
            Requirement requirement,
            List<VerificationObligation> obligations);
}
