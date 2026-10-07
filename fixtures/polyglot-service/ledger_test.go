package ledger

import "testing"

// TestTotalAddsTheAmounts is R-004: the running total of a list of amounts.
func TestTotalAddsTheAmounts(t *testing.T) {
	if got := Total([]int64{100, 250}); got != 350 {
		t.Fatalf("Total = %d, want 350", got)
	}
}

func TestTotalOfNothingIsZero(t *testing.T) {
	if got := Total(nil); got != 0 {
		t.Fatalf("Total = %d, want 0", got)
	}
}
