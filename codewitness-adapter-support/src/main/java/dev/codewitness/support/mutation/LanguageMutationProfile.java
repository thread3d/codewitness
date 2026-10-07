package dev.codewitness.support.mutation;

import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Everything {@link TextMutationTester} needs to know about one C-like language.
 *
 * <p>The mutation loop, its bounds and its restore guarantee are language independent, but a handful
 * of details are not: which language the tester declares, how the project's sources and tests are
 * found, how Node is invoked, and how a mutated file is checked for parseability. An adapter supplies
 * exactly those by implementing this interface; the shared tester supplies everything else.
 *
 * <p>This is a profile of the adapter's tools and naming, not a strategy interface: an implementation
 * answers questions about the language and holds no mutation logic of its own.
 */
public interface LanguageMutationProfile {

    /**
     * The language's display name, spelled the way the adapter writes it in prose ("JavaScript",
     * "TypeScript").
     *
     * <p>The shared tester uses it in the messages it emits and returns, so those read as the
     * adapter's own rather than as generic ones.
     *
     * @return the display name, never empty
     */
    String languageName();

    /**
     * The language whose projects this profile can mutate.
     *
     * @return the language id the tester supports, e.g. {@link LanguageId#JAVASCRIPT}
     */
    LanguageId languageId();

    /**
     * The stable id the adapter publishes for this mutation tester.
     *
     * <p>Used as the tool id on a report that was <em>not</em> measured, matching the convention every
     * other adapter's mutation tester follows.
     *
     * @return the SPI id, e.g. {@code "javascript-text-mutation"}
     */
    String mutationTesterId();

    /**
     * The tool id written on a report that <em>was</em> measured, so a reader can see which testers
     * produced the numbers.
     *
     * @return the report tool id, e.g. {@code "javascript-text"}
     */
    String mutationReportToolId();

    /**
     * The language's lexical shape, which is what the shared mutator reads its source with.
     *
     * <p>It must describe the same language {@link #languageId()} claims: the mutator decides which
     * operators to flip and which literals to leave alone from this, and a mismatch would mutate the
     * wrong tokens rather than fail loudly.
     *
     * @return the lexis the shared mutator tokenizes this language's source with
     */
    LanguageLexis lexis();

    /**
     * Lower-case suffix of the files that sit next to the language's own sources but are not mutated,
     * because the adapter's parse check does not handle their syntax.
     *
     * @return the excluded suffix including the dot, e.g. {@code ".jsx"}
     */
    String excludedSourceSuffix();

    /**
     * The test files the project's own runner should execute for each mutant.
     *
     * @param context the run context
     * @return the runnable test files, empty when none could be found
     */
    List<Path> testFiles(VerificationContext context);

    /**
     * Every source file of the language under one of the project's source roots.
     *
     * @param sourceRoot a source root reported by the project's detector
     * @return the language's source files under that root
     */
    List<Path> sourceFiles(Path sourceRoot);

    /**
     * The Node executable the adapter resolved for this run.
     *
     * @param project the project being verified
     * @param context the run context
     * @return the executable to invoke, never empty
     */
    String nodeExecutable(ProjectInfo project, VerificationContext context);

    /**
     * The environment to hand to every Node child process the tester starts.
     *
     * @param context the run context
     * @return the environment to pass through to Node
     */
    Map<String, String> nodeEnvironment(VerificationContext context);

    /**
     * Writes (or reuses) the adapter's own parse-check script and returns it.
     *
     * <p>This is the same helper the adapter's build uses, so a mutant the build would reject and a
     * mutant the tester rejects cannot disagree.
     *
     * @param context the run context
     * @return the script that accepts one file path and fails when that file does not parse
     */
    Path buildCheckScript(VerificationContext context);
}
