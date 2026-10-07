package com.example.account

import org.junit.jupiter.api.Test
import java.math.BigDecimal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * The project's own tests.
 *
 * They are deliberately thin: they pass against the defective implementation, which is the whole point of
 * the fixture. Note that [rejectsWithdrawalAboveLimit] mentions R-001 in a comment so that CodeWitness can
 * map it to the requirement.
 */
class WithdrawalLedgerTest {

    /** R-001: the limit itself, within one calendar day. */
    @Test
    fun rejectsWithdrawalAboveLimit() {
        val ledger = WithdrawalLedger()
        assertTrue(ledger.withdraw(0L, BigDecimal("1000")))
        val second = WithdrawalLedger()
        assertFalse(second.withdraw(0L, BigDecimal("1000.01")))
    }

    @Test
    fun recordsWithdrawalsForTheDay() {
        val ledger = WithdrawalLedger()
        ledger.withdraw(0L, BigDecimal("250"))
        ledger.withdraw(3_600_000L, BigDecimal("250"))
        assertEquals(0, ledger.totalWithinLast24Hours(3_600_000L).compareTo(BigDecimal("500")))
    }

    @Test
    fun feeCallDoesNotThrow() {
        val ledger = WithdrawalLedger()
        ledger.withdraw(0L, BigDecimal("100"))
        ledger.applyFee(BigDecimal("2"))
    }
}
