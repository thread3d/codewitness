package ledger

import "testing"

// Existing tests for the Go fixture.
//
// These are the tests a developer would have written alongside the code. They pass, which is the
// point: CodeWitness must not treat a passing suite written by the same process as independent
// verification, and must find the defects through the declared scenarios instead.

func TestFeeIsAddedToTheRecordedTotal(t *testing.T) {
	// R-002: applying a fee must change the recorded total.
	l := New()
	l.Withdraw(0, 10_000)
	l.ApplyFee(50)
	if got := l.TotalWithinLast24Hours(3_600_000); got != 10_050 {
		t.Fatalf("total = %d, want 10050", got)
	}
}

func TestTotalsAreTheSumOfRecentEntries(t *testing.T) {
	l := New()
	l.Withdraw(0, 10_000)
	l.Withdraw(1_000, 20_000)
	if got := l.TotalWithinLast24Hours(2_000); got != 30_000 {
		t.Fatalf("total = %d, want 30000", got)
	}
}
