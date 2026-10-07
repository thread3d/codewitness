package dev.codewitness.csharp.mutation;

import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.csharp.CSharpBuildProvider;
import dev.codewitness.csharp.CSharpProjectDetector;
import dev.codewitness.csharp.CSharpTestProvider;
import dev.codewitness.csharp.DotnetTool;
import dev.codewitness.support.mutation.CommandMutationProfile;
import dev.codewitness.support.mutation.CommandMutationTester;
import dev.codewitness.support.mutation.LanguageLexis;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mutation testing for C# using a bounded source mutator.
 *
 * <p>.NET has a mutation testing tool, Stryker.NET, but it is a global tool that has to be installed and
 * restored before it runs, and a project should not lose mutation evidence because of that. The adapter
 * therefore mutates the source text itself through the shared mutator, re-runs the project's own test
 * command for each mutant, and reports the mutants the suite could not detect as survivors.
 *
 * <p>The run is bounded three ways: a fixed operator set, a mutant cap and a wall-clock budget. Every file
 * is restored in a {@code finally} block, so a crash cannot leave a mutated source tree behind. A mutant
 * that fails to build is non-viable and is excluded from the score rather than counted as killed: a
 * compilation error is evidence about the mutant, not about the strength of the tests. Surviving mutants
 * never change a verdict: they are prominent evidence and a run limitation.
 *
 * <p>Restore is switched off for the per-mutant runs. The manifest has already been resolved by the build
 * phase, a mutant changes a source file rather than a package graph, and a restore per mutant would
 * multiply the cost of the phase by an order of magnitude for no new information.
 */
public final class CSharpMutationTester implements VerificationTools.MutationTester {

    private final CommandMutationTester delegate = new CommandMutationTester(new CSharpMutationProfile());

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

    /** The C# half of the shared mutation loop: its projects, its test command and its lexis. */
    private static final class CSharpMutationProfile implements CommandMutationProfile {

        @Override
        public String languageName() {
            return "C#";
        }

        @Override
        public LanguageId languageId() {
            return LanguageId.CSHARP;
        }

        @Override
        public String mutationTesterId() {
            return "csharp-text-mutation";
        }

        @Override
        public String mutationReportToolId() {
            return "csharp-text";
        }

        @Override
        public LanguageLexis lexis() {
            return CSharpLexis.INSTANCE;
        }

        @Override
        public String manifestProblem(VerificationContext context) {
            if (!DotnetTool.hasProject(context)) {
                return "no .csproj or .sln was found, so mutants cannot be compiled";
            }
            if (CSharpTestProvider.testTargets(context).isEmpty()) {
                return "the project has no test project, so no test could ever kill a mutant";
            }
            return null;
        }

        @Override
        public List<Path> sourceFiles(Path sourceRoot) {
            return CSharpProjectDetector.sourceFiles(sourceRoot);
        }

        @Override
        public boolean isTestPath(String relative) {
            // C# keeps tests in their own project, so a source root normally holds none. What this still
            // has to catch is the convention a project may use inside one project instead: a file named
            // FooTests.cs, or anything under a tests/ directory.
            String normalized = relative.replace('\\', '/').toLowerCase(Locale.ROOT);
            String name = normalized.contains("/")
                    ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
            if (name.endsWith("tests.cs") || name.endsWith("test.cs")) {
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
            List<Path> targets = CSharpTestProvider.testTargets(context);
            if (targets.isEmpty()) {
                return List.of();
            }
            List<String> command = new ArrayList<>();
            command.add(DotnetTool.dotnet(project, context));
            command.add("test");
            command.add(CSharpBuildProvider.relativeArgument(context.projectRoot(), targets.getFirst()));
            command.addAll(DotnetTool.commonArguments());
            command.add("--no-restore");
            return List.copyOf(command);
        }

        @Override
        public Map<String, String> environment(VerificationContext context) {
            return DotnetTool.environment(context);
        }

        @Override
        public String nonViableReason(String output) {
            if (output == null) {
                return null;
            }
            // A mutant that does not compile is not evidence about the tests; MSBuild says so with the
            // compiler's own error code or with its build-failed banner.
            if (output.contains("error CS") || output.contains("Build FAILED")) {
                return "the mutant did not compile: " + DotnetTool.lastLines(output, 3);
            }
            if (output.contains("No test is available") || output.contains("no test is available")) {
                // The test host ran but found nothing to run, which says the project's tests were not
                // discovered rather than that the mutant was undetected.
                return "no test was discovered to run against the mutant: " + DotnetTool.lastLines(output, 3);
            }
            return null;
        }

        @Override
        public String operatorSetDescription() {
            return "The operator set is deliberately small (arithmetic, comparison boundaries, equality, "
                    + "boolean operators and the boolean literals), and only operators surrounded by "
                    + "whitespace are considered.";
        }
    }
}
