package dev.codewitness.core.spi;

import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.VerificationObligation;

import java.util.List;

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
    }
}
