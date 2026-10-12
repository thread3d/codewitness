# Withdrawal ledger

## R-001 The balance must be at least 0

A ledger balance must be at least 0 at all times.

## R-002 The amount must not exceed 1000

A single ledger amount must not exceed 1000.

## R-003 A withdrawal beyond the balance must be rejected

Withdrawing more than the balance must be rejected by the database.

## R-004 Statements are recorded per ledger id

Each ledger statement is recorded against its ledger id.
