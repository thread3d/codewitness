"""Existing tests for the Python half of the fixture.

Written as unittest cases rather than bare asserts so that they run under the standard library runner
when pytest is absent, which is the case on a machine that has only an interpreter.
"""

import unittest

import ledger


class LedgerTest(unittest.TestCase):
    def test_total_adds_the_amounts(self):
        """R-002: the running total of a list of amounts."""
        self.assertEqual(350, ledger.total([100, 250]))

    def test_total_of_nothing_is_zero(self):
        self.assertEqual(0, ledger.total([]))
