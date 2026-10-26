package dev.codewitness.kotlin.mutation;

import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.kotlin.KotlinBuildSystem;
import dev.codewitness.kotlin.KotlinBuildTools;
import dev.codewitness.kotlin.KotlinBuildOutputParser;
import dev.codewitness.kotlin.KotlinProjectDetector;
import dev.codewitness.support.mutation.CommandMutationProfile;
import dev.codewitness.support.mutation.CommandMutationTester;
import dev.codewitness.support.mutation.LanguageLexis;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mutation testing for Kotlin using a bounded source mutator.
 *
 * <p>PIT is the JVM's mutation framework and it is what the Java adapter drives, but it mutates bytecode and
 * maps the result back to source lines through debug information that Kotlin generates differently from
 * javac; a Kotlin project would get mutants it cannot attribute, or none at all. The adapter therefore
 * mutates the Kotlin source text itself through the shared loop, re-runs the project's own test command for
 * each mutant, and reports the mutants the suite could not detect as survivors.
 *
 * <p>Three properties keep that honest. The run is bounded by a fixed operator set, a mutant cap and a
 * wall-clock budget. Every file is restored in a {@code finally} block, so a crash cannot leave a mutated
 * tree behind. And a mutant that fails to compile is non-viable and is excluded from the score rather than
 * counted as killed, because a compilation error is evidence about the mutant and not about the strength of
 * the tests.
 *
 * <p>The per-mutant command is <em>not</em> the test phase's command. The test phase asks Maven to keep going
 * after a failure ({@code -Dmaven.test.failure.ignore=true}) so that every module produces a report; a
 * mutation run needs the opposite, because the exit status is the only thing that distinguishes a killed
 * mutant from a surviving one.
 */
public final class KotlinMutationTester implements VerificationTools.MutationTester {

    private final CommandMutationTester delegate;

    public KotlinMutationTester(KotlinBuildTools tools) {
        this.delegate = new CommandMutationTester(new KotlinMutationProfile(tools));
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return delegate.supports(project);
    }

    @Override
    public MutationReport analyse(VerificationContext context) {
        return delegate.analyse(context);
    }

    /** The Kotlin half of the shared mutation loop: its projects, its test command and its lexis. */
    private static final class KotlinMutationProfile implements CommandMutationProfile {

        private final KotlinBuildTools tools;

        private KotlinMutationProfile(KotlinBuildTools tools) {
            this.tools = tools;
        }

        @Override
        public String languageName() {
            return "Kotlin";
        }

        @Override
        public LanguageId languageId() {
            return LanguageId.KOTLIN;
        }

        @Override
        public String mutationTesterId() {
            return "kotlin-text-mutation";
        }

        @Override
        public String mutationReportToolId() {
            return "kotlin-text";
        }

        @Override
        public LanguageLexis lexis() {
            return KotlinLexis.INSTANCE;
        }

        @Override
        public String manifestProblem(VerificationContext context) {
            ProjectInfo project = context.projectInfo();
            KotlinBuildSystem buildSystem = KotlinBuildOutputParser.forProject(project);
            if (buildSystem == null) {
                return "no Kotlin build system was detected, so mutants cannot be compiled";
            }
            if (!project.hasTests()) {
                return "the project has no Kotlin test source directory, so no test could ever kill a mutant";
            }
            if (tools.resolve(project, buildSystem.buildRoot(project)) == null) {
                return "no " + buildSystem.id() + " executable was found, so mutants cannot be compiled";
            }
            return null;
        }

        @Override
        public List<Path> sourceFiles(Path sourceRoot) {
            return KotlinProjectDetector.sourceFiles(sourceRoot);
        }

        @Override
        public boolean isTestPath(String relative) {
            String normalized = relative.replace('\\', '/').toLowerCase(Locale.ROOT);
            String name = normalized.contains("/")
                    ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
            if (name.endsWith("test.kt") || name.endsWith("tests.kt")) {
                return true;
            }
            for (String element : normalized.split("/")) {
                if (element.equals("test") || element.equals("tests") || element.equals("testdata")) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public List<String> testCommand(ProjectInfo project, VerificationContext context) {
            KotlinBuildSystem buildSystem = KotlinBuildOutputParser.forProject(project);
            if (buildSystem == null) {
                return List.of();
            }
            List<String> executable = tools.resolve(project, buildSystem.buildRoot(project));
            if (executable == null) {
                return List.of();
            }
            return KotlinBuildTools.command(executable, new ArrayList<>(buildSystem.mutationGoals()));
        }

        @Override
        public Map<String, String> environment(VerificationContext context) {
            return Map.of();
        }

        @Override
        public String nonViableReason(String output) {
            if (output == null) {
                return null;
            }
            // A mutant that does not compile is not evidence about the tests, so the compiler's own words
            // decide: kotlin-maven-plugin fails the goal with "Compilation failure" and Gradle reports the
            // failing compile task, in both cases after printing the diagnostics in the Kotlin format.
            if (output.contains("Compilation failure") || output.contains("compilation error")
                    || output.contains("Execution failed for task ':compileKotlin'")
                    || output.contains("Execution failed for task ':compileTestKotlin'")
                    || output.contains("e: file://") || output.contains("error: unresolved reference")
                    || output.contains("error: type mismatch")) {
                return "the mutant did not compile: " + lastLines(output, 3);
            }
            if (output.contains("No tests found for given includes") || output.contains("No tests found")) {
                // The suite ran but discovered nothing to run, which says the project's tests were not
                // discovered rather than that the mutant was undetected.
                return "no test was discovered to run against the mutant: " + lastLines(output, 3);
            }
            return null;
        }

        @Override
        public String operatorSetDescription() {
            return "The operator set is deliberately small (arithmetic, comparison boundaries, equality, "
                    + "boolean operators and the boolean literals), and only operators surrounded by "
                    + "whitespace are considered.";
        }

        private static String lastLines(String text, int count) {

            // One implementation, in support. Five copies of this existed and three of them had

            // silently diverged by stripping each line, so the same tool output was quoted with

            // different whitespace in a Kotlin report than in a Java one.

            return dev.codewitness.support.Text.lastLines(text, count);

        }
    }
}
