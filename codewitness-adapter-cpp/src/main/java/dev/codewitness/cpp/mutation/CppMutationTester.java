package dev.codewitness.cpp.mutation;

import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.cpp.CppBuildTools;
import dev.codewitness.cpp.CppDiagnostics;
import dev.codewitness.cpp.CppProjectDetector;
import dev.codewitness.support.Platform;
import dev.codewitness.support.mutation.CommandMutationProfile;
import dev.codewitness.support.mutation.CommandMutationTester;
import dev.codewitness.support.mutation.LanguageLexis;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mutation testing for C and C++ using a bounded source mutator.
 *
 * <p>CMake has no mutation testing tool of its own, and a project should not lose mutation evidence for
 * want of one. The adapter therefore mutates the source text itself through the shared mutator, rebuilds
 * the project and runs the project's own CTest suite over every mutant, and reports the mutants the suite
 * could not detect as survivors.
 *
 * <p>The run is bounded three ways: a fixed operator set, a mutant cap and a wall-clock budget. Every file
 * is restored in a {@code finally} block, so a crash cannot leave a mutated source tree behind. A mutant
 * that fails to build is non-viable and is excluded from the score rather than counted as killed: a
 * compilation error is evidence about the mutant, not about the strength of the tests. Surviving mutants
 * never change a verdict: they are prominent evidence and a run limitation.
 *
 * <p>This is the one profile here whose command is composed with the platform's shell, and the reason is
 * CMake's own shape. CMake offers no single invocation that both rebuilds an existing build tree and runs
 * its tests: CTest's {@code --build-and-test} mode reconfigures the project from scratch for every
 * mutant, which multiplies the cost of this phase by an order of magnitude. The two commands the project
 * itself runs - {@code cmake --build <buildDir> --parallel} and
 * {@code ctest --test-dir <buildDir> --output-on-failure} - are therefore joined with {@code &&} and
 * handed to {@code /bin/sh} or {@code cmd.exe}. The limit of that choice is that both commands share one
 * shell and one exit status: a mutant is killed only by the tests failing, and those tests ran only
 * because the build succeeded, which is exactly how the shared loop reads that status.
 *
 * <p>The loop, its bounds and its restore guarantee live once in {@link CommandMutationTester}; this class
 * supplies only the C and C++ half through a nested profile - the manifest to look for, the sources, the
 * build-and-test invocation and the lexis.
 */
public final class CppMutationTester implements VerificationTools.MutationTester {

    private final CommandMutationTester delegate = new CommandMutationTester(new CppMutationProfile());

    /**
     * @return the id the shared loop publishes for this tester, {@code "cpp-text-mutation"}
     */
    @Override
    public String id() {
        return delegate.id();
    }

    /**
     * @param project the project to test
     * @return {@code true} when the project is C or C++, the language this tester mutates
     */
    @Override
    public boolean supports(ProjectInfo project) {
        return delegate.supports(project);
    }

    /**
     * Runs the bounded mutation loop over the project's C and C++ sources.
     *
     * @param context the run context
     * @return what the run measured, or why nothing could be measured
     */
    @Override
    public MutationReport analyse(VerificationContext context) {
        return delegate.analyse(context);
    }

    /** The C and C++ half of the shared mutation loop: its project, its test command and its lexis. */
    private static final class CppMutationProfile implements CommandMutationProfile {

        @Override
        public String languageName() {
            return "C or C++";
        }

        @Override
        public LanguageId languageId() {
            return LanguageId.CPP;
        }

        @Override
        public String mutationTesterId() {
            return "cpp-text-mutation";
        }

        @Override
        public String mutationReportToolId() {
            return "cpp-text";
        }

        @Override
        public LanguageLexis lexis() {
            return CppLexis.INSTANCE;
        }

        @Override
        public String manifestProblem(VerificationContext context) {
            if (CppProjectDetector.buildRoot(context.projectRoot()) == null) {
                return "no CMakeLists.txt declaring a project was found, so mutants cannot be compiled";
            }
            if (CppProjectDetector.ctestTests(context.projectRoot()).isEmpty()) {
                return "the project registers no test with add_test, so no test could ever kill a mutant";
            }
            return null;
        }

        @Override
        public List<Path> sourceFiles(Path sourceRoot) {
            return CppProjectDetector.sourceFiles(sourceRoot);
        }

        @Override
        public boolean isTestPath(String relative) {
            // The loop reports a project-relative path but not always the root it is relative to, so this
            // works on the path text rather than resolving it. The conventions are the detector's: a test_*
            // or *-test / *_test / *test.* file name, or any path element naming a test directory.
            String normalized = relative.replace('\\', '/').toLowerCase(Locale.ROOT);
            String name = normalized.contains("/")
                    ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
            int dot = name.lastIndexOf('.');
            String stem = dot < 0 ? name : name.substring(0, dot);
            if (stem.startsWith("test_") || stem.startsWith("test-") || stem.endsWith("_test")
                    || stem.endsWith("-test") || stem.endsWith("test")) {
                return true;
            }
            for (String element : normalized.split("/")) {
                if (element.equals("test") || element.equals("tests") || element.equals("testing")
                        || element.equals("unittest") || element.equals("unittests")) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public List<String> testCommand(ProjectInfo project, VerificationContext context) {
            if (CppProjectDetector.buildRoot(context.projectRoot()) == null) {
                return List.of();
            }
            Path buildDir = new CppBuildTools(context.runner()).buildDirectory(context);
            // CMake provides no single invocation that both rebuilds an existing build tree and runs its
            // tests, so the two commands the project itself runs are composed with the platform's shell.
            // The build directory is quoted because the run's artefact path can contain spaces, and the
            // shared loop reads the one exit status the two commands produce together.
            String quote = Platform.isWindows() ? "\"" : "'";
            String directory = quote + buildDir.toString() + quote;
            String script = String.join(" ", List.of(
                    CppBuildTools.cmake(project, context), "--build", directory, "--parallel",
                    "&&",
                    CppBuildTools.ctest(project), "--test-dir", directory, "--output-on-failure"));
            return Platform.isWindows()
                    ? List.of("cmd.exe", "/c", script)
                    : List.of("/bin/sh", "-c", script);
        }

        @Override
        public Map<String, String> environment(VerificationContext context) {
            return CppBuildTools.environment(context);
        }

        @Override
        public String nonViableReason(String output) {
            if (output == null) {
                return null;
            }
            if (output.contains("No tests were found") || output.contains("No test configuration file found")) {
                // CTest ran but found nothing to run, which says the project's tests were not discovered
                // rather than that the mutant was undetected.
                return "no test was discovered to run against the mutant: " + CppBuildTools.lastLines(output, 3);
            }
            if (output.contains("Total Test time")) {
                // CTest prints its own summary only after it has run the suite, so the build succeeded and
                // the tests, not the compiler, decided this mutant's fate. The exit status is the verdict.
                //
                // This is deliberately not a search for the words a build tool uses to report a failure:
                // CTest's own "The following tests FAILED:" contains ninja's "FAILED:" verbatim, and a rule
                // that matched it would call every killed mutant a mutant that did not compile.
                return null;
            }
            // The two commands are joined with &&, so reaching here means the build stopped before CTest
            // started: the mutant did not compile. The compiler's own first error is the useful part of that.
            List<CompilerDiagnostic> errors = CppDiagnostics.parse(output, null).stream()
                    .filter(diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR)
                    .toList();
            String detail = errors.isEmpty() ? CppBuildTools.lastLines(output, 3)
                    : errors.getFirst().location() + ": " + errors.getFirst().message();
            return "the mutant did not compile: " + detail;
        }

        @Override
        public String operatorSetDescription() {
            return "The operator set is deliberately small (arithmetic, comparison boundaries, equality, "
                    + "boolean operators and the boolean literals), and only operators surrounded by "
                    + "whitespace are considered, so that a unary minus, a dereference or a scope "
                    + "resolution is never mistaken for a binary operator site.";
        }
    }
}
