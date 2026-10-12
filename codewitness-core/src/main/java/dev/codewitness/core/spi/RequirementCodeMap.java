package dev.codewitness.core.spi;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The mapping from requirements to the source files and test classes that mention them.
 *
 * <p>This is the one project fact a verification strategy needs that is not a tool result: which code
 * belongs to the requirement it is trying to discharge. It is exposed as an interface for the same
 * reason {@link VerificationTools} is: so that a strategy can be written, registered and run without
 * the core naming the class that produces the map, and so that a strategy never has to be constructed
 * with a run-specific object.
 *
 * <p>The engine builds one map per run from the project's source and test trees and puts it on the
 * {@link VerificationContext}. A strategy reads it from the context at verification time, exactly as it
 * reads the phase results from {@link VerificationContext#state()}.
 *
 * <p>The mapping rule stays simple and inspectable: a file belongs to a requirement when the
 * requirement's identifier appears in it, or when the requirement declares {@code paths:} / {@code tests:}
 * explicitly. Anything not mapped stays unmapped, and a strategy must treat "not mapped" as "no evidence"
 * rather than as "not this requirement".
 */
public interface RequirementCodeMap {

    /** @return the test classes mapped to a requirement, or an empty list when none are. */
    List<String> testClassesFor(String requirementId);

    /** @return the requirement a test class is mapped to, or empty when it is mapped to none. */
    Optional<String> requirementForTestClass(String className);

    /**
     * @param relativeOrAbsolutePath a path as reported by a tool or written in a requirement
     * @return the requirement ids that path is mapped to, or an empty set when it is mapped to none
     */
    Set<String> requirementsForFile(String relativeOrAbsolutePath);

    /** @return the source files mapped to a requirement, or an empty set when none are. */
    Set<String> filesFor(String requirementId);

    /**
     * @return a map that attributes nothing to anything.
     *
     * <p>It is the default on a context that was not built by the engine, so a strategy written against
     * this interface can rely on {@link VerificationContext#codeMap()} being present and can never fail
     * on a missing map. With no mapping every strategy correctly produces no requirement-specific
     * evidence instead of inventing an attribution.
     */
    static RequirementCodeMap empty() {
        return new RequirementCodeMap() {
            @Override
            public List<String> testClassesFor(String requirementId) {
                return List.of();
            }

            @Override
            public Optional<String> requirementForTestClass(String className) {
                return Optional.empty();
            }

            @Override
            public Set<String> requirementsForFile(String relativeOrAbsolutePath) {
                return Set.of();
            }

            @Override
            public Set<String> filesFor(String requirementId) {
                return Set.of();
            }
        };
    }
}
