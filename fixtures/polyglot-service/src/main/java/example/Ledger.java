package example;

/** The Java half of the ledger. R-001 lives here. */
public final class Ledger {

    /** R-001: the running total of a list of amounts. */
    public static long total(long... amounts) {
        long sum = 0;
        for (long amount : amounts) {
            sum += amount;
        }
        return sum;
    }

    private Ledger() {
    }
}
