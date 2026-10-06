package dev.codewitness.core.requirements;

/** Verification status of a requirement. */
public enum RequirementStatus {
    /** Sufficient independent evidence supports the requirement. */
    VERIFIED,
    /** Some aspects have evidence, but significant cases remain unverified. */
    PARTIALLY_VERIFIED,
    /** A counterexample demonstrates that the requirement is violated. */
    FAILED,
    /** CodeWitness cannot establish sufficient evidence either way. */
    UNVERIFIED,
    /** Verification could not proceed because of infrastructure or project problems. */
    BLOCKED;

    public boolean isFailure() {
        return this == FAILED;
    }
}
