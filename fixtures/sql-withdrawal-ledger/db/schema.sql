-- The ledger under test.
--
-- Deliberately defective: the balance has no lower-bound check, so nothing rejects a balance below
-- zero. R-001 says the balance must be at least 0 and R-003 says an overdraft must be rejected; the
-- schema enforces neither, and the run is expected to find that.
-- R-002
CREATE TABLE ledger (
  id INTEGER PRIMARY KEY,
  amount NUMERIC NOT NULL CHECK (amount <= 1000),
  balance NUMERIC NOT NULL
);
