# R-001 Rolling withdrawal limit

A customer may withdraw at most 1,000 during any rolling 24-hour period.
The limit is not a calendar day limit: a withdrawal at 23:50 followed by a withdrawal at 01:00 the
next day must respect the same limit, because only one hour and ten minutes have elapsed.

A withdrawal that would exceed the limit must be rejected.

# R-002 Fees are applied to the recorded total

Applying a fee of 0.50 to a recorded total of 100 must leave the recorded total at 100.50.

# R-003 Negative amounts are rejected

A withdrawal of a negative amount must be rejected and must not change the recorded total.

# R-004 Rounding of computed totals

Money arithmetic must be exact to the cent. Totals are reported in minor units.
