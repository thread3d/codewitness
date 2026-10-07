namespace Ledger;

/// <summary>
/// The withdrawal ledger used by the CodeWitness C# fixture.
///
/// <para>It contains two deliberately introduced defects behind the requirements in requirements.md:</para>
/// <list type="bullet">
///   <item>the withdrawal limit is applied per calendar day instead of over a rolling 24-hour window
///     (R-001);</item>
///   <item>a negative withdrawal amount is accepted and reduces the recorded total (R-003).</item>
/// </list>
/// <para>
/// It exists so that <c>codewitness verify</c> can be shown finding a real defect in C# code, attributing
/// it to the requirement it violates, and producing a replayable counterexample. The project's own tests
/// pass, which is the point: a suite written by the same process that wrote the code is not independent
/// verification.
/// </para>
/// </summary>
public sealed class WithdrawalLedger
{
    /// <summary>The most a customer may withdraw in the rolling window, in minor units (cents).</summary>
    public const long LimitCents = 100_000;

    private readonly List<Entry> _entries = new();
    private long _feeCents;

    private readonly record struct Entry(long AtMillis, long AmountCents);

    /// <summary>
    /// Records a withdrawal of <paramref name="amountCents"/> at <paramref name="atMillis"/> (epoch
    /// milliseconds), returning true when the withdrawal was accepted.
    /// </summary>
    /// <remarks>R-003 negative amounts are rejected.</remarks>
    public bool Withdraw(long atMillis, long amountCents)
    {
        // DEFECT (R-003): a negative amount is accepted and reduces the recorded total, instead of being
        // rejected.
        var bucket = CalendarDay(atMillis);
        var recorded = _entries.Where(e => CalendarDay(e.AtMillis) == bucket).Sum(e => e.AmountCents);
        if (recorded + amountCents > LimitCents)
        {
            return false;
        }
        _entries.Add(new Entry(atMillis, amountCents));
        return true;
    }

    /// <summary>Adds a fee to the recorded total.</summary>
    public void ApplyFee(long feeCents) => _feeCents += feeCents;

    /// <summary>Returns the total recorded in the 24 hours before <paramref name="atMillis"/>.</summary>
    public long TotalWithinLast24Hours(long atMillis)
    {
        var cutoff = atMillis - 24 * 60 * 60 * 1000;
        return _entries.Where(e => e.AtMillis >= cutoff).Sum(e => e.AmountCents) + _feeCents;
    }

    /// <summary>Implements the R-001 rolling withdrawal limit.</summary>
    private static long CalendarDay(long atMillis)
    {
        // DEFECT (R-001): the limit is applied to the calendar day of the withdrawal, not to the rolling
        // 24 hours that precede it, so a withdrawal either side of midnight escapes the limit.
        return atMillis / 86_400_000;
    }
}
