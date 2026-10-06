/**
 * Existing tests for the TypeScript fixture.
 *
 * These are the tests a developer would have written alongside the code. They pass, which is the
 * point: CodeWitness must not treat a passing suite written by the same process as independent
 * verification, and must find the defects through the declared scenarios instead.
 */

import test from "node:test";
import assert from "node:assert/strict";
import { WithdrawalLedger } from "../src/ledger.ts";

test("R-002 a fee is added to the recorded total", () => {
  const ledger = new WithdrawalLedger();
  ledger.withdraw(0n, 10_000n);
  ledger.applyFee(50n);
  assert.equal(ledger.totalWithinLast24Hours(3_600_000n), 10_050n);
});

test("totals are the sum of recent entries", () => {
  const ledger = new WithdrawalLedger();
  ledger.withdraw(0n, 10_000n);
  ledger.withdraw(1_000n, 20_000n);
  assert.equal(ledger.totalWithinLast24Hours(2_000n), 30_000n);
});
