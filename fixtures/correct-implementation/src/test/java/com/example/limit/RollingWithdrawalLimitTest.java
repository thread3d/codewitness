package com.example.limit;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The project's own tests for R-001.
 *
 * <p>This is a CodeWitness fixture, and it is deliberately a suite that can fail: each test pins the
 * boundary it is about, including the closed 24-hour window that a calendar-day implementation would
 * get wrong.
 */
class RollingWithdrawalLimitTest {

    private static final Instant START = Instant.parse("2026-10-06T23:30:00Z");

    @Test
    void sharesOneBudgetAcrossMidnight() {
        RollingWithdrawalLimit limit = new RollingWithdrawalLimit();
        assertTrue(limit.withdraw(START, new BigDecimal("700")));
        assertFalse(limit.withdraw(START.plus(Duration.ofHours(1)), new BigDecimal("700")));
        assertEquals(0, limit.totalWithinWindow(START.plus(Duration.ofHours(1)))
                .compareTo(new BigDecimal("700")));
    }

    @Test
    void keepsAWithdrawalExactly24HoursOldInsideTheWindow() {
        RollingWithdrawalLimit limit = new RollingWithdrawalLimit();
        assertTrue(limit.withdraw(START, new BigDecimal("600")));
        assertFalse(limit.withdraw(START.plus(Duration.ofHours(24)), new BigDecimal("600")));
    }

    @Test
    void forgetsAWithdrawalOnceMoreThan24HoursHavePassed() {
        RollingWithdrawalLimit limit = new RollingWithdrawalLimit();
        assertTrue(limit.withdraw(START, new BigDecimal("1000")));
        assertTrue(limit.withdraw(START.plus(Duration.ofHours(24)).plus(Duration.ofMinutes(1)),
                new BigDecimal("1000")));
    }

    @Test
    void rejectsNonPositiveAmounts() {
        RollingWithdrawalLimit limit = new RollingWithdrawalLimit();
        assertFalse(limit.withdraw(START, new BigDecimal("-1")));
        assertFalse(limit.withdraw(START, BigDecimal.ZERO));
        assertEquals(0, limit.totalWithinWindow(START).compareTo(BigDecimal.ZERO));
    }
}
