package com.example.fees;

import java.math.BigDecimal;

/**
 * The fee schedule for withdrawals.
 *
 * <p>This is a CodeWitness fixture. The implementation is correct with respect to the declared
 * requirements; the defect deliberately introduced by this fixture lives in the project's own test
 * suite, not here.
 *
 * <p>Defect present:
 * <ol>
 *   <li>the test suite executes {@link #describe(BigDecimal)} with a zero fee but never asserts the
 *       result, so a mutant of the {@code fee.signum() > 0} boundary survives. CodeWitness must report
 *       that surviving mutant as a test-strength limitation for R-002 (and for the file-level coverage
 *       of R-001), not as a requirement failure.</li>
 * </ol>
 *
 * <p>The behaviour required by R-001 and R-002 is implemented correctly below.
 */
public final class FeeSchedule {

    /** Amounts strictly above this are charged a fee. */
    public static final BigDecimal FREE_LIMIT = new BigDecimal("1000");

    /** The flat fee charged above the free limit. */
    public static final BigDecimal FEE = new BigDecimal("2.00");

    /**
     * Computes the fee for a withdrawal amount (R-001).
     *
     * @param amount withdrawal amount
     * @return 2.00 when the amount is strictly greater than the free limit, zero otherwise
     */
    public BigDecimal feeFor(BigDecimal amount) {
        if (amount.compareTo(FREE_LIMIT) > 0) {
            return FEE;
        }
        return BigDecimal.ZERO;
    }

    /**
     * Renders a fee as text (R-002).
     *
     * @param fee fee to render
     * @return {@code "free"} for a zero fee, the plain decimal text otherwise
     */
    public String describe(BigDecimal fee) {
        if (fee.signum() > 0) {
            return fee.toPlainString();
        }
        return "free";
    }
}
