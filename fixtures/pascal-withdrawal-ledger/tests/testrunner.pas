program testrunner;
{$mode objfpc}{$H+}

{ The project's test entry point: the fpcunit console runner links the suites the test units registered.
  The XML format and the --all switch are what make every test report individually. }

uses
  fpcunit, testregistry, consoletestrunner, ledgertests;

var
  app: TTestRunner;

begin
  app := TTestRunner.Create(nil);
  app.Initialize;
  app.Title := 'CodeWitness Pascal withdrawal-ledger tests';
  app.Run;
  app.Free;
end.
