<?php

declare(strict_types=1);

namespace CodeWitness\Fixture;

/**
 * Withdrawal ledger used by the CodeWitness PHP fixture.
 *
 * The class contains two deliberately introduced defects behind the requirements in
 * `requirements.md`:
 *
 *  - the withdrawal limit is applied per calendar day instead of over a rolling 24-hour window (R-001);
 *  - a negative withdrawal amount is accepted and reduces the recorded total (R-003).
 *
 * Money is held in integer cents rather than in floating point. A ledger that rounded binary floating
 * point values would make R-004 unanswerable, and the fixture exists to show a real defect being found,
 * not a rounding artefact.
 */
final class WithdrawalLedger
{
    /** The limit in cents: 1,000.00. */
    public const LIMIT = 100000;

    private const MILLIS_PER_DAY = 86400000;

    /** @var list<array{int, int}> entries as [epochMillis, cents] */
    private array $entries = [];

    /** Fees applied so far, in cents. */
    private int $fee = 0;

    /**
     * Record a withdrawal at `$atMillis` (epoch milliseconds).
     *
     * @return bool true when the withdrawal was accepted
     */
    public function withdraw(int $atMillis, int $amount): bool
    {
        // DEFECT (R-003): a negative amount is accepted and reduces the recorded total, instead of
        // being rejected.
        $bucket = $this->calendarDay($atMillis);
        $recorded = 0;
        foreach ($this->entries as [$when, $value]) {
            if ($this->calendarDay($when) === $bucket) {
                $recorded += $value;
            }
        }
        if ($recorded + $amount > self::LIMIT) {
            return false;
        }
        $this->entries[] = [$atMillis, $amount];
        return true;
    }

    /**
     * @return int the calendar day of `$atMillis` as days since the epoch
     */
    private function calendarDay(int $atMillis): int
    {
        // DEFECT (R-001): the limit is applied to the calendar day of the withdrawal, not to the
        // rolling 24 hours that precede it, so a withdrawal either side of midnight escapes the limit.
        return intdiv($atMillis, self::MILLIS_PER_DAY);
    }

    /** Add a fee, in cents, to the recorded total. */
    public function applyFee(int $fee): void
    {
        $this->fee += $fee;
    }

    /**
     * @return int the total recorded in the 24 hours before `$atMillis`, in cents, including fees
     */
    public function totalWithinLast24Hours(int $atMillis): int
    {
        $cutoff = $atMillis - self::MILLIS_PER_DAY;
        $total = 0;
        foreach ($this->entries as [$when, $value]) {
            if ($when >= $cutoff) {
                $total += $value;
            }
        }
        return $total + $this->fee;
    }
}
