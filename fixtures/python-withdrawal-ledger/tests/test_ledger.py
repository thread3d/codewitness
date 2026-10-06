"""Existing tests for the Python fixture.

These are the tests a developer would have written alongside the code. They pass, which is the
point: CodeWitness must not treat a passing suite written by the same process as independent
verification, and must find the defects through the declared scenarios instead.
"""

import unittest
from decimal import Decimal

from ledger import WithdrawalLedger


class LedgerTest(unittest.TestCase):
    def test_fee_is_added_to_the_recorded_total(self):
        """R-002: applying a fee must change the recorded total."""
        ledger = WithdrawalLedger()
        ledger.withdraw(0, Decimal("100"))
        ledger.apply_fee(Decimal("0.50"))
        self.assertEqual(Decimal("100.50"), ledger.total_within_last_24_hours(3600000))

    def test_totals_are_exact_to_the_cent(self):
        """Money arithmetic must be exact; this is a supporting test, not a requirement claim."""
        ledger = WithdrawalLedger()
        ledger.withdraw(0, Decimal("0.10"))
        ledger.withdraw(1000, Decimal("0.20"))
        self.assertEqual(Decimal("0.30"), ledger.total_within_last_24_hours(2000))
