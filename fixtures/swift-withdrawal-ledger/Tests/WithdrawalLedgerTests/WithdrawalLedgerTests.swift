import XCTest

@testable import WithdrawalLedger

// The tests a developer would have written alongside the ledger.
//
// They pass, which is the point: CodeWitness must not treat a passing suite written by the same process
// as independent verification, and must find the defects through the declared scenarios instead.
final class WithdrawalLedgerTests: XCTestCase {

    // R-002: applying a fee must change the recorded total.
    func testFeeIsAddedToTheRecordedTotal() {
        let ledger = WithdrawalLedger()
        ledger.withdraw(0, 10_000)
        ledger.applyFee(50)

        XCTAssertEqual(ledger.totalWithinLast24Hours(3_600_000), 10_050)
    }

    // Money arithmetic must be exact; this is a supporting test, not a requirement claim.
    func testTotalsAreExactToTheCent() {
        let ledger = WithdrawalLedger()
        ledger.withdraw(0, 10)
        ledger.withdraw(1_000, 20)

        XCTAssertEqual(ledger.totalWithinLast24Hours(2_000), 30)
    }
}
