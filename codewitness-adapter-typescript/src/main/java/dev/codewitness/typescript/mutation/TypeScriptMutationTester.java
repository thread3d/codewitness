package dev.codewitness.typescript.mutation;

import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.support.mutation.LanguageLexis;
import dev.codewitness.support.mutation.LanguageMutationProfile;
import dev.codewitness.support.mutation.ScriptLexis;
import dev.codewitness.support.mutation.TextMutationTester;
import dev.codewitness.typescript.NodeRuntime;
import dev.codewitness.typescript.TypeScriptProjectDetector;
import dev.codewitness.typescript.TypeScriptScripts;
import dev.codewitness.typescript.TypeScriptTestProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Mutation testing for TypeScript using a bounded source mutator.
 *
 * <p>A mutation framework is an optional dependency, and a project should not lose mutation evidence
 * just because it is not installed. The adapter therefore mutates the source text itself through
 * {@link dev.codewitness.support.mutation.TextMutator}, re-runs the project's own test suite for each
 * mutant with {@code node --test}, and reports the mutants the suite could not detect as survivors.
 *
 * <p>The loop, its bounds and its restore guarantee live once in
 * {@link TextMutationTester}; this class supplies only the TypeScript half through a nested profile.
 *
 * <p>A mutant that the runtime's type stripper cannot parse is non-viable and is excluded from the score
 * rather than counted as killed; the parse is checked with the same helper the build uses, so
 * "does not compile" and "was detected" are never confused. The run is bounded three ways: a fixed
 * operator set, a mutant cap and a wall-clock budget, and every file is restored in a {@code finally}
 * block so a crash cannot leave a mutated source tree behind.
 *
 * <p>Surviving mutants never change a verdict: they are prominent evidence and a run limitation,
 * exactly like the Java adapter's PIT results.
 */
public final class TypeScriptMutationTester implements VerificationTools.MutationTester {

    private final TextMutationTester delegate = new TextMutationTester(new TypeScriptMutationProfile());

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

    /** The TypeScript half of the shared mutation tester: its sources, its tests and its Node. */
    private static final class TypeScriptMutationProfile implements LanguageMutationProfile {

        @Override
        public String languageName() {
            return "TypeScript";
        }

        @Override
        public LanguageId languageId() {
            return LanguageId.TYPESCRIPT;
        }

        @Override
        public String mutationTesterId() {
            return "typescript-text-mutation";
        }

        @Override
        public String mutationReportToolId() {
            return "typescript-text";
        }

        @Override
        public LanguageLexis lexis() {
            return ScriptLexis.INSTANCE;
        }

        @Override
        public String excludedSourceSuffix() {
            return ".tsx";
        }

        @Override
        public List<Path> testFiles(VerificationContext context) {
            return TypeScriptTestProvider.testFiles(context);
        }

        @Override
        public List<Path> sourceFiles(Path sourceRoot) {
            return TypeScriptProjectDetector.typeScriptFiles(sourceRoot);
        }

        @Override
        public String nodeExecutable(ProjectInfo project, VerificationContext context) {
            return NodeRuntime.node(project, context);
        }

        @Override
        public Map<String, String> nodeEnvironment(VerificationContext context) {
            return NodeRuntime.environment(context);
        }

        @Override
        public Path buildCheckScript(VerificationContext context) {
            return TypeScriptScripts.write(context).buildCheck();
        }
    }
}
