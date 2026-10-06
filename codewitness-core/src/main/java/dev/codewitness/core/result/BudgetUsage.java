package dev.codewitness.core.result;

import java.time.Duration;
import java.time.Instant;

/**
 * A snapshot of budget consumption. Verification stops when a limit is reached, and the report says
 * which limit stopped it - a truncated verification must never look like a completed one.
 */
public record BudgetUsage(
        Duration elapsed,
        int testGenerationIterations,
        int generatedTests,
        int llmCalls,
        boolean exhausted,
        String exhaustedReason) {

    public static BudgetUsage empty() {
        return new BudgetUsage(Duration.ZERO, 0, 0, 0, false, null);
    }

    /** Mutable accumulator used while a run is in progress. */
    public static final class Tracker {
        private final Budget budget;
        private final Instant start;
        private int iterations;
        private int generatedTests;
        private int llmCalls;
        private String exhaustedReason;

        public Tracker(Budget budget) {
            this.budget = budget;
            this.start = Instant.now();
        }

        public void recordIteration() {
            iterations++;
        }

        public void recordGeneratedTests(int count) {
            generatedTests += count;
        }

        public void recordLlmCall() {
            llmCalls++;
        }

        public void markExhausted(String reason) {
            if (exhaustedReason == null) {
                exhaustedReason = reason;
            }
        }

        public boolean isExhausted() {
            return exhaustedReason != null;
        }

        public Duration elapsed() {
            return Duration.between(start, Instant.now());
        }

        public boolean timeExpired() {
            return elapsed().compareTo(budget.maxDuration()) >= 0;
        }

        /**
         * @return {@code true} when another batch of generated tests may be produced.
         *
         * <p>Note that {@code max_test_generation_iterations} is deliberately <em>not</em> consulted
         * here: it bounds passes of the verification loop, not the number of requirements that may be
         * attempted. Conflating the two would silently stop verifying after the first few
         * requirements, which would look like "nothing to verify" in the report.
         */
        public boolean canGenerateTests() {
            return !isExhausted()
                    && generatedTests < budget.maxGeneratedTests()
                    && !timeExpired();
        }

        /** @return {@code true} when the verification loop may begin another pass. */
        public boolean canStartNewIteration() {
            return !isExhausted() && iterations < budget.maxTestGenerationIterations() && !timeExpired();
        }

        public boolean canCallLlm() {
            return !isExhausted() && llmCalls < budget.maxLlmCalls() && !timeExpired();
        }

        public Budget budget() {
            return budget;
        }

        /** @return {@code true} when the mutation phase still has time available. */
        public boolean mutationTimeAvailable() {
            return !timeExpired();
        }

        public Duration remaining() {
            Duration remaining = budget.maxDuration().minus(elapsed());
            return remaining.isNegative() ? Duration.ZERO : remaining;
        }

        public BudgetUsage snapshot() {
            return new BudgetUsage(elapsed(), iterations, generatedTests, llmCalls,
                    isExhausted() || timeExpired(),
                    exhaustedReason != null ? exhaustedReason
                            : (timeExpired() ? "max_duration " + budget.maxDuration() + " reached" : null));
        }
    }
}
