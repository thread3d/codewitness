package com.example.account;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The project's own tests.
 *
 * <p>They are deliberately thin: they pass against the defective implementation, which is the whole
 * point of the fixture. Note that {@link #rejectsWithdrawalAboveLimit()} mentions R-001 in a comment
 * so that CodeWitness can map it to the requirement.
 */
class WithdrawalLedgerTest {

    private static Instant at(int hour, int minute) {
        return LocalDate.of(2026, 10, 6).atTime(hour, minute).toInstant(ZoneOffset.UTC);
    }

    /** R-001: the limit itself. */
    @Test
    void rejectsWithdrawalAboveLimit() {
        WithdrawalLedger ledger = new WithdrawalLedger();
        assertTrue(ledger.withdraw(at(10, 0), new BigDecimal("1000")));
        WithdrawalLedger second = new WithdrawalLedger();
        assertFalse(second.withdraw(at(10, 0), new BigDecimal("1000.01")));
    }

    @Test
    void recordsWithdrawalsForTheDay() {
        WithdrawalLedger ledger = new WithdrawalLedger();
        ledger.withdraw(at(9, 0), new BigDecimal("250"));
        ledger.withdraw(at(11, 0), new BigDecimal("250"));
        assertEquals(0, ledger.totalOn(LocalDate.of(2026, 10, 6)).compareTo(new BigDecimal("500")));
    }

    @Test
    void feeCallDoesNotThrow() {
        WithdrawalLedger ledger = new WithdrawalLedger();
        ledger.withdraw(at(9, 0), new BigDecimal("100"));
        ledger.applyFee(new BigDecimal("2"));
    }
}
