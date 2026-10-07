# frozen_string_literal: true

# Withdrawal ledger used by the CodeWitness Ruby fixture.
#
# The class contains two deliberately introduced defects behind the requirements in
# `requirements.md`:
#
#  - the withdrawal limit is applied per calendar day instead of over a rolling 24-hour window (R-001);
#  - a negative withdrawal amount is accepted and reduces the recorded total (R-003).
#
# Money is held in integer cents rather than in floating point. A ledger that rounded binary floating
# point values would make R-004 unanswerable, and the fixture exists to show a real defect being found,
# not a rounding artefact.
class WithdrawalLedger
  # The limit in cents: 1,000.00.
  LIMIT = 100_000

  MILLIS_PER_DAY = 86_400_000

  def initialize
    # Entries as [epoch millis, cents].
    @entries = []
    # Fees applied so far, in cents.
    @fee = 0
  end

  # Record a withdrawal at +at_millis+ (epoch milliseconds).
  #
  # @return [Boolean] true when the withdrawal was accepted
  def withdraw(at_millis, amount)
    # DEFECT (R-003): a negative amount is accepted and reduces the recorded total, instead of being
    # rejected.
    bucket = calendar_day(at_millis)
    recorded = @entries
               .select { |when_millis, _value| calendar_day(when_millis) == bucket }
               .sum { |_when_millis, value| value }
    return false if recorded + amount > LIMIT

    @entries << [at_millis, amount]
    true
  end

  # Add a fee, in cents, to the recorded total.
  def apply_fee(fee)
    @fee += fee
  end

  # @return [Integer] the total recorded in the 24 hours before +at_millis+, in cents, including fees
  def total_within_last_24_hours(at_millis)
    cutoff = at_millis - MILLIS_PER_DAY
    total = @entries
            .select { |when_millis, _value| when_millis >= cutoff }
            .sum { |_when_millis, value| value }
    total + @fee
  end

  private

  # @return [Integer] the calendar day of +at_millis+ as days since the epoch
  def calendar_day(at_millis)
    # DEFECT (R-001): the limit is applied to the calendar day of the withdrawal, not to the rolling
    # 24 hours that precede it, so a withdrawal either side of midnight escapes the limit.
    at_millis / MILLIS_PER_DAY
  end
end
