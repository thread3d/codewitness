package dev.codewitness.javascript.mutation;

import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.javascript.JavaScriptProjectDetector;
import dev.codewitness.javascript.JavaScriptScripts;
import dev.codewitness.javascript.JavaScriptTestProvider;
import dev.codewitness.javascript.NodeRuntime;
import dev.codewitness.support.mutation.LanguageMutationProfile;
import dev.codewitness.support.mutation.TextMutationTester;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Mutation testing for JavaScript using a bounded source mutator.
 *
 * <p>A mutation framework is an optional dependency, and a project should not lose mutation evidence
 * just because it is not installed. The adapter therefore mutates the source text itself through
 * {@link dev.codewitness.support.mutation.TextMutator}, re-runs the project's own test suite for each
 * mutant with {@code node --test}, and reports the mutants the suite could not detect as survivors.
 *
 * <p>The loop, its bounds and its restore guarantee live once in
 * {@link TextMutationTester}; this class supplies only the JavaScript half through a nested profile.
 *
 * <p>A mutant the runtime cannot parse is non-viable and is excluded from the score rather than counted
 * as killed; the parse is checked with the same helper the build uses, so "does not parse" and "was
 * detected" are never confused. The run is bounded three ways: a fixed operator set, a mutant cap and a
 * wall-clock budget, and every file is restored in a {@code finally} block so a crash cannot leave a
 * mutated source tree behind.
 *
 * <p>Surviving mutants never change a verdict: they are prominent evidence and a run limitation,
 * exactly like the Java adapter's PIT results.
 */
public final class JavaScriptMutationTester implements VerificationTools.MutationTester {

    private final TextMutationTester delegate = new TextMutationTester(new JavaScriptMutationProfile());

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

    /** The JavaScript half of the shared mutation tester: its sources, its tests and its Node. */
    private static final class JavaScriptMutationProfile implements LanguageMutationProfile {

        @Override
        public String languageName() {
            return "JavaScript";
        }

        @Override
        public LanguageId languageId() {
            return LanguageId.JAVASCRIPT;
        }

        @Override
        public String mutationTesterId() {
            return "javascript-text-mutation";
        }

        @Override
        public String mutationReportToolId() {
            return "javascript-text";
        }

        @Override
        public String excludedSourceSuffix() {
            return ".jsx";
        }

        @Override
        public List<Path> testFiles(VerificationContext context) {
            return JavaScriptTestProvider.testFiles(context);
        }

        @Override
        public List<Path> sourceFiles(Path sourceRoot) {
            return JavaScriptProjectDetector.javascriptFiles(sourceRoot);
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
            return JavaScriptScripts.write(context).buildCheck();
        }
    }
}
