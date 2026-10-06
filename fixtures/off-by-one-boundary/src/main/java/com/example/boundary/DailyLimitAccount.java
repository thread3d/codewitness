package com.example.boundary;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A daily spending account with a per-transaction daily limit.
 *
 * <p>This is a CodeWitness fixture. It contains a deliberately introduced defect; it is not an
 * example of good code.
 *
 * <p>Deliberate defect:
 * <ol>
 *   <li>{@link #withdraw(BigDecimal)} uses an off-by-one boundary comparison. It rejects a
 *       withdrawal of <em>exactly</em> the limit because the guard asks whether
 *       {@code total + amount >= LIMIT} while the requirement needs {@code total + amount > LIMIT}.
 *       A withdrawal of exactly 1,000.00 must be accepted; only an amount that pushes the total
 *       strictly above the limit may be rejected.</li>
 * </ol>
 */
public class DailyLimitAccount {

    /** The maximum that may be withdrawn in a single day. */
    public static final BigDecimal LIMIT = new BigDecimal("1000.00");

    private final List<BigDecimal> accepted = new ArrayList<>();

    /**
     * Attempts a withdrawal of {@code amount}.
     *
     * @param amount amount to withdraw; must not be {@code null}
     * @return {@code true} when the withdrawal was accepted
     */
    public boolean withdraw(BigDecimal amount) {
        // DEFECT: off-by-one boundary. ">= 0" rejects a withdrawal that lands exactly on the limit;
        // the requirement needs "> 0" so that the limit itself is accepted. The compiler is happy,
        // and only an input at the boundary exposes the difference.
        if (total().add(amount).compareTo(LIMIT) >= 0) {
            return false;
        }
        accepted.add(amount);
        return true;
    }

    /** @return the total accepted so far. */
    public BigDecimal total() {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal amount : accepted) {
            total = total.add(amount);
        }
        return total;
    }
}
