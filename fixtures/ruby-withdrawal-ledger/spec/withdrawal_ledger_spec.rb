# frozen_string_literal: true

require "withdrawal_ledger"

# The tests a developer would have written alongside the ledger.
#
# They pass, which is the point: CodeWitness must not treat a passing suite written by the same process
# as independent verification, and must find the defects through the declared scenarios instead.
RSpec.describe WithdrawalLedger do
  # R-002: applying a fee must change the recorded total.
  it "adds a fee to the recorded total" do
    ledger = WithdrawalLedger.new
    ledger.withdraw(0, 10_000)
    ledger.apply_fee(50)

    expect(ledger.total_within_last_24_hours(3_600_000)).to eq(10_050)
  end

  # Money arithmetic must be exact; this is a supporting test, not a requirement claim.
  it "keeps totals exact to the cent" do
    ledger = WithdrawalLedger.new
    ledger.withdraw(0, 10)
    ledger.withdraw(1_000, 20)

    expect(ledger.total_within_last_24_hours(2_000)).to eq(30)
  end
end
