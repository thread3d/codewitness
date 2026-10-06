"""Withdrawal ledger used by the CodeWitness Python fixture.

The module contains two deliberately introduced defects behind the requirements in
``requirements.md``:

* the withdrawal limit is applied per calendar day instead of over a rolling 24-hour window (R-001);
* a negative withdrawal amount is accepted and reduces the recorded total (R-003).

It exists so that ``codewitness verify`` can be shown finding a real defect, attributing it to the
requirement it violates, and producing a replayable counterexample.
"""

from datetime import datetime, timezone
from decimal import Decimal

LIMIT = Decimal("1000")


class WithdrawalLedger:
    """Records withdrawals and answers questions about the recorded total."""

    def __init__(self):
        self._entries = []
        self._fee = Decimal("0")

    # R-003 negative amounts are rejected
    def withdraw(self, at_millis, amount):
        """Record a withdrawal at ``at_millis`` (epoch milliseconds).

        Returns ``True`` when the withdrawal was accepted.
        """
        amount = Decimal(amount)
        # DEFECT (R-003): a negative amount is accepted and reduces the recorded total, instead of
        # being rejected.
        bucket = self._calendar_day(at_millis)
        recorded = sum(
            (value for (when, value) in self._entries if self._calendar_day(when) == bucket),
            Decimal("0"),
        )
        if recorded + amount > LIMIT:
            return False
        self._entries.append((at_millis, amount))
        return True

    # R-001 rolling withdrawal limit
    def _calendar_day(self, at_millis):
        # DEFECT (R-001): the limit is applied to the calendar day of the withdrawal, not to the
        # rolling 24 hours that precede it, so a withdrawal either side of midnight escapes the limit.
        return datetime.fromtimestamp(at_millis / 1000, tz=timezone.utc).date()

    def apply_fee(self, fee):
        """Add a fee to the recorded total."""
        self._fee += Decimal(fee)

    def total_within_last_24_hours(self, at_millis):
        """Return the total recorded in the 24 hours before ``at_millis``, including fees."""
        cutoff = at_millis - 24 * 60 * 60 * 1000
        total = sum(
            (value for (when, value) in self._entries if when >= cutoff),
            Decimal("0"),
        )
        return total + self._fee
