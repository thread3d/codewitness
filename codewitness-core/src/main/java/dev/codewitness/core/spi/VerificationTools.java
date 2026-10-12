package dev.codewitness.core.spi;

import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.PackageCoordinate;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.VerificationObligation;

import java.util.List;
import java.util.Set;

/**
 * The tool level extension points of CodeWitness.
 *
 * <p>A {@link dev.codewitness.core.spi.LanguageAdapter} answers "how do I build and run this
 * language?"; these interfaces answer "which specific tools do the verification phases use?". They
 * are separate so that a new static analyzer, mutation tester or test generator can be added
 * without touching either the engine or the language adapter.
 *
 * <p>Every method returns a value object describing what happened. Implementations must never throw
 * for a tool failure and must never report success they did not observe: an unavailable tool
 * produces evidence with an explanatory note, which the engine turns into {@code UNVERIFIED} or
 * {@code BLOCKED} rather than a pass.
 */
public final class VerificationTools {

    private VerificationTools() {
    }

    /** Produces a build or compilation result. */
    public interface Compiler {
        String id();

        boolean supports(ProjectInfo project);

        BuildResult build(VerificationContext context);
    }

    /** Executes the project's existing tests. */
    public interface TestRunner {
        String id();

        boolean supports(ProjectInfo project);

        TestResult runTests(VerificationContext context);

        /**
         * The command {@link #runTests} executes, so the test evidence can name the invocation that
         * produced its result.
         *
         * <p>The engine cannot derive this for itself: only the language adapter knows how its tests
         * are invoked. An empty list means "no command is available", and the evidence record says so
         * rather than attributing the result to some other command that happened to run earlier.
         *
         * <p>Named distinctly from {@link DependencyAnalyzer#dependencyCommand} because one class may
         * implement both interfaces, and a single shared name would silently answer for the wrong one.
         *
         * @param context the run context
         * @return argv style command, or an empty list when the adapter runs no command
         */
        default List<String> testCommand(VerificationContext context) {
            return List.of();
        }

        /**
         * Builds the command that re-runs one already-executed test.
         *
         * <p>The core needs a replay command to put in a counterexample, but only the language
         * adapter knows how its tests are invoked. Returning an empty list means "no replay command
         * is available", and the report says so rather than printing a command that would not work.
         *
         * @param context  the run context
         * @param testCase the failing test case to replay
         * @return argv style command, or an empty list when replay is not supported
         */
        default List<String> replayCommand(VerificationContext context, TestCaseResult testCase) {
            return List.of();
        }
    }

    /** Produces normalised static analysis findings. */
    public interface StaticAnalyzer {
        String id();

        boolean supports(ProjectInfo project);

        List<Finding> analyze(VerificationContext context);
    }

    /** Measures test coverage. */
    public interface CoverageProvider {
        String id();

        boolean supports(ProjectInfo project);

        CoverageReport measure(VerificationContext context);
    }

    /** Runs mutation testing. */
    public interface MutationTester {
        String id();

        boolean supports(ProjectInfo project);

        MutationReport analyse(VerificationContext context);
    }

    /** Runs security oriented analysis. */
    public interface SecurityAnalyzer {
        String id();

        boolean supports(ProjectInfo project);

        List<Finding> analyze(VerificationContext context);
    }

    /** Analyses dependencies and composition. */
    public interface DependencyAnalyzer {
        String id();

        boolean supports(ProjectInfo project);

        DependencyReport analyseDependencies(VerificationContext context);

        /**
         * The command {@link #analyseDependencies} executes, so the dependency evidence can name the
         * invocation that produced it.
         *
         * <p>An empty list means the analysis reads the project's manifests directly and runs no
         * command, which the evidence record reflects instead of leaving a blank field.
         *
         * @param context the run context
         * @return argv style command, or an empty list when no command is run
         */
        default List<String> dependencyCommand(VerificationContext context) {
            return List.of();
        }

        /**
         * The advisory ecosystems this analyzer's coordinates belong to, spelled exactly as the advisory
         * service spells them (for example {@code npm}, {@code NuGet}, {@code RubyGems}).
         *
         * <p>This is the hook a new language answers so that an advisory can be explained in that
         * language's own toolchain without the core learning the language. When an advisory is attributed
         * to a requirement, the core asks the analyzer that claims the advisory's ecosystem to describe the
         * dependency path; the core itself names no ecosystem and no tool. The default is empty, which
         * correctly means "this analyzer reports no queryable coordinates" - a Maven-only fallback is
         * deliberately not assumed, because guessing would attribute one language's tool to another's
         * dependency.
         *
         * @return the ecosystem ids this analyzer produces coordinates in
         */
        default Set<String> ecosystems() {
            return Set.of();
        }

        /**
         * How a human shows why a resolved dependency is on the project's graph, in this ecosystem's own
         * tool. The text is appended to a counterexample's reproduction recipe.
         *
         * <p>Only the analyzer that owns the ecosystem can answer this: {@code npm ls} is the npm answer,
         * {@code cargo tree -i} the Cargo one, and a core that hard-coded either would be a core that
         * knows a language. An empty string is the honest answer for an ecosystem whose toolchain has no
         * such command, or for one whose executable recipe is supplied by
         * {@link #dependencyPathCommand(VerificationContext, PackageCoordinate)} instead.
         *
         * @param coordinate the vulnerable dependency, as the inventory reported it
         * @return prose naming the ecosystem's tool, or the empty string when there is none
         */
        default String dependencyPathHint(PackageCoordinate coordinate) {
            return "";
        }

        /**
         * An executable command that shows why a resolved dependency is on the project's graph, for a
         * counterexample's reproduction recipe.
         *
         * <p>Returning a command is a claim that it can be run in this project as it was verified. An
         * empty list is the honest answer when the analyzer cannot build one - for example because it does
         * not control which package manager the project uses - and the recipe then carries only
         * {@link #dependencyPathHint(PackageCoordinate)}.
         *
         * @param context    the run context
         * @param coordinate the vulnerable dependency, as the inventory reported it
         * @return argv style command, or an empty list when none can be built
         */
        default List<String> dependencyPathCommand(VerificationContext context, PackageCoordinate coordinate) {
            return List.of();
        }
    }

    /**
     * Generates additional tests for a requirement's obligations.
     *
     * <p>Generators only produce sources. They never execute them and never assign status.
     */
    public interface TestGenerator {
        String id();

        boolean supports(ProjectInfo project);

        List<GeneratedTest> generate(
                VerificationContext context,
                Requirement requirement,
                List<VerificationObligation> obligations);
    }

    /** Compiles and executes generated tests inside the project. */
    public interface GeneratedTestExecutor {
        String id();

        boolean supports(ProjectInfo project);

        GeneratedTestExecution execute(VerificationContext context, List<GeneratedTest> tests);

        /**
         * Builds the command that replays generated tests, optionally pinned to one seed and case.
         *
         * <p>Replaying a generated counterexample is execution, so it belongs to the executor rather
         * than to the strategy that interpreted the result. An empty list means the language has no
         * replay command; the engine then records the counterexample without one instead of printing
         * a command that cannot work.
         *
         * @param context   the run context
         * @param tests     the generated tests to replay
         * @param seed      seed to pin, or {@code null}
         * @param caseIndex case index to pin, or {@code null}
         * @return argv style command, or an empty list when replay is not supported
         */
        default List<String> reproductionCommand(
                VerificationContext context, List<GeneratedTest> tests, Long seed, Integer caseIndex) {
            return List.of();
        }
    }
}
