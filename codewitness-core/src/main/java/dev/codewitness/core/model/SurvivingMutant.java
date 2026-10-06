package dev.codewitness.core.model;

import java.util.Objects;

/**
 * A mutant that the existing test suite did not detect. Surviving mutants are first-class evidence:
 * they are the most direct signal that the tests do not constrain the behaviour they appear to
 * constrain.
 *
 * @param id          framework specific mutant identifier
 * @param status      mutant status
 * @param mutator     mutation operator, for example {@code ConditionalsBoundaryMutator}
 * @param className   mutated class
 * @param method      mutated method
 * @param line        1-based line number
 * @param description human readable mutation description
 * @param killingTest test that killed the mutant, or {@code null} when it survived
 */
public record SurvivingMutant(
        String id,
        MutantStatus status,
        String mutator,
        String className,
        String method,
        int line,
        String description,
        String killingTest) {

    public SurvivingMutant {
        Objects.requireNonNull(status, "status");
    }

    public String location() {
        return className + (method == null ? "" : "#" + method) + ":" + line;
    }
}
