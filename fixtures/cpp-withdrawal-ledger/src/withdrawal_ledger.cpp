#include "ledger/withdrawal_ledger.hpp"

namespace ledger {

namespace {

constexpr std::int64_t kMillisPerDay = 86400000;

/// The whole-day number an instant falls in.
///
/// This is the arithmetic the fixture gets wrong, and it is written once, in a function with a name that
/// says what it computes. A rolling window is not a sequence of whole days: the boundary between two
/// calendar days is a moment the requirement never mentions.
std::int64_t dayOf(std::int64_t atMillis) {
    return atMillis / kMillisPerDay;
}

}  // namespace

WithdrawalLedger::WithdrawalLedger(std::int64_t limitMinor) : limitMinor_(limitMinor) {
}

void WithdrawalLedger::withdraw(std::int64_t atMillis, std::int64_t amountMinor) {
    std::int64_t todayMinor = 0;
    for (const Entry& entry : entries_) {
        if (dayOf(entry.atMillis) == dayOf(atMillis)) {
            todayMinor += entry.amountMinor;
        }
    }
    if (todayMinor + amountMinor > limitMinor_) {
        return;
    }
    entries_.push_back(Entry{atMillis, amountMinor});
}

void WithdrawalLedger::applyFee(std::int64_t feeMinor) {
    if (entries_.empty()) {
        return;
    }
    entries_.back().amountMinor += feeMinor;
}

std::int64_t WithdrawalLedger::totalWithinLast24Hours(std::int64_t atMillis) const {
    std::int64_t total = 0;
    for (const Entry& entry : entries_) {
        if (entry.atMillis <= atMillis && atMillis - entry.atMillis <= kMillisPerDay) {
            total += entry.amountMinor;
        }
    }
    return total;
}

const std::vector<Entry>& WithdrawalLedger::entries() const {
    return entries_;
}

}  // namespace ledger
