package dev.codewitness.support.mutation;

import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Everything {@link CommandMutationTester} needs to know about one language whose mutants are judged by
 * running the project's own build and test command.
 *
 * <p>Writing a mutant into the source, running what the project itself runs, reading the exit status and
 * restoring the file is the same job for Rust and Go; what differs is which command that is, what counts
 * as "the tests never ran", how the language's sources and its tests are told apart, and how the report
 * describes the operator set. An adapter supplies exactly those.
 *
 * <p>This is a profile of the adapter's tools and naming, not a strategy: an implementation answers
 * questions and holds no mutation logic of its own.
 */
public interface CommandMutationProfile {

    /**
     * The language's display name, spelled the way the adapter writes it in prose ("Rust", "Go").
     *
     * @return the display name, never empty
     */
    String languageName();

    /**
     * The language whose projects this tester can mutate.
     *
     * @return the language id the tester supports, e.g. {@link LanguageId#RUST}
     */
    LanguageId languageId();

    /**
     * The stable id the adapter publishes for this mutation tester.
     *
     * @return the SPI id, e.g. {@code "rust-text-mutation"}
     */
    String mutationTesterId();

    /**
     * The tool id written on a report that <em>was</em> measured.
     *
     * @return the report tool id, e.g. {@code "rust-text"}
     */
    String mutationReportToolId();

    /**
     * The language's lexical shape, which is what the shared mutator reads its source with.
     *
     * @return the lexis the shared mutator tokenizes this language's source with
     */
    LanguageLexis lexis();

    /**
     * Why mutants cannot be compiled in this project.
     *
     * @param context the run context
     * @return the reason to report as "not measured", or {@code null} when the manifest was found and
     *     the run may proceed
     */
    String manifestProblem(VerificationContext context);

    /**
     * Every source file of the language under one of the project's source roots.
     *
     * @param sourceRoot a source root reported by the project's detector
     * @return the language's source files under that root
     */
    List<Path> sourceFiles(Path sourceRoot);

    /**
     * How this language tells its own tests from the code they test, given a project-relative path.
     *
     * <p>Rust keeps integration tests in a {@code tests/} directory; Go keeps them next to the code as
     * {@code *_test.go}. A mutant is only evidence if it is applied to the code under test.
     *
     * @param relative the path relative to the project root
     * @return {@code true} when that path holds tests rather than mutable sources
     */
    boolean isTestPath(String relative);

    /**
     * The command that builds one mutant and runs the project's own tests over it.
     *
     * @param project the project being verified
     * @param context the run context
     * @return the command to invoke, never empty
     */
    List<String> testCommand(ProjectInfo project, VerificationContext context);

    /**
     * @param context the run context
     * @return the environment to hand to the test command
     */
    Map<String, String> environment(VerificationContext context);

    /**
     * Why a mutant's output is not a test result at all.
     *
     * <p>The distinction matters: a mutant that never built is not evidence about the test suite, while
     * a mutant that built and whose tests then failed is a killed mutant. Each language states this
     * differently, so each adapter answers it in its own terms.
     *
     * @param output the combined output of the test command
     * @return the reason this output is not a test result, or {@code null} when the tests ran and the
     *     exit status decides the mutant
     */
    String nonViableReason(String output);

    /**
     * The sentence the report uses to state the operator set, as this adapter words it.
     *
     * @return the operator-set sentence, ending in a full stop
     */
    String operatorSetDescription();
}
