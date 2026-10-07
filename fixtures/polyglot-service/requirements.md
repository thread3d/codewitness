# R-001 The Java ledger totals a list of amounts

`example.Ledger.total(long...)` returns the sum of the amounts it is given.
An empty list totals zero.

# R-002 The Python ledger totals a list of amounts

`ledger.total(amounts)` returns the sum of the amounts it is given.
An empty list totals zero.

# R-003 The Rust ledger totals a list of amounts

`polyglot_ledger::total(&[i64])` returns the sum of the amounts it is given.
An empty slice totals zero.

# R-004 The Go ledger totals a list of amounts

`ledger.Total([]int64)` returns the sum of the amounts it is given.
An empty slice totals zero.

# R-005 The TypeScript ledger totals a list of amounts

`total(readonly number[])` returns the sum of the amounts it is given.
An empty array totals zero.

# R-006 The JavaScript tool formats minor units

`format(minorUnits)` renders an amount in minor units as a decimal string with two places.
