// Withdrawal ledger used by the CodeWitness Swift fixture.
//
// The class contains two deliberately introduced defects behind the requirements in
// `requirements.md`:
//
//  - the withdrawal limit is applied per calendar day instead of over a rolling 24-hour window (R-001);
//  - a negative withdrawal amount is accepted and reduces the recorded total (R-003).
//
// Money is held in integer cents rather than in floating point. A ledger that rounded binary floating
// point values would make R-004 unanswerable, and the fixture exists to show a real defect being found,
// not a rounding artefact.
public final class WithdrawalLedger {

    /// The limit in cents: 1,000.00.
    public static let limit = 100_000

    private static let millisPerDay = 86_400_000

    /// Entries as (epoch millis, cents).
    private var entries: [(at: Int, amount: Int)] = []

    /// Fees applied so far, in cents.
    private var fee = 0

    public init() {}

    /// Record a withdrawal at `atMillis` (epoch milliseconds).
    ///
    /// - Returns: `true` when the withdrawal was accepted.
    @discardableResult
    public func withdraw(_ atMillis: Int, _ amount: Int) -> Bool {
        // DEFECT (R-003): a negative amount is accepted and reduces the recorded total, instead of
        // being rejected.
        let bucket = calendarDay(atMillis)
        let recorded = entries
            .filter { calendarDay($0.at) == bucket }
            .reduce(0) { $0 + $1.amount }
        if recorded + amount > Self.limit {
            return false
        }
        entries.append((atMillis, amount))
        return true
    }

    /// Add a fee, in cents, to the recorded total.
    public func applyFee(_ value: Int) {
        fee += value
    }

    /// The total recorded in the 24 hours before `atMillis`, in cents, including fees.
    public func totalWithinLast24Hours(_ atMillis: Int) -> Int {
        let cutoff = atMillis - Self.millisPerDay
        let recorded = entries
            .filter { $0.at >= cutoff }
            .reduce(0) { $0 + $1.amount }
        return recorded + fee
    }

    /// The calendar day of `atMillis` as days since the epoch.
    private func calendarDay(_ atMillis: Int) -> Int {
        // DEFECT (R-001): the limit is applied to the calendar day of the withdrawal, not to the
        // rolling 24 hours that precede it, so a withdrawal either side of midnight escapes the limit.
        atMillis / Self.millisPerDay
    }
}
