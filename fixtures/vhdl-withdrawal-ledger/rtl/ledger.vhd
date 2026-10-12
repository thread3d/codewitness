-- Withdrawal ledger used by the CodeWitness VHDL fixture.
--
-- The entity is combinational: the balance and the requested amount are its inputs, and whether the
-- withdrawal is accepted and the resulting balance are its outputs. It contains two deliberately
-- introduced defects behind the requirements in `requirements.md`:
--
--  - R-001: an accepted withdrawal adds one cent back, so the balance is not reduced by exactly the
--    amount that was requested;
--  - R-003: the comparison is `<` rather than `<=`, so a withdrawal of exactly the balance is
--    rejected instead of emptying the account.
--
-- R-002 (an over-limit withdrawal is rejected and changes nothing) is implemented correctly. A
-- requirement that is genuinely satisfied should be verified, not merely reported, which is what the
-- fixture is for.
--
-- Money is held in integer cents. A design that carried a running total in real arithmetic would make
-- an exact boundary unanswerable, and the fixture exists to show a real defect being found rather than
-- a simulator artefact.

library ieee;
use ieee.std_logic_1164.all;
use ieee.numeric_std.all;

entity ledger is
  port (
    balance     : in  unsigned(31 downto 0);
    amount      : in  unsigned(31 downto 0);
    accepted    : out std_logic;
    new_balance : out unsigned(31 downto 0)
  );
end entity ledger;

architecture rtl of ledger is
begin

  -- DEFECT (R-003): a withdrawal of exactly the balance fails this test and is rejected.
  process(all)
  begin
    if amount < balance then
      accepted    <= '1';
      -- DEFECT (R-001): the accepted branch returns one cent too much.
      new_balance <= balance - amount + 1;
    else
      -- R-002: an over-limit withdrawal is rejected and leaves the balance unchanged.
      accepted    <= '0';
      new_balance <= balance;
    end if;
  end process;

end architecture rtl;
