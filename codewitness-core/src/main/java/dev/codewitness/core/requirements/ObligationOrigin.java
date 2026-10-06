package dev.codewitness.core.requirements;

/** Where a verification obligation came from. */
public enum ObligationOrigin {
    /** Derived mechanically from the requirement text or its structured properties. */
    REQUIREMENT_TEXT,
    /** Derived from a property declared by the user in the requirements file. */
    USER_DECLARED,
    /** Suggested by a language model. Advisory until executed. */
    LLM_DERIVED,
    /** Derived by CodeWitness from analysis results, for example a surviving mutant. */
    ANALYZER_DERIVED
}
