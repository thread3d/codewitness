// The ledger's own tests, run by CTest through add_test(NAME withdrawal_ledger_test ...).
//
// They are hand-written and use no framework: the fixture is meant to be verifiable on a machine that has
// a C++ compiler and CMake and nothing else, and a test framework would be one more thing to install. A
// test binary that prints why it failed and exits non-zero is what CTest reads, and it is all this needs.
//
// These tests pass. The defects the requirements describe are the ones CodeWitness finds with a generated
// test written from codewitness.yaml: they are behaviours no existing test constrains, which is exactly
// the situation a verification run exists for.

#include "ledger/withdrawal_ledger.hpp"

#include <cstdio>

namespace {

int failures = 0;

void check(bool condition, const char* what) {
    if (!condition) {
        std::printf("FAIL %s\n", what);
        ++failures;
    }
}

void aFeeIsAddedToTheMostRecentWithdrawal() {
    ledger::WithdrawalLedger ledger;
    ledger.withdraw(0, 10000);
    ledger.applyFee(50);
    check(ledger.totalWithinLast24Hours(3600000) == 10050, "a fee is added to the total");
}

void aWithdrawalWithinTheLimitIsRecorded() {
    ledger::WithdrawalLedger ledger;
    ledger.withdraw(0, 70000);
    check(ledger.entries().size() == 1, "a withdrawal within the limit is recorded");
    check(ledger.totalWithinLast24Hours(0) == 70000, "the total is what was withdrawn");
}

void aWithdrawalThatWouldPassTheLimitIsRejected() {
    ledger::WithdrawalLedger ledger;
    ledger.withdraw(0, 90000);
    ledger.withdraw(1000, 90000);
    check(ledger.entries().size() == 1, "a withdrawal that would pass the limit is rejected");
}

void aWithdrawalThatBringsTheTotalExactlyToTheLimitIsAccepted() {
    ledger::WithdrawalLedger ledger;
    ledger.withdraw(0, 100000);
    check(ledger.entries().size() == 1, "a withdrawal exactly at the limit is accepted");
}

void theWindowEndsAtTheInstantAskedAbout() {
    ledger::WithdrawalLedger ledger;
    ledger.withdraw(0, 10000);
    check(ledger.totalWithinLast24Hours(86400000) == 10000, "a withdrawal at the edge of the window counts");
    check(ledger.totalWithinLast24Hours(86400001) == 0, "a withdrawal past the window does not");
    check(ledger.totalWithinLast24Hours(-1) == 0, "the window ends at the instant asked about");
}

void theLedgerStartsEmpty() {
    ledger::WithdrawalLedger ledger;
    check(ledger.entries().empty(), "a new ledger holds nothing");
    check(ledger.totalWithinLast24Hours(0) == 0, "a new ledger totals zero");
}

}  // namespace

int main() {
    theLedgerStartsEmpty();
    aWithdrawalWithinTheLimitIsRecorded();
    aWithdrawalThatWouldPassTheLimitIsRejected();
    aWithdrawalThatBringsTheTotalExactlyToTheLimitIsAccepted();
    theWindowEndsAtTheInstantAskedAbout();
    aFeeIsAddedToTheMostRecentWithdrawal();
    if (failures != 0) {
        std::printf("%d check(s) failed\n", failures);
        return 1;
    }
    std::printf("all checks passed\n");
    return 0;
}
