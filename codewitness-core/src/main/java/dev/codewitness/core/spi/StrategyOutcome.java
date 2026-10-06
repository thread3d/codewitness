package dev.codewitness.core.spi;

import dev.codewitness.core.evidence.Evidence;
import dev.codewitness.core.result.Counterexample;
import dev.codewitness.core.result.ObligationOutcome;

import java.util.List;

/**
 * What a verification strategy observed.
 *
 * @param strategyId      strategy that produced the outcome
 * @param requirementId   requirement the strategy was working on
 * @param evidence        evidence produced
 * @param counterexamples  counterexamples observed by execution
 * @param obligations     per-obligation outcomes
 * @param notes           anything the strategy could not do, surfaced to the report
 */
public record StrategyOutcome(
        String strategyId,
        String requirementId,
        List<Evidence> evidence,
        List<Counterexample> counterexamples,
        List<ObligationOutcome> obligations,
        List<String> notes) {

    public StrategyOutcome {
        evidence = List.copyOf(evidence == null ? List.of() : evidence);
        counterexamples = List.copyOf(counterexamples == null ? List.of() : counterexamples);
        obligations = List.copyOf(obligations == null ? List.of() : obligations);
        notes = List.copyOf(notes == null ? List.of() : notes);
    }

    public static StrategyOutcome empty(String strategyId, String requirementId) {
        return new StrategyOutcome(strategyId, requirementId, List.of(), List.of(), List.of(), List.of());
    }

    public boolean anyCounterexample() {
        return !counterexamples.isEmpty();
    }

    public StrategyOutcome withEvidence(List<Evidence> additional) {
        List<Evidence> merged = new java.util.ArrayList<>(evidence);
        merged.addAll(additional);
        return new StrategyOutcome(strategyId, requirementId, merged, counterexamples, obligations, notes);
    }
}
