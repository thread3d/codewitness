// Package ledger is the Go half of the ledger. R-004 lives here.
package ledger

// Total is R-004: the running total of a list of amounts.
func Total(amounts []int64) int64 {
	var sum int64
	for _, amount := range amounts {
		sum += amount
	}
	return sum
}
