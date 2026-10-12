-- R-002
--
-- The declared upper bound is checked from both sides: exactly 1000 is a valid amount, 1001 is not, and
-- the column is required. These are the statements that kill a mutation which loosens the check to
-- `amount < 1000`, or drops it altogether - a boundary nobody asserts is a mutant nobody kills.
INSERT INTO ledger (id, amount, balance) VALUES (1, 1000, 10);

-- expect: error
INSERT INTO ledger (id, amount, balance) VALUES (2, 1001, 10);

-- expect: error
INSERT INTO ledger (id, amount, balance) VALUES (3, NULL, 10);

SELECT id, amount FROM ledger WHERE amount > 1000;
