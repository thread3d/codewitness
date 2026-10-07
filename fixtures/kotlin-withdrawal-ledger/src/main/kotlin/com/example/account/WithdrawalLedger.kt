package com.example.account

import java.math.BigDecimal

/**
 * The withdrawal ledger used by the CodeWitness Kotlin fixture.
 *
 * It contains two deliberately introduced defects behind the requirements in requirements.md:
 *
 *  - the withdrawal limit is applied per calendar day instead of over a rolling 24-hour window (R-001);
 *  - a negative withdrawal amount is accepted and reduces the recorded total (R-003).
 *
 * It exists so that `codewitness verify` can be shown finding a real defect in Kotlin code, attributing it
 * to the requirement it violates, and producing a replayable counterexample. The project's own tests pass,
 * which is the point: a suite written by the same process that wrote the code is not independent
 * verification.
 */
class WithdrawalLedger {

    companion object {
        /** The most a customer may withdraw in the rolling window. */
        val LIMIT: BigDecimal = BigDecimal("1000")
    }

    /** One recorded withdrawal, at epoch milliseconds. */
    data class Entry(val atMillis: Long, val amount: BigDecimal)

    private val entries = ArrayList<Entry>()
    private var feeTotal: BigDecimal = BigDecimal.ZERO

    /**
     * Records a withdrawal of [amount] at [atMillis] (epoch milliseconds).
     *
     * @return true when the withdrawal was accepted.
     */
    fun withdraw(atMillis: Long, amount: BigDecimal): Boolean {
        // DEFECT (R-001): the limit is compared against the total for the calendar day of the withdrawal,
        // not the rolling 24 hours that precede it, so a withdrawal either side of midnight escapes it.
        val day = calendarDay(atMillis)
        var recorded = BigDecimal.ZERO
        for (entry in entries) {
            if (calendarDay(entry.atMillis) == day) {
                recorded = recorded.add(entry.amount)
            }
        }
        if (recorded.add(amount).compareTo(LIMIT) > 0) {
            return false
        }
        // DEFECT (R-003): a negative amount is accepted here and reduces the recorded total, instead of
        // being rejected.
        entries.add(Entry(atMillis, amount))
        return true
    }

    /** Adds a fee to the recorded total. */
    fun applyFee(fee: BigDecimal) {
        feeTotal = feeTotal.add(fee)
    }

    /** @return the total recorded in the 24 hours up to and including [atMillis]. */
    fun totalWithinLast24Hours(atMillis: Long): BigDecimal {
        val cutoff = atMillis - 24L * 60 * 60 * 1000
        var total = feeTotal
        for (entry in entries) {
            if (entry.atMillis >= cutoff) {
                total = total.add(entry.amount)
            }
        }
        return total
    }

    private fun calendarDay(atMillis: Long): Long = Math.floorDiv(atMillis, 86_400_000L)
}
