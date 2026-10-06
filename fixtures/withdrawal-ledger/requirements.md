# R-001 Rolling withdrawal limit

A customer may withdraw at most 1,000 during any rolling 24-hour period.
The limit is not a calendar day limit: a withdrawal at 23:30 followed by a withdrawal at 00:30 the
next day must respect the same limit, because only one hour has elapsed.

A withdrawal that would exceed the limit must be rejected.

# R-002 Fees are applied to the recorded total

Applying a fee of 0.50 to a recorded total of 100 must leave the recorded total at 100.50.

# R-003 Negative amounts are rejected

A withdrawal of a negative amount must be rejected and must not change the recorded total.

# R-004 Multiple concurrent withdrawal requests

When several withdrawal requests for the same account are processed concurrently, the total
accepted must never exceed 1,000. The system must behave correctly when multiple withdrawal requests
occur concurrently.

# R-005 Rounding of computed totals

Money arithmetic must be exact to the cent. Totals are reported with two decimal places.
