package dev.codewitness.javascript.generate;

import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.javascript.NodeRuntime;
import dev.codewitness.support.generate.LanguageScenarioProfile;
import dev.codewitness.support.generate.NodeScenarioTestGenerator;

import java.util.List;

/**
 * Turns declared scenarios into executable {@code node:test} modules.
 *
 * <p>A scenario supplies the oracle — the setup, the calls and the invariant, written in JavaScript in
 * {@code codewitness.yaml}; this generator supplies the mechanical part: boundary enumeration, the
 * seeded random search, concurrent execution and the structured failure report. CodeWitness never
 * invents an oracle, because an invented oracle is exactly the kind of assertion this tool exists to
 * avoid.
 *
 * <p>The generated file is an ordinary test beside the project's own tests. It is written with the
 * {@code .test.mjs} extension so it is unambiguously an ES module whatever the project's
 * {@code package.json} declares, runs with {@code node --test}, and needs no dependency beyond the Node
 * standard library. Because the generated module is ESM, a CommonJS project's fragment must import the
 * project through Node's ESM/CJS interop (named imports when the exports are statically visible,
 * otherwise the default-import form). Its class name becomes the suite name the runner reports, which is
 * what the engine maps the result back to. Each failing case throws an {@code Error} carrying a single
 * {@code CODEWITNESS_COUNTEREXAMPLE|...} line with the exact inputs, seed and case index, which is what
 * makes the counterexample reproducible.
 *
 * <p>Fragment imports ({@code import ...} lines in {@code setup}) are hoisted to the top of the module,
 * because an import cannot appear inside a function; everything else in {@code setup} runs inside each
 * case. A fragment therefore imports the project exactly as a hand-written test in the same directory
 * would, which is the convention the documentation states.
 *
 * <p>Every one of those decisions is made once in {@link NodeScenarioTestGenerator}; this class supplies
 * only the JavaScript half through a nested profile, and the only thing that half changes is that the
 * emitted module declares no types.
 */
public final class JavaScriptScenarioTestGenerator implements VerificationTools.TestGenerator {

    private final NodeScenarioTestGenerator delegate =
            new NodeScenarioTestGenerator(new JavaScriptScenarioProfile());

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return delegate.supports(project);
    }

    @Override
    public List<GeneratedTest> generate(
            VerificationContext context, Requirement requirement, List<VerificationObligation> obligations) {
        return delegate.generate(context, requirement, obligations);
    }

    /** The JavaScript half of the shared generator: its id, its Node and its untyped dialect. */
    private static final class JavaScriptScenarioProfile implements LanguageScenarioProfile {

        @Override
        public String generatorId() {
            return "javascript-scenario-generator";
        }

        @Override
        public LanguageId languageId() {
            return LanguageId.JAVASCRIPT;
        }

        @Override
        public String minimumNodeVersion() {
            return NodeRuntime.MINIMUM_TEST_RUNNER_VERSION;
        }

        @Override
        public boolean typedAnnotations() {
            return false;
        }
    }
}
