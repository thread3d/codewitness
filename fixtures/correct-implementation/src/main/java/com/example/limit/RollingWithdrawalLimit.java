package com.example.limit;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A withdrawal limit over a rolling 24-hour window.
 *
 * <p>This is a CodeWitness fixture. Unlike the other fixtures in this repository it is deliberately
 * <em>well built</em>: it is the control case that proves CodeWitness can report a requirement as
 * {@code VERIFIED} rather than failing everything it is shown.
 *
 * <p>Defects present: none. The implementation keeps an explicit list of accepted withdrawals, so the
 * window is computed from real timestamps rather than from a calendar day, and every comparison is done
 * with {@link BigDecimal#compareTo} so scales do not change the arithmetic.
 *
 * <p>The window is closed at both ends: a withdrawal made exactly 24 hours before the one being
 * attempted still counts. That is the semantics stated by R-001.
 */
public final class RollingWithdrawalLimit {

    /** The limit: 1,000 per rolling 24 hours. */
    public static final BigDecimal LIMIT = new BigDecimal("1000");

    /** The length of the rolling window. */
    private static final Duration WINDOW = Duration.ofHours(24);

    private final List<Entry> accepted = new ArrayList<>();

    /** An accepted withdrawal. */
    public record Entry(Instant at, BigDecimal amount) {
    }

    /**
     * Attempts a withdrawal.
     *
     * @param at     instant of the withdrawal
     * @param amount amount to withdraw; must be strictly positive
     * @return {@code true} when the withdrawal was accepted
     */
    public boolean withdraw(Instant at, BigDecimal amount) {
        if (at == null || amount == null || amount.signum() <= 0) {
            return false;
        }
        if (totalWithinWindow(at).add(amount).compareTo(LIMIT) > 0) {
            return false;
        }
        accepted.add(new Entry(at, amount));
        return true;
    }

    /**
     * The total accepted in the 24 hours ending at {@code at}, inclusive at both ends.
     *
     * @param at end of the window
     * @return the total withdrawn in {@code [at - 24h, at]}
     */
    public BigDecimal totalWithinWindow(Instant at) {
        Instant from = at.minus(WINDOW);
        BigDecimal total = BigDecimal.ZERO;
        for (Entry entry : accepted) {
            if (!entry.at().isBefore(from) && !entry.at().isAfter(at)) {
                total = total.add(entry.amount());
            }
        }
        return total;
    }
}
