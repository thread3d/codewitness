package dev.codewitness.support.coverage;

import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Everything {@link NodeCoverageProvider} needs to know about one Node language.
 *
 * <p>Running {@code node --test --experimental-test-coverage} and reading the LCOV it writes is the
 * same job for JavaScript and TypeScript. What differs is which projects the provider claims, where
 * that language's tests live, where the adapter wants the report written, and how Node is invoked -
 * which is a property of the adapter's runtime resolution, not of the measurement.
 *
 * <p>This is a profile of the adapter's tools and naming, not a strategy: an implementation answers
 * questions and holds no measurement logic of its own.
 */
public interface NodeCoverageProfile {

    /** @return the language whose projects this provider measures. */
    LanguageId languageId();

    /**
     * @param context the run context
     * @return the test files the runner should load, empty when none could be found
     */
    List<Path> testFiles(VerificationContext context);

    /**
     * @param context the run context
     * @return the path the LCOV report should be written to, prepared for writing
     */
    Path prepareReport(VerificationContext context);

    /**
     * @param project the project being verified
     * @param context the run context
     * @return the Node executable to invoke, never empty
     */
    String nodeExecutable(ProjectInfo project, VerificationContext context);

    /**
     * @param context the run context
     * @return the environment to hand to the Node child process
     */
    Map<String, String> nodeEnvironment(VerificationContext context);
}
