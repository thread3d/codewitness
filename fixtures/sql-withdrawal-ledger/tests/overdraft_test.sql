-- R-003
--
-- An overdraft is inserted and then asserted away. The insert is accepted because the schema has no
-- lower-bound check on the balance, and the assertion query then returns the row it should not find -
-- which is the counterexample.
INSERT INTO ledger (id, amount, balance) VALUES (1, 100, -50);

SELECT id, balance FROM ledger WHERE balance < 0;
