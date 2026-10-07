//! The Rust half of the ledger. R-003 lives here.

/// R-003: the running total of a list of amounts.
pub fn total(amounts: &[i64]) -> i64 {
    amounts.iter().sum()
}

#[cfg(test)]
mod tests {
    use super::total;

    #[test]
    fn totals_the_amounts() {
        // R-003: the running total of a list of amounts.
        assert_eq!(350, total(&[100, 250]));
    }

    #[test]
    fn totals_nothing_as_zero() {
        assert_eq!(0, total(&[]));
    }
}
