package dev.codewitness.core.evidence;

/** What a piece of evidence does to the claim it is attached to. */
public enum EvidenceStatus {
    /** The observation is consistent with the claim, but does not by itself prove it. */
    SUPPORTS,
    /** The observation demonstrates that the claim is violated. */
    REFUTES,
    /** The observation neither supports nor refutes the claim. */
    INCONCLUSIVE
}
