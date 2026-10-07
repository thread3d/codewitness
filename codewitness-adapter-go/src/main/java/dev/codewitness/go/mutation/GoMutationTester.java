package dev.codewitness.go.mutation;

import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.go.GoProjectDetector;
import dev.codewitness.go.GoTool;
import dev.codewitness.support.mutation.CommandMutationProfile;
import dev.codewitness.support.mutation.CommandMutationTester;
import dev.codewitness.support.mutation.LanguageLexis;
import dev.codewitness.support.mutation.TextMutator;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mutation testing for Go using a bounded source mutator.
 *
 * <p>Go has no universally installed mutation tool, and a project should not lose mutation evidence
 * just because one is absent. The adapter therefore mutates the source text itself through
 * {@link TextMutator}, re-runs the project's own test suite with {@code go test} for each mutant, and
 * reports the mutants the suite could not detect as survivors.
 *
 * <p>The run is bounded three ways: a fixed operator set, a mutant cap and a wall-clock budget. Every
 * file is restored in a {@code finally} block, so a crash cannot leave a mutated source tree behind.
 * A mutant that fails to build is non-viable and is excluded from the score rather than counted as
 * killed: a compilation error is evidence about the mutant, not about the strength of the tests. The
 * same convention is used for the Java adapter's PIT results, so the mutation score means the same
 * thing in every language. Surviving mutants never change a verdict: they are prominent evidence and
 * a run limitation.
 *
 * <p>The loop, its bounds and its restore guarantee live once in {@link CommandMutationTester}; this
 * class supplies only the Go half through a nested profile - the manifest to look for, the sources, the
 * go invocation and the lexis.
 */
public final class GoMutationTester implements VerificationTools.MutationTester {

    private final CommandMutationTester delegate = new CommandMutationTester(new GoMutationProfile());

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

    /** The Go half of the shared mutation loop: its manifest, its sources, its go tool and its lexis. */
    private static final class GoMutationProfile implements CommandMutationProfile {

        @Override
        public String languageName() {
            return "Go";
        }

        @Override
        public LanguageId languageId() {
            return LanguageId.GO;
        }

        @Override
        public String mutationTesterId() {
            return "go-text-mutation";
        }

        @Override
        public String mutationReportToolId() {
            return "go-text";
        }

        @Override
        public LanguageLexis lexis() {
            return GoLexis.INSTANCE;
        }

        @Override
        public String manifestProblem(VerificationContext context) {
            return GoTool.hasManifest(context)
                    ? null : "no go.mod was found, so mutants cannot be compiled";
        }

        @Override
        public List<Path> sourceFiles(Path sourceRoot) {
            return GoProjectDetector.goFiles(sourceRoot);
        }

        @Override
        public boolean isTestPath(String relative) {
            // Go has one source tree: tests sit next to the code as *_test.go, and testdata is data the
            // toolchain ignores rather than a package to mutate.
            String normalized = relative.replace('\\', '/').toLowerCase(Locale.ROOT);
            String name = normalized.contains("/")
                    ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
            return name.endsWith("_test.go")
                    || normalized.equals("testdata")
                    || normalized.startsWith("testdata/")
                    || normalized.contains("/testdata/");
        }

        @Override
        public List<String> testCommand(ProjectInfo project, VerificationContext context) {
            return List.of(GoTool.go(project, context), "test", "-count=1", "./...");
        }

        @Override
        public Map<String, String> environment(VerificationContext context) {
            return GoTool.environment(context);
        }

        @Override
        public String nonViableReason(String output) {
            if (output.contains("[build failed]") || output.contains("[setup failed]")
                    || output.contains("no Go files")) {
                // The mutant did not compile, or no package ran: it is not evidence about test strength.
                return GoTool.lastLines(output, 3);
            }
            return null;
        }

        @Override
        public String operatorSetDescription() {
            return "The operator set is deliberately small (arithmetic, comparison boundaries, boolean "
                    + "operators and the predeclared boolean constants), and only operators surrounded "
                    + "by whitespace are considered.";
        }
    }
}
