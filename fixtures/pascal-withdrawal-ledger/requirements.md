# R-001 An accepted withdrawal reduces the balance by exactly the amount

A withdrawal that is accepted must leave the account at the previous balance minus exactly the
requested amount. One cent of drift in either direction is a defect, because the ledger is money.

# R-002 A withdrawal larger than the balance is rejected

A withdrawal larger than the current balance must be rejected, and the balance must be unchanged.

# R-003 A withdrawal of exactly the balance is accepted

A withdrawal of the entire balance is valid: it must be accepted and leave a balance of zero. The
boundary between "allowed" and "rejected" is inclusive at the balance.

# R-004 The balance never goes negative

For any non-negative requested amount, the resulting balance must never be negative, whichever branch
the code takes. The requirement is stated independently of the branches above, so it is the one left
without a scenario in this fixture: the report must say it was not verified rather than quietly count a
passing build as evidence.
