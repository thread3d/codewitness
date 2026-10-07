package dev.codewitness.typescript.generate;

import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.support.generate.LanguageScenarioProfile;
import dev.codewitness.support.generate.NodeScenarioTestGenerator;
import dev.codewitness.typescript.NodeRuntime;

import java.util.List;

/**
 * Turns declared scenarios into executable {@code node:test} modules.
 *
 * <p>A scenario supplies the oracle — the setup, the calls and the invariant, written in TypeScript in
 * {@code codewitness.yaml}; this generator supplies the mechanical part: boundary enumeration, the
 * seeded random search, concurrent execution and the structured failure report. CodeWitness never
 * invents an oracle, because an invented oracle is exactly the kind of assertion this tool exists to
 * avoid.
 *
 * <p>The generated file is an ordinary test beside the project's own tests, so it runs with
 * {@code node --test}, executes TypeScript directly by stripping types, and needs no dependency beyond
 * the Node standard library. It is named {@code <class>.test.ts} so the runner's JUnit report names the
 * same class the engine maps the result back to. Each failing case throws an {@code Error} carrying a
 * single {@code CODEWITNESS_COUNTEREXAMPLE|...} line with the exact inputs, seed and case index, which
 * is what makes the counterexample reproducible.
 *
 * <p>Fragment imports ({@code import ...} lines in {@code setup}) are hoisted to the top of the module,
 * because an import cannot appear inside a function; everything else in {@code setup} runs inside each
 * case. A fragment therefore imports the project exactly as a hand-written test in the same directory
 * would, which is the convention the documentation states.
 *
 * <p>Every one of those decisions is made once in {@link NodeScenarioTestGenerator}; this class supplies
 * only the TypeScript half through a nested profile. That half declares the type of each input and
 * helper, because the project's own configuration type-checks the generated file and the annotations are
 * what make it compile, and it names the Node release type stripping is enabled by default from.
 */
public final class TypeScriptScenarioTestGenerator implements VerificationTools.TestGenerator {

    private final NodeScenarioTestGenerator delegate =
            new NodeScenarioTestGenerator(new TypeScriptScenarioProfile());

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

    /** The TypeScript half of the shared generator: its id, its Node and its typed dialect. */
    private static final class TypeScriptScenarioProfile implements LanguageScenarioProfile {

        @Override
        public String generatorId() {
            return "typescript-scenario-generator";
        }

        @Override
        public LanguageId languageId() {
            return LanguageId.TYPESCRIPT;
        }

        @Override
        public String minimumNodeVersion() {
            return NodeRuntime.MINIMUM_TYPE_STRIPPING_VERSION;
        }

        @Override
        public boolean typedAnnotations() {
            return true;
        }
    }
}
