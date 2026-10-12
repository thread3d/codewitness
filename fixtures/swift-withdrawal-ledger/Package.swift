// swift-tools-version:5.9
import PackageDescription

// The withdrawal-ledger fixture as a Swift package.
//
// It has no external dependencies, so `swift build` and `swift test` need no network: XCTest ships
// with the Swift toolchain, which is what makes this fixture runnable in a sandbox that forbids the
// network. The target and test-target names are what XCTest reports a case under, so the detector
// reads them from this manifest.
let package = Package(
    name: "WithdrawalLedger",
    targets: [
        .target(name: "WithdrawalLedger"),
        .testTarget(name: "WithdrawalLedgerTests", dependencies: ["WithdrawalLedger"]),
    ]
)
