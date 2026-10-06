// Package ledger is the withdrawal ledger used by the CodeWitness Go fixture.
//
// It contains two deliberately introduced defects behind the requirements in requirements.md:
//
//   - the withdrawal limit is applied per calendar day instead of over a rolling 24-hour window
//     (R-001);
//   - a negative withdrawal amount is accepted and reduces the recorded total (R-003).
//
// It exists so that `codewitness verify` can be shown finding a real defect in Go code, attributing
// it to the requirement it violates, and producing a replayable counterexample. The project's own
// tests pass, which is the point.
package ledger

// LimitCents is the most a customer may withdraw in the rolling window, in minor units (cents).
const LimitCents int64 = 100_000

// WithdrawalLedger records withdrawals and answers questions about the recorded total.
type WithdrawalLedger struct {
	entries  []entry
	feeCents int64
}

type entry struct {
	atMillis    int64
	amountCents int64
}

// New creates an empty ledger.
func New() *WithdrawalLedger {
	return &WithdrawalLedger{}
}

// Withdraw records a withdrawal of amountCents at atMillis (epoch milliseconds).
//
// It returns true when the withdrawal was accepted.
//
// R-003 negative amounts are rejected.
func (l *WithdrawalLedger) Withdraw(atMillis, amountCents int64) bool {
	// DEFECT (R-003): a negative amount is accepted and reduces the recorded total, instead of being
	// rejected.
	bucket := calendarDay(atMillis)
	var recorded int64
	for _, e := range l.entries {
		if calendarDay(e.atMillis) == bucket {
			recorded += e.amountCents
		}
	}
	if recorded+amountCents > LimitCents {
		return false
	}
	l.entries = append(l.entries, entry{atMillis: atMillis, amountCents: amountCents})
	return true
}

// ApplyFee adds a fee to the recorded total.
func (l *WithdrawalLedger) ApplyFee(feeCents int64) {
	l.feeCents += feeCents
}

// TotalWithinLast24Hours returns the total recorded in the 24 hours before atMillis, including fees.
func (l *WithdrawalLedger) TotalWithinLast24Hours(atMillis int64) int64 {
	cutoff := atMillis - 24*60*60*1000
	var total int64
	for _, e := range l.entries {
		if e.atMillis >= cutoff {
			total += e.amountCents
		}
	}
	return total + l.feeCents
}

// calendarDay implements the R-001 rolling withdrawal limit.
func calendarDay(atMillis int64) int64 {
	// DEFECT (R-001): the limit is applied to the calendar day of the withdrawal, not to the rolling
	// 24 hours that precede it, so a withdrawal either side of midnight escapes the limit.
	return atMillis / 86_400_000
}
