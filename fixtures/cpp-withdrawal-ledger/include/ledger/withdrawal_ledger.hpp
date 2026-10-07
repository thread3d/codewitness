#ifndef LEDGER_WITHDRAWAL_LEDGER_HPP
#define LEDGER_WITHDRAWAL_LEDGER_HPP

#include <cstdint>
#include <vector>

namespace ledger {

/// A withdrawal recorded against a customer's account.
struct Entry {
    /// When the withdrawal happened, in milliseconds since the epoch.
    std::int64_t atMillis;
    /// What was withdrawn, in minor units: 10000 is 100.00.
    std::int64_t amountMinor;
};

/// Records withdrawals and answers what has been withdrawn recently.
///
/// The ledger is deliberately small: it holds the entries a customer has made, refuses one that would
/// take the account past the limit it was configured with, and reports the total within a window.
class WithdrawalLedger {
public:
    /// The default limit: 1,000.00 in minor units.
    static constexpr std::int64_t kDefaultLimitMinor = 100000;

    explicit WithdrawalLedger(std::int64_t limitMinor = kDefaultLimitMinor);

    /// Records a withdrawal at the given instant, unless it would pass the limit.
    ///
    /// Amounts are minor units and may be negative in this fixture; see the repository's requirements
    /// for what the ledger is supposed to do with one.
    void withdraw(std::int64_t atMillis, std::int64_t amountMinor);

    /// Adds a fee to the most recent withdrawal, in minor units.
    void applyFee(std::int64_t feeMinor);

    /// The total withdrawn in the 24 hours ending at the given instant, in minor units.
    std::int64_t totalWithinLast24Hours(std::int64_t atMillis) const;

    /// Every entry the ledger holds, oldest first.
    const std::vector<Entry>& entries() const;

private:
    std::int64_t limitMinor_;
    std::vector<Entry> entries_;
};

}  // namespace ledger

#endif  // LEDGER_WITHDRAWAL_LEDGER_HPP
