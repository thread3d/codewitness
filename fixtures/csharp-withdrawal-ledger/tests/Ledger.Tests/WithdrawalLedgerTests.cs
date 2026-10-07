using Xunit;

namespace Ledger.Tests;

/// <summary>
/// The tests a developer would have written alongside the fixture's ledger.
///
/// <para>
/// They pass, which is the point: CodeWitness must not treat a passing suite written by the same process
/// as independent verification, and must find the defects through the declared scenarios instead. The
/// R-002 comment below is what maps this class to that requirement.
/// </para>
/// </summary>
public sealed class WithdrawalLedgerTests
{
    /// <summary>R-002: applying a fee must change the recorded total.</summary>
    [Fact]
    public void FeeIsAddedToTheRecordedTotal()
    {
        var ledger = new WithdrawalLedger();
        ledger.Withdraw(0, 10_000);
        ledger.ApplyFee(50);
        Assert.Equal(10_050, ledger.TotalWithinLast24Hours(3_600_000));
    }

    [Fact]
    public void TotalsAreTheSumOfRecentEntries()
    {
        var ledger = new WithdrawalLedger();
        ledger.Withdraw(0, 10_000);
        ledger.Withdraw(1_000, 20_000);
        Assert.Equal(30_000, ledger.TotalWithinLast24Hours(2_000));
    }
}
