/**
 * Withdrawal ledger used by the CodeWitness JavaScript fixture.
 *
 * The module contains two deliberately introduced defects behind the requirements in
 * `requirements.md`:
 *
 * * the withdrawal limit is applied per calendar day instead of over a rolling 24-hour window
 *   (R-001);
 * * a negative withdrawal amount is accepted and reduces the recorded total (R-003).
 *
 * It exists so that `codewitness verify` can be shown finding a real defect in JavaScript code,
 * attributing it to the requirement it violates, and producing a replayable counterexample.
 * The project's own tests pass, which is the point. Money is held in integer minor units, so the
 * arithmetic is exact.
 */

/** The most a customer may withdraw in the rolling window, in minor units (cents). */
export const LIMIT_MINOR_UNITS = 100_000n;

/** One day in epoch milliseconds, used by the calendar-day defect below. */
const DAY_MILLIS = 86_400_000n;

/** Records withdrawals and answers questions about the recorded total. */
export class WithdrawalLedger {
  #entries = [];
  #fee = 0n;

  /**
   * Records a withdrawal of `amountMinorUnits` at `atMillis` (epoch milliseconds).
   *
   * @returns {boolean} true when the withdrawal was accepted.
   *
   * R-003 negative amounts are rejected.
   */
  withdraw(atMillis, amountMinorUnits) {
    // DEFECT (R-003): a negative amount is accepted and reduces the recorded total, instead of
    // being rejected.
    const bucket = this.calendarDay(atMillis);
    let recorded = 0n;
    for (const entry of this.#entries) {
      if (this.calendarDay(entry.at) === bucket) {
        recorded += entry.amount;
      }
    }
    if (recorded + amountMinorUnits > LIMIT_MINOR_UNITS) {
      return false;
    }
    this.#entries.push({ at: atMillis, amount: amountMinorUnits });
    return true;
  }

  /** Adds a fee to the recorded total. */
  applyFee(feeMinorUnits) {
    this.#fee += feeMinorUnits;
  }

  /** Returns the total recorded in the 24 hours before `atMillis`, including fees. */
  totalWithinLast24Hours(atMillis) {
    const cutoff = atMillis - 24n * 60n * 60n * 1000n;
    let total = 0n;
    for (const entry of this.#entries) {
      if (entry.at >= cutoff) {
        total += entry.amount;
      }
    }
    return total + this.#fee;
  }

  /** R-001 rolling withdrawal limit. */
  calendarDay(atMillis) {
    // DEFECT (R-001): the limit is applied to the calendar day of the withdrawal, not to the
    // rolling 24 hours that precede it, so a withdrawal either side of midnight escapes the limit.
    return atMillis / DAY_MILLIS;
  }
}
