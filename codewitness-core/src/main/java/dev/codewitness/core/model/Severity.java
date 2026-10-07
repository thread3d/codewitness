package dev.codewitness.core.model;

/** Severity of an analysis finding, ordered from most to least severe. */
public enum Severity {
    CRITICAL(0),
    HIGH(1),
    MEDIUM(2),
    LOW(3),
    INFO(4),
    /**
     * The advisory did not state a severity. It sorts last, but that is an ordering convenience, not a
     * judgement: an advisory of unknown severity must never be read as a harmless one.
     */
    UNKNOWN(5);

    private final int rank;

    Severity(int rank) {
        this.rank = rank;
    }

    /** Lower rank means more severe. */
    public int rank() {
        return rank;
    }

    public boolean atLeastAsSevereAs(Severity other) {
        return this.rank <= other.rank;
    }
}
