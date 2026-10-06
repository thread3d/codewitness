package com.example.boundary;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The project's own tests.
 *
 * <p>They are deliberately thin: every case is clearly inside or clearly outside the limit, so they
 * pass against the off-by-one defect. The boundary value 1,000.00 is never exercised, which is the
 * whole point of the fixture.
 */
class DailyLimitAccountTest {

    @Test
    void acceptsWithdrawalClearlyInsideTheLimit() {
        DailyLimitAccount account = new DailyLimitAccount();
        assertTrue(account.withdraw(new BigDecimal("999.99")));
    }

    @Test
    void rejectsWithdrawalClearlyAboveTheLimit() {
        DailyLimitAccount account = new DailyLimitAccount();
        assertFalse(account.withdraw(new BigDecimal("1000.01")));
    }
}
