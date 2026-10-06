package com.example.fees;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The project's own tests for R-001 and R-002.
 *
 * <p>This is a CodeWitness fixture. The suite is deliberately too weak: it executes the zero-fee
 * branch of {@link FeeSchedule#describe(BigDecimal)} but never asserts what it returns, so a mutant
 * that changes the branch's boundary survives. Every test here still passes against the correct
 * implementation, which is why the requirement must not be reported as failed.
 */
class FeeScheduleTest {

    /** R-001: the free limit itself is free, anything above it is charged. */
    @Test
    void chargesTheFeeOnlyAboveTheFreeLimit() {
        FeeSchedule schedule = new FeeSchedule();
        assertEquals(0, schedule.feeFor(new BigDecimal("1000")).compareTo(BigDecimal.ZERO));
        assertEquals(0, schedule.feeFor(new BigDecimal("1000.01")).compareTo(new BigDecimal("2.00")));
    }

    /**
     * R-002: positive fees are rendered.
     *
     * <p>The zero call is here to cover the "free" branch, but its result is never checked. That
     * missing assertion is the deliberate hole in this fixture.
     */
    @Test
    void rendersFeesAsText() {
        FeeSchedule schedule = new FeeSchedule();
        schedule.describe(BigDecimal.ZERO);
        assertEquals("2.00", schedule.describe(new BigDecimal("2.00")));
    }
}
