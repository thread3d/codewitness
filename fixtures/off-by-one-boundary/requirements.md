# R-001 A withdrawal of exactly the daily limit is accepted

A customer may withdraw at most 1,000.00 per day. The limit itself is inside the permitted range:
a withdrawal of exactly 1,000.00 must be accepted. Only a withdrawal that would take the recorded
total strictly above 1,000.00 must be rejected.

# R-002 A withdrawal strictly below the daily limit is accepted and recorded

Any withdrawal strictly below the daily limit of 1,000.00 must be accepted and must become the
recorded total, so the total after a single such withdrawal equals the amount withdrawn.
