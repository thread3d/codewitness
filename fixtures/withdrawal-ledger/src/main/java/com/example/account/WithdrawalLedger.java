package com.example.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A withdrawal ledger with a daily limit.
 *
 * <p>This is a CodeWitness fixture. It contains deliberately introduced defects; it is not an example
 * of good code.
 *
 * <p>Defects present:
 * <ol>
 *   <li>the limit is applied per calendar day rather than over a rolling 24-hour window;</li>
 *   <li>{@link #applyFee(BigDecimal)} discards the result of {@code BigDecimal.add}, which is a
 *       silent no-op because BigDecimal is immutable;</li>
 *   <li>negative withdrawals are accepted and reduce the total;</li>
 *   <li>{@link #withdrawConcurrently(BigDecimal)} is a check-then-act race.</li>
 * </ol>
 */
public class WithdrawalLedger {

    /** The limit: 1,000. */
    public static final BigDecimal LIMIT = new BigDecimal("1000");

    private final Map<LocalDate, List<Entry>> byDay = new LinkedHashMap<>();
    private BigDecimal concurrentTotal = BigDecimal.ZERO;

    /** A single withdrawal. */
    public record Entry(Instant at, BigDecimal amount) {
    }

    /**
     * Attempts a withdrawal.
     *
     * @param at     instant of the withdrawal
     * @param amount amount withdrawn
     * @return {@code true} when the withdrawal was accepted
     */
    public boolean withdraw(Instant at, BigDecimal amount) {
        LocalDate day = LocalDate.ofInstant(at, ZoneOffset.UTC);
        BigDecimal todayTotal = totalOn(day);
        // DEFECT 1: the limit is compared against the total for the *calendar day*, not the rolling
        // 24 hour window the requirement asks for.
        if (todayTotal.add(amount).compareTo(LIMIT) > 0) {
            return false;
        }
        // DEFECT 3: a non-positive amount is accepted here and reduces the recorded total.
        byDay.computeIfAbsent(day, key -> new ArrayList<>()).add(new Entry(at, amount));
        return true;
    }

    /**
     * Applies a fee to the recorded total for a day.
     *
     * @param fee fee to add
     */
    public void applyFee(BigDecimal fee) {
        // DEFECT 2: BigDecimal is immutable, so this line computes a value and throws it away. The
        // compiler accepts it, and the code looks correct at a glance.
        fee.add(new BigDecimal("0.50"));
    }

    /**
     * Attempts a withdrawal against a single running total, as a service with shared state would.
     *
     * <p>DEFECT 4: the check and the update are not atomic, so concurrent callers can both pass the
     * check and overshoot the limit.
     *
     * @param amount amount withdrawn
     * @return {@code true} when the withdrawal was accepted
     */
    public boolean withdrawConcurrently(BigDecimal amount) {
        if (concurrentTotal.add(amount).compareTo(LIMIT) > 0) {
            return false;
        }
        concurrentTotal = concurrentTotal.add(amount);
        return true;
    }

    /** @return the total recorded for a calendar day. */
    public BigDecimal totalOn(LocalDate day) {
        BigDecimal total = BigDecimal.ZERO;
        for (Entry entry : byDay.getOrDefault(day, List.of())) {
            total = total.add(entry.amount());
        }
        return total;
    }

    /**
     * Observation used by the requirement oracle: everything withdrawn in the 24 hours up to
     * {@code at}, inclusive.
     *
     * <p>This is deliberately a correct implementation of the rolling window, so that it can be used
     * to observe whether {@link #withdraw(Instant, BigDecimal)} respects the requirement.
     *
     * @param at end of the window
     * @return the total withdrawn in the preceding 24 hours
     */
    public BigDecimal totalWithinLast24Hours(Instant at) {
        Instant from = at.minusSeconds(24 * 60 * 60);
        BigDecimal total = BigDecimal.ZERO;
        for (List<Entry> entries : byDay.values()) {
            for (Entry entry : entries) {
                if (!entry.at().isBefore(from) && !entry.at().isAfter(at)) {
                    total = total.add(entry.amount());
                }
            }
        }
        return total;
    }

    /** @return the total tracked by the concurrent entry point. */
    public BigDecimal concurrentTotal() {
        return concurrentTotal;
    }
}
