package dev.codewitness.core.spi;

import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.Capability;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.TestResult;

import java.util.List;
import java.util.Set;

/**
 * The single extension point for supporting a programming language.
 *
 * <p>Everything the verification engine does with a project goes through this interface. The engine
 * holds no knowledge of Java, Maven or JUnit; it looks up an adapter for the detected language and
 * asks it to inspect, build, test and analyse.
 *
 * <p>Implementations are discovered through {@link java.util.ServiceLoader}, so adding a language
 * means adding a module and a provider file - not editing the engine.
 */
public interface LanguageAdapter {

    /** @return unique adapter identifier, for example {@code java}. */
    String id();

    /** @return {@code true} when this adapter can verify the given project. */
    boolean supports(ProjectRef project);

    /** Detects language, build system, modules, source layout and test frameworks. */
    ProjectInfo inspect(ProjectRef project, VerificationContext context);

    /** Compiles the project. A failed build must be reported as a failed build, never as a code defect. */
    BuildResult build(VerificationContext context);

    /** Runs the project's existing test suite. */
    TestResult runTests(VerificationContext context);

    /** Runs static analysis over the project. */
    List<Finding> analyze(VerificationContext context);

    /** @return the capabilities this adapter provides. The engine only schedules those phases. */
    default Set<Capability> capabilities() {
        return Set.of(Capability.INSPECT, Capability.BUILD, Capability.TEST, Capability.STATIC_ANALYSIS);
    }
}
