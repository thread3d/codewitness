unit ledger;
{$mode objfpc}{$H+}

{ A withdrawal ledger. Money is in integer cents: the arithmetic is exact and a one-cent drift is a
  defect, not a rounding artifact. }

interface

{ True when a withdrawal of amount from balance is allowed. The boundary is inclusive. }
function Accepted(balance, amount: Integer): Boolean;

{ The balance after withdrawing amount. A withdrawal that is not accepted leaves the balance unchanged. }
function Withdraw(balance, amount: Integer): Integer;

implementation

function Accepted(balance, amount: Integer): Boolean;
begin
  Result := amount <= balance;
end;

function Withdraw(balance, amount: Integer): Integer;
begin
  if Accepted(balance, amount) then
    { Deliberately defective: the result is one cent short of the balance minus the amount. R-001 and
      R-003 are the requirements this violates. }
    Result := balance - amount - 1
  else
    Result := balance;
end;

end.
