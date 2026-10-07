<?php

declare(strict_types=1);

namespace CodeWitness\Fixture\Tests;

use CodeWitness\Fixture\WithdrawalLedger;
use PHPUnit\Framework\TestCase;

/**
 * The tests a developer would have written alongside the ledger.
 *
 * They pass, which is the point: CodeWitness must not treat a passing suite written by the same process
 * as independent verification, and must find the defects through the declared scenarios instead.
 */
final class WithdrawalLedgerTest extends TestCase
{
    /** R-002: applying a fee must change the recorded total. */
    public function testFeeIsAddedToTheRecordedTotal(): void
    {
        $ledger = new WithdrawalLedger();
        $ledger->withdraw(0, 10000);
        $ledger->applyFee(50);

        self::assertSame(10050, $ledger->totalWithinLast24Hours(3600000));
    }

    /** Money arithmetic must be exact; this is a supporting test, not a requirement claim. */
    public function testTotalsAreExactToTheCent(): void
    {
        $ledger = new WithdrawalLedger();
        $ledger->withdraw(0, 10);
        $ledger->withdraw(1000, 20);

        self::assertSame(30, $ledger->totalWithinLast24Hours(2000));
    }
}
