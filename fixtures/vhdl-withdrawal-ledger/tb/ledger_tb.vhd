-- The testbench a developer would have written alongside the ledger.
--
-- It passes, which is the point: CodeWitness must not treat a passing testbench written by the same
-- process as independent verification, and must find the defects through the declared scenarios
-- instead. It checks only the one requirement the design satisfies - R-002 - and never the boundary
-- where the defects live.

library ieee;
use ieee.std_logic_1164.all;
use ieee.numeric_std.all;
use std.env.all;

entity ledger_tb is
end entity ledger_tb;

architecture sim of ledger_tb is
  signal balance     : unsigned(31 downto 0) := (others => '0');
  signal amount      : unsigned(31 downto 0) := (others => '0');
  signal accepted    : std_logic;
  signal new_balance : unsigned(31 downto 0);
begin

  dut : entity work.ledger
    port map (
      balance     => balance,
      amount      => amount,
      accepted    => accepted,
      new_balance => new_balance
    );

  process
  begin
    -- R-002: a withdrawal larger than the balance is rejected and changes nothing.
    balance <= to_unsigned(100000, 32);
    amount  <= to_unsigned(200000, 32);
    wait for 1 ns;

    if accepted /= '0' then
      report "FAIL: an over-limit withdrawal was accepted" severity error;
    end if;
    if new_balance /= to_unsigned(100000, 32) then
      report "FAIL: a rejected withdrawal changed the balance" severity error;
    end if;

    report "PASS: an over-limit withdrawal is rejected and leaves the balance unchanged" severity note;
    std.env.finish;
  end process;

end architecture sim;
