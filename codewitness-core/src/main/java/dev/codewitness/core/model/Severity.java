package dev.codewitness.core.model;

/** Severity of an analysis finding, ordered from most to least severe. */
public enum Severity {
    CRITICAL(0),
    HIGH(1),
    MEDIUM(2),
    LOW(3),
    INFO(4);

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
