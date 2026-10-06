//! Withdrawal ledger used by the CodeWitness Rust fixture.
//!
//! The crate contains two deliberately introduced defects behind the requirements in
//! `requirements.md`:
//!
//! * the withdrawal limit is applied per calendar day instead of over a rolling 24-hour window
//!   (R-001);
//! * a negative withdrawal amount is accepted and reduces the recorded total (R-003).
//!
//! It exists so that `codewitness verify` can be shown finding a real defect in Rust code,
//! attributing it to the requirement it violates, and producing a replayable counterexample.
//! The project's own tests pass, which is the point.

/// The most a customer may withdraw in the rolling window, in minor units (cents).
pub const LIMIT_CENTS: i64 = 100_000;

/// Records withdrawals and answers questions about the recorded total.
#[derive(Default)]
pub struct WithdrawalLedger {
    entries: Vec<(i64, i64)>,
    fee_cents: i64,
}

impl WithdrawalLedger {
    /// Creates an empty ledger.
    pub fn new() -> WithdrawalLedger {
        WithdrawalLedger {
            entries: Vec::new(),
            fee_cents: 0,
        }
    }

    /// Records a withdrawal of `amount_cents` at `at_millis` (epoch milliseconds).
    ///
    /// Returns `true` when the withdrawal was accepted.
    ///
    /// R-003 negative amounts are rejected.
    pub fn withdraw(&mut self, at_millis: i64, amount_cents: i64) -> bool {
        // DEFECT (R-003): a negative amount is accepted and reduces the recorded total, instead of
        // being rejected.
        let bucket = self.calendar_day(at_millis);
        let recorded: i64 = self
            .entries
            .iter()
            .filter(|(when, _)| self.calendar_day(*when) == bucket)
            .map(|(_, value)| *value)
            .sum();
        if recorded + amount_cents > LIMIT_CENTS {
            return false;
        }
        self.entries.push((at_millis, amount_cents));
        true
    }

    /// Adds a fee to the recorded total.
    pub fn apply_fee(&mut self, fee_cents: i64) {
        self.fee_cents += fee_cents;
    }

    /// Returns the total recorded in the 24 hours before `at_millis`, including fees.
    pub fn total_within_last_24_hours(&self, at_millis: i64) -> i64 {
        let cutoff = at_millis - 24 * 60 * 60 * 1000;
        let total: i64 = self
            .entries
            .iter()
            .filter(|(when, _)| *when >= cutoff)
            .map(|(_, value)| *value)
            .sum();
        total + self.fee_cents
    }

    /// R-001 rolling withdrawal limit.
    fn calendar_day(&self, at_millis: i64) -> i64 {
        // DEFECT (R-001): the limit is applied to the calendar day of the withdrawal, not to the
        // rolling 24 hours that precede it, so a withdrawal either side of midnight escapes the
        // limit.
        at_millis / 86_400_000
    }
}
