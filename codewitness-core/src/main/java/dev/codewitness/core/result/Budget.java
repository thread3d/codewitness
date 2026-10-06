package dev.codewitness.core.result;

import java.time.Duration;

/**
 * Limits that keep verification bounded. Without a budget, verification of an arbitrary project is
 * an unbounded search for counterexamples.
 */
public record Budget(
        Duration maxDuration,
        int maxTestGenerationIterations,
        int maxGeneratedTests,
        Duration maxMutationTime,
        int maxLlmCalls) {

    public static Budget defaults() {
        return new Budget(Duration.ofMinutes(15), 5, 500, Duration.ofMinutes(10), 50);
    }

    public Budget {
        if (maxDuration == null || maxDuration.isZero() || maxDuration.isNegative()) {
            maxDuration = Duration.ofMinutes(15);
        }
        if (maxGeneratedTests <= 0) {
            maxGeneratedTests = 500;
        }
        if (maxTestGenerationIterations < 0) {
            maxTestGenerationIterations = 0;
        }
        if (maxLlmCalls < 0) {
            maxLlmCalls = 0;
        }
        if (maxMutationTime == null) {
            maxMutationTime = Duration.ofMinutes(10);
        }
    }

    public Budget withMaxDuration(Duration value) {
        return new Budget(value, maxTestGenerationIterations, maxGeneratedTests, maxMutationTime, maxLlmCalls);
    }

    public Budget withMaxGeneratedTests(int value) {
        return new Budget(maxDuration, maxTestGenerationIterations, value, maxMutationTime, maxLlmCalls);
    }

    public Budget withMaxLlmCalls(int value) {
        return new Budget(maxDuration, maxTestGenerationIterations, maxGeneratedTests, maxMutationTime, value);
    }

    public Budget withMaxTestGenerationIterations(int value) {
        return new Budget(maxDuration, value, maxGeneratedTests, maxMutationTime, maxLlmCalls);
    }

    public Budget withMaxMutationTime(Duration value) {
        return new Budget(maxDuration, maxTestGenerationIterations, maxGeneratedTests, value, maxLlmCalls);
    }
}
