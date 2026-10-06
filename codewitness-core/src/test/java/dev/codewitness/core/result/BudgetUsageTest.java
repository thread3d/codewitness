package dev.codewitness.core.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The budget keeps verification bounded. The subtle rule here is that
 * {@code max_test_generation_iterations} bounds loop passes, not the number of requirements that may
 * be attempted: conflating them would silently stop verifying after the first few requirements and
 * look like "nothing to verify" in the report.
 */
class BudgetUsageTest {

    private static final Budget GENEROUS = new Budget(Duration.ofMinutes(5), 5, 500, Duration.ofMinutes(5), 50);

    @Test
    @DisplayName("a fresh tracker may generate tests and start an iteration")
    void freshTrackerMayWork() {
        BudgetUsage.Tracker tracker = new BudgetUsage.Tracker(GENEROUS);

        assertTrue(tracker.canGenerateTests());
        assertTrue(tracker.canStartNewIteration());
        assertFalse(tracker.isExhausted());
        assertEquals(0, tracker.snapshot().generatedTests());
        assertTrue(tracker.remaining().compareTo(Duration.ZERO) > 0);
    }

    @Test
    @DisplayName("canGenerateTests respects maxGeneratedTests")
    void canGenerateTestsRespectsMaxGeneratedTests() {
        BudgetUsage.Tracker tracker = new BudgetUsage.Tracker(GENEROUS.withMaxGeneratedTests(2));

        assertTrue(tracker.canGenerateTests());
        tracker.recordGeneratedTests(1);
        assertTrue(tracker.canGenerateTests());
        tracker.recordGeneratedTests(1);
        assertFalse(tracker.canGenerateTests());
        assertEquals(2, tracker.snapshot().generatedTests());
    }

    @Test
    @DisplayName("canGenerateTests is NOT limited by max_test_generation_iterations")
    void canGenerateTestsIgnoresIterationLimit() {
        // Design decision: max_test_generation_iterations bounds passes of the verification loop. A
        // requirement may still be attempted (tests generated) after the iteration budget is spent,
        // otherwise later requirements would silently never be verified.
        BudgetUsage.Tracker tracker = new BudgetUsage.Tracker(GENEROUS.withMaxTestGenerationIterations(0));

        assertFalse(tracker.canStartNewIteration(), "loop passes are exhausted");
        assertTrue(tracker.canGenerateTests(), "per-requirement work is still allowed");
    }

    @Test
    @DisplayName("canGenerateTests respects the wall-clock budget")
    void canGenerateTestsRespectsWallClock() throws InterruptedException {
        BudgetUsage.Tracker tracker = new BudgetUsage.Tracker(GENEROUS.withMaxDuration(Duration.ofMillis(20)));

        assertTrue(tracker.canGenerateTests());
        Thread.sleep(80);
        assertTrue(tracker.timeExpired());
        assertFalse(tracker.canGenerateTests());
        assertEquals(Duration.ZERO, tracker.remaining());
    }

    @Test
    @DisplayName("an exhausted tracker allows nothing")
    void exhaustedTrackerAllowsNothing() {
        BudgetUsage.Tracker tracker = new BudgetUsage.Tracker(GENEROUS);
        tracker.markExhausted("max_duration reached");

        assertTrue(tracker.isExhausted());
        assertFalse(tracker.canGenerateTests());
        assertFalse(tracker.canStartNewIteration());
        assertFalse(tracker.canCallLlm());
    }

    @Test
    @DisplayName("snapshot reports exhaustion together with its reason")
    void snapshotReportsExhaustionReason() {
        BudgetUsage.Tracker tracker = new BudgetUsage.Tracker(GENEROUS);
        tracker.markExhausted("max_generated_tests reached");

        BudgetUsage usage = tracker.snapshot();

        assertTrue(usage.exhausted());
        assertEquals("max_generated_tests reached", usage.exhaustedReason());
    }

    @Test
    @DisplayName("snapshot explains a wall-clock exhaustion even without an explicit marker")
    void snapshotExplainsTimeExhaustion() throws InterruptedException {
        BudgetUsage.Tracker tracker = new BudgetUsage.Tracker(GENEROUS.withMaxDuration(Duration.ofMillis(20)));
        Thread.sleep(80);

        BudgetUsage usage = tracker.snapshot();

        assertTrue(usage.exhausted());
        assertTrue(usage.exhaustedReason().contains("max_duration"), usage.exhaustedReason());
    }

    @Test
    @DisplayName("snapshot of a healthy tracker is not exhausted and has no reason")
    void snapshotOfHealthyTracker() {
        BudgetUsage usage = new BudgetUsage.Tracker(GENEROUS).snapshot();

        assertFalse(usage.exhausted());
        assertNull(usage.exhaustedReason());
        assertEquals(0, usage.testGenerationIterations());
        assertEquals(0, usage.llmCalls());
    }

    @Test
    @DisplayName("the first exhaustion reason wins")
    void firstExhaustionReasonWins() {
        BudgetUsage.Tracker tracker = new BudgetUsage.Tracker(GENEROUS);
        tracker.markExhausted("first");
        tracker.markExhausted("second");

        assertEquals("first", tracker.snapshot().exhaustedReason());
    }

    @Test
    @DisplayName("canStartNewIteration respects max_test_generation_iterations")
    void canStartNewIterationRespectsIterations() {
        BudgetUsage.Tracker tracker = new BudgetUsage.Tracker(GENEROUS.withMaxTestGenerationIterations(2));

        tracker.recordIteration();
        assertTrue(tracker.canStartNewIteration());
        tracker.recordIteration();
        assertFalse(tracker.canStartNewIteration());
        assertEquals(2, tracker.snapshot().testGenerationIterations());
    }

    @Test
    @DisplayName("canCallLlm respects maxLlmCalls")
    void canCallLlmRespectsMaxCalls() {
        BudgetUsage.Tracker tracker = new BudgetUsage.Tracker(GENEROUS.withMaxLlmCalls(1));

        assertTrue(tracker.canCallLlm());
        tracker.recordLlmCall();
        assertFalse(tracker.canCallLlm());
        assertEquals(1, tracker.snapshot().llmCalls());
    }

    @Test
    @DisplayName("remaining() never goes negative")
    void remainingNeverNegative() throws InterruptedException {
        BudgetUsage.Tracker tracker = new BudgetUsage.Tracker(GENEROUS.withMaxDuration(Duration.ofMillis(20)));
        Thread.sleep(80);

        assertEquals(Duration.ZERO, tracker.remaining());
    }

    @Test
    @DisplayName("mutationTimeAvailable follows the overall deadline")
    void mutationTimeAvailableFollowsDeadline() throws InterruptedException {
        BudgetUsage.Tracker tracker = new BudgetUsage.Tracker(GENEROUS.withMaxDuration(Duration.ofMillis(20)));
        assertTrue(tracker.mutationTimeAvailable());
        Thread.sleep(80);
        assertFalse(tracker.mutationTimeAvailable());
    }

    @Test
    @DisplayName("Budget normalises unsafe values")
    void budgetNormalisesUnsafeValues() {
        Budget budget = new Budget(null, -1, 0, null, -5);

        assertEquals(Duration.ofMinutes(15), budget.maxDuration());
        assertEquals(0, budget.maxTestGenerationIterations());
        assertEquals(500, budget.maxGeneratedTests());
        assertEquals(Duration.ofMinutes(10), budget.maxMutationTime());
        assertEquals(0, budget.maxLlmCalls());
    }

    @Test
    @DisplayName("Budget with-ers return modified copies")
    void budgetWithErsAreImmutable() {
        Budget budget = Budget.defaults();

        assertEquals(Duration.ofSeconds(1), budget.withMaxDuration(Duration.ofSeconds(1)).maxDuration());
        assertEquals(3, budget.withMaxGeneratedTests(3).maxGeneratedTests());
        assertEquals(2, budget.withMaxTestGenerationIterations(2).maxTestGenerationIterations());
        assertEquals(4, budget.withMaxLlmCalls(4).maxLlmCalls());
        assertEquals(Duration.ofSeconds(2), budget.withMaxMutationTime(Duration.ofSeconds(2)).maxMutationTime());
        assertEquals(Budget.defaults().maxGeneratedTests(), budget.maxGeneratedTests());
    }

    @Test
    @DisplayName("BudgetUsage.empty() is a clean snapshot")
    void budgetUsageEmpty() {
        BudgetUsage usage = BudgetUsage.empty();

        assertEquals(Duration.ZERO, usage.elapsed());
        assertFalse(usage.exhausted());
        assertNull(usage.exhaustedReason());
    }
}
