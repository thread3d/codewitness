//! Existing tests for the Rust fixture.
//!
//! These are the tests a developer would have written alongside the code. They pass, which is the
//! point: CodeWitness must not treat a passing suite written by the same process as independent
//! verification, and must find the defects through the declared scenarios instead.

use withdrawal_ledger::WithdrawalLedger;

#[test]
fn test_fee_is_added_to_the_recorded_total() {
    // R-002: applying a fee must change the recorded total.
    let mut ledger = WithdrawalLedger::new();
    ledger.withdraw(0, 10_000);
    ledger.apply_fee(50);
    assert_eq!(10_050, ledger.total_within_last_24_hours(3_600_000));
}

#[test]
fn test_totals_are_the_sum_of_recent_entries() {
    let mut ledger = WithdrawalLedger::new();
    ledger.withdraw(0, 10_000);
    ledger.withdraw(1_000, 20_000);
    assert_eq!(30_000, ledger.total_within_last_24_hours(2_000));
}
