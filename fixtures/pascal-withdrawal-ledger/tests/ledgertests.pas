unit ledgertests;
{$mode objfpc}{$H+}

{ The project's own fpcunit suite. It asserts the ordinary debit (R-001), the rejection branch (R-002)
  and the exact-balance boundary (R-003), which is why the defective arithmetic fails two of its four
  tests. The suite is what the mutation phase uses to judge each mutant. }

interface

uses
  fpcunit, testregistry;

type
  TWithdrawTest = class(TTestCase)
  published
    procedure TestAcceptedReducesByAmount;
    procedure TestOverBalanceRejected;
    procedure TestExactBalanceAccepted;
    procedure TestBoundaryIsInclusive;
  end;

implementation

uses
  ledger;

procedure TWithdrawTest.TestAcceptedReducesByAmount;
begin
  AssertEquals(60000, Withdraw(100000, 40000));
end;

procedure TWithdrawTest.TestOverBalanceRejected;
begin
  AssertEquals(100000, Withdraw(100000, 100001));
end;

procedure TWithdrawTest.TestExactBalanceAccepted;
begin
  AssertEquals(0, Withdraw(100000, 100000));
end;

procedure TWithdrawTest.TestBoundaryIsInclusive;
begin
  AssertTrue('exactly the balance must be accepted', Accepted(100000, 100000));
end;

initialization
  RegisterTest(TWithdrawTest);
end.
