# R-001 Rolling 24-hour withdrawal limit

At most 1,000 may be withdrawn in any period of 24 hours. The window is rolling, not a calendar day,
and it is measured from the timestamp of each withdrawal: a withdrawal made exactly 24 hours before
the one being attempted is still inside the window. A withdrawal that would take the total over
1,000 must be rejected and must not change the amount available.
