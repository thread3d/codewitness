package dev.codewitness.ruby.generate;

import dev.codewitness.core.config.Scenario;
import dev.codewitness.core.engine.CounterexampleProtocol;
import dev.codewitness.core.exec.LocalProcessSandbox;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.requirements.ObligationKind;
import dev.codewitness.core.requirements.ObligationOrigin;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.ruby.RubyTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rendering of declared scenarios as RSpec spec files.
 *
 * <p>The generator is tested by reading the Ruby it produces. Nothing here needs Ruby or RSpec: the
 * contract is the source text, and a test that had to run a suite to check a spec's name would be testing
 * the machine. What the tests are really about is the two things that make a generated file attributable
 * and replayable - the dotted path it is recorded under, which is how the report parser attributes a
 * result back to it, and the counterexample protocol written into its helpers.
 */
class RubyScenarioTestGeneratorTest {

    @Test
    @DisplayName("a declared scenario becomes an RSpec file with a structured counterexample protocol")
    void rendersScenario(@TempDir Path root, @TempDir Path run) {
        Scenario scenario = rolling();
        VerificationContext context = context(root, run, scenario);

        List<GeneratedTest> generated = new RubyScenarioTestGenerator()
                .generate(context, requirement("R-001"), List.of(obligation("OB-001", "SC-ROLLING")));

        assertEquals(1, generated.size());
        GeneratedTest test = generated.getFirst();
        assertEquals("spec.generated.cw_sc_SC_ROLLING_spec", test.className(),
                "the recorded name is the path its report will carry with the separators dotted, because "
                        + "that is what the engine matches a result against");
        assertEquals(List.of("R-001"), test.requirementIds());
        assertEquals(List.of("OB-001"), test.obligationIds());
        assertEquals(Long.valueOf(42L), test.seed());
        assertTrue(test.checksInvariant(), "an observation and an invariant are what make the file an oracle");
        assertEquals("cw_random_search", test.testMethod(),
                "a SEQUENCE scenario searches, so the search example is the one worth replaying");

        String source = test.sourceCode();
        String firstCodeLine = source.lines()
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .findFirst()
                .orElseThrow();
        assertEquals("require 'spec_helper'", firstCodeLine,
                "the header comments come first, and the first code line is what loads the project's own "
                        + "RSpec configuration: a spec that lives outside the tree it configures loads "
                        + "nothing: " + source);
        assertTrue(source.contains("RSpec.describe 'spec.generated.cw_sc_SC_ROLLING_spec' do"), source);
        assertTrue(source.contains(CounterexampleProtocol.MARKER),
                "the marker comes from the engine's own protocol, so the generated file cannot disagree "
                        + "with the decoder about the line it writes: " + source);
        assertTrue(source.contains("ledger.withdraw(t1, a1)"),
                "the placeholder must become the Ruby local the fragment names: " + source);
        assertTrue(source.contains("t1 = 85800000"),
                "a declared boundary value is enumerated into a case: " + source);
        assertTrue(source.contains("observation = ledger.total_within_last_24_hours(t2)"), source);
        assertTrue(source.contains("cw_check(observation, (observation <= 100000)"), source);
        assertTrue(source.contains("it 'cw_case0000' do"), source);
        assertTrue(source.contains("cw_random_search"), source);
        assertTrue(source.contains("cw_seed(42)"), source);
        assertTrue(source.contains("cw_cases(200)"), source);
        assertTrue(source.contains("ExpectationNotMetError"),
                "a failing case must raise RSpec's own failure, or the report shows an error: " + source);
        assertTrue(source.contains("be(true)"),
                "the invariant is a boolean by declaration, so a merely truthy expression must fail: " + source);
        assertFalse(source.contains("${"), "no placeholder may survive into generated code:\n" + source);
    }

    @Test
    @DisplayName("the spec file lives under the test root's generated directory and is named from the scenario")
    void theSpecFileLivesInTheGeneratedDirectory(@TempDir Path root, @TempDir Path run) {
        Scenario scenario = rolling();
        VerificationContext context = context(root, run, scenario);

        assertEquals("cw_sc_SC_ROLLING_spec", RubyScenarioTestGenerator.specName(scenario));
        assertEquals(root.resolve("spec").resolve("generated").resolve("cw_sc_SC_ROLLING_spec.rb"),
                RubyScenarioTestGenerator.specFile(context, RubyScenarioTestGenerator.specName(scenario)),
                "RSpec discovers a spec by its file name, so the generated file has to be a _spec.rb below "
                        + "the tree RSpec scans");
        assertEquals("spec.generated.cw_sc_SC_ROLLING_spec",
                RubyScenarioTestGenerator.classNameFor(context, scenario),
                "the same rule the report parser applies to RSpec's file_path: ./ dropped, separators "
                        + "dotted, .rb removed");
    }

    @Test
    @DisplayName("the file name is a Ruby identifier whatever the scenario id is, and punctuation cannot collide")
    void identifiersDoNotCollide(@TempDir Path bare, @TempDir Path run) {
        Scenario hyphen = noInputScenario("SC-A_B");
        Scenario plain = noInputScenario("SC-AB");

        assertEquals("cw_sc_SC_A_B_spec", RubyScenarioTestGenerator.specName(hyphen));
        assertEquals("cw_sc_SC_AB_spec", RubyScenarioTestGenerator.specName(plain));
        assertNotEquals(RubyScenarioTestGenerator.specName(hyphen), RubyScenarioTestGenerator.specName(plain),
                "a deletion would make SC-A_B and SC-AB the same file, and one requirement would be judged "
                        + "by the other's experiment");
        assertEquals("Scenario", RubyScenarioTestGenerator.identifier(""),
                "an empty id still has to yield a usable identifier");
        assertEquals("cw_sc_SC_ROLLING_spec",
                RubyScenarioTestGenerator.classNameFor(RubyTestContexts.context(bare, run), rolling()),
                "a project with no test directory has no path to name, so the file name is the identifier "
                        + "and the executor reports the batch rather than pretending it ran");
    }

    @Test
    @DisplayName("a concurrent scenario is executed sequentially, and the generated file says so")
    void rendersConcurrentScenarioWithItsLimit(@TempDir Path root, @TempDir Path run) {
        Scenario scenario = new Scenario(
                "SC-CONC", "R-004", "concurrent withdrawals", Scenario.Mode.CONCURRENT,
                "ledger = Ledger.new",
                List.of(new Scenario.Step("ledger.withdraw(${amount})", List.of(
                        new Scenario.Input("amount", "int", Scenario.Domain.INTEGER, List.of("200"),
                                "100", "300", 0)), "step")),
                null, null, 5, 7L, 60);
        VerificationContext context = context(root, run, scenario);

        GeneratedTest test = new RubyScenarioTestGenerator()
                .generate(context, requirement("R-004"), List.of(obligation("OB-009", "SC-CONC")))
                .getFirst();

        assertEquals("spec.generated.cw_sc_SC_CONC_spec", test.className());
        assertEquals("cw_concurrent_search", test.testMethod());
        assertFalse(test.checksInvariant(), "no invariant was declared, so the file asserts nothing");
        String source = test.sourceCode();
        assertTrue(source.contains("it 'cw_concurrent_search' do"), source);
        assertTrue(source.contains("LIMIT: the scenario declares mode \"concurrent\""),
                "the steps run once, in order, and the limit must be visible where the test is: " + source);
        assertTrue(source.contains("Random.new(cw_seed_value + cw_case)"),
                "each case gets a fresh generator seeded from the run's seed and the case index, so one "
                        + "case is reproducible from the seed and the index alone: " + source);
        assertTrue(source.contains("(no invariant declared)"), source);
        assertFalse(source.contains("cw_check(observation,"),
                "a test with no oracle asserted nothing, and must be reported as such rather than dressed up");
    }

    @Test
    @DisplayName("a scenario with no inputs emits one single case rather than an empty file")
    void aScenarioWithoutInputsEmitsASingleCase(@TempDir Path root, @TempDir Path run) {
        Scenario scenario = new Scenario(
                "SC-EMPTY", "R-001", "no inputs", Scenario.Mode.SINGLE,
                "ledger = Ledger.new",
                List.of(new Scenario.Step("ledger.flush", List.of(), "flush")),
                "ledger.total", null, 1, 1L, 60);
        VerificationContext context = context(root, run, scenario);

        GeneratedTest test = new RubyScenarioTestGenerator()
                .generate(context, requirement("R-001"), List.of(obligation("OB-010", "SC-EMPTY")))
                .getFirst();

        assertEquals("cw_single_case", test.testMethod());
        String source = test.sourceCode();
        assertTrue(source.contains("it 'cw_single_case' do"), source);
        assertTrue(source.contains("@cw_inputs = []"), source);
        assertFalse(source.contains("cw_random_search"),
                "there is no input to search over, and an example that does not exist must not be named");
        assertFalse(source.contains("it 'cw_case"), source);
    }

    @Test
    @DisplayName("inputs without boundary values still produce exactly one executed case")
    void inputsWithoutBoundaryValuesProduceOneCase(@TempDir Path root, @TempDir Path run) {
        Scenario scenario = new Scenario(
                "SC-DEFAULTS", "R-001", "inputs without boundary values", Scenario.Mode.SINGLE,
                "ledger = Ledger.new",
                List.of(new Scenario.Step("ledger.record(${label}, ${note})", List.of(
                        new Scenario.Input("label", "string", Scenario.Domain.STRING, List.of("explicit"),
                                null, null, 0),
                        new Scenario.Input("note", "string", Scenario.Domain.STRING, List.of(),
                                null, null, 0)), "record both")),
                null, null, 1, 1L, 60);
        VerificationContext context = context(root, run, scenario);

        GeneratedTest test = new RubyScenarioTestGenerator()
                .generate(context, requirement("R-001"), List.of(obligation("OB-007", "SC-DEFAULTS")))
                .getFirst();
        String source = test.sourceCode();

        assertEquals("cw_case0000", test.testMethod());
        assertEquals(1, countOccurrences(source, "it 'cw_case"),
                "an empty spec file reports nothing, and a scenario that reports nothing must not be mistaken "
                        + "for one that ran and passed:\n" + source);
        assertTrue(source.contains("label = 'explicit'"),
                "the first declared value is used when there is one:\n" + source);
        assertTrue(source.contains("note = ''"),
                "the type default is used when there is none, as a value rather than as quoted source:\n" + source);
        assertTrue(source.contains("['label', cw_text(label)]"), source);
        assertTrue(source.contains("['note', cw_text(note)]"), source);
        assertTrue(source.contains("ledger.record(label, note)"), source);
        assertFalse(source.contains("cw_random_search"),
                "a SINGLE scenario with one case has nothing to search over");
        assertFalse(source.contains("${"), source);
    }

    @Test
    @DisplayName("a Ruby type name is read when the scenario declares no domain")
    void rubyTypeNamesWithoutADomainProduceRubyLiterals(@TempDir Path root, @TempDir Path run) {
        // The configuration's domain table knows Java spellings, so `type: string` and `type: bool` arrive
        // here as RAW. A generated literal of `explicit` or `FALSE` would not parse, which is why the Ruby
        // type names a scenario author would write are read as well.
        Scenario scenario = new Scenario(
                "SC-TYPES", "R-001", "ruby type names", Scenario.Mode.SINGLE,
                "ledger = Ledger.new",
                List.of(new Scenario.Step("ledger.record(${label}, ${flag}, ${price})", List.of(
                        new Scenario.Input("label", "string", Scenario.Domain.RAW, List.of(), null, null, 0),
                        new Scenario.Input("flag", "bool", Scenario.Domain.RAW, List.of(), null, null, 0),
                        new Scenario.Input("price", "float", Scenario.Domain.RAW, List.of("2.5"),
                                null, null, 0)), "record the three")),
                null, null, 1, 3L, 60);
        VerificationContext context = context(root, run, scenario);

        String source = new RubyScenarioTestGenerator()
                .generate(context, requirement("R-001"), List.of(obligation("OB-002", "SC-TYPES")))
                .getFirst().sourceCode();

        assertTrue(source.contains("label = ''"), "a string type takes a string literal:\n" + source);
        assertTrue(source.contains("flag = false"), "a bool type takes a boolean literal:\n" + source);
        assertTrue(source.contains("price = 2.5"), "a float type takes a numeric literal:\n" + source);
        assertTrue(source.contains("ledger.record(label, flag, price)"), source);
    }

    @Test
    @DisplayName("one scenario declared for two obligations produces one file covering both")
    void oneScenarioProducesOneFileForEveryObligation(@TempDir Path root, @TempDir Path run) {
        VerificationContext context = context(root, run, rolling());

        List<GeneratedTest> generated = new RubyScenarioTestGenerator().generate(
                context, requirement("R-001"),
                List.of(obligation("OB-001", "SC-ROLLING"), obligation("OB-002", "SC-ROLLING")));

        assertEquals(1, generated.size(),
                "two files with one name would be written to one path, and one obligation would be lost");
        assertEquals(List.of("OB-001", "OB-002"), generated.getFirst().obligationIds());
        assertTrue(generated.getFirst().sourceCode().contains("OB-001, OB-002"),
                generated.getFirst().sourceCode());
    }

    @Test
    @DisplayName("an obligation that names no declared scenario produces no test")
    void unknownScenarioProducesNothing(@TempDir Path root, @TempDir Path run) {
        VerificationContext context = context(root, run, rolling());

        List<GeneratedTest> generated = new RubyScenarioTestGenerator().generate(
                context, requirement("R-009"), List.of(obligation("OB-009", "SC-NOT-DECLARED")));

        assertEquals(List.of(), generated);
    }

    @Test
    @DisplayName("the generator names itself and claims only Ruby projects")
    void namesItselfAndClaimsOnlyRuby(@TempDir Path root) {
        RubyScenarioTestGenerator generator = new RubyScenarioTestGenerator();

        assertEquals("ruby-scenarios", generator.id());
        assertTrue(generator.supports(RubyTestContexts.projectInfo(root)));
        assertFalse(generator.supports(ProjectInfo.builder(ProjectRef.of(root))
                        .language(LanguageId.JAVA)
                        .build()),
                "a Java scenario is the Java generator's to render");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * @return a context around a project that has a test tree, so the generated spec has a path to name
     *
     * <p>The helper's {@code spec_helper.rb} is what makes the detector record {@code spec/} as a test
     * root; without it there is no tree for RSpec to scan and the generator falls back to the bare file
     * name.
     */
    private static VerificationContext context(Path root, Path run, Scenario... scenarios) {
        try {
            RubyTestContexts.write(root, "spec/spec_helper.rb", "# the project's own helper\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return RubyTestContexts.context(RubyTestContexts.projectInfo(root), run, List.<Scenario>of(scenarios),
                new LocalProcessSandbox(List.of(root, run)));
    }

    private static Requirement requirement(String id) {
        return Requirement.builder(id, "declared scenario").title("Rolling withdrawal limit").build();
    }

    private static VerificationObligation obligation(String id, String scenarioId) {
        return new VerificationObligation(id, "R-001", "execute scenario", ObligationKind.PROPERTY,
                ObligationOrigin.USER_DECLARED, scenarioId, Map.of("scenario", scenarioId), false);
    }

    /** The fixture's own scenario, reduced to the shape the DSL fixes. */
    private static Scenario rolling() {
        return new Scenario(
                "SC-ROLLING", "R-001", "two withdrawals across midnight", Scenario.Mode.SEQUENCE,
                "ledger = Ledger.new",
                List.of(new Scenario.Step("ledger.withdraw(${t1}, ${a1})", List.of(
                        new Scenario.Input("t1", "int", Scenario.Domain.INTEGER, List.of("85800000"),
                                "0", "172800000", 0),
                        new Scenario.Input("a1", "int", Scenario.Domain.INTEGER, List.of("70000"),
                                "100", "90000", 0)), "first step"),
                        new Scenario.Step("ledger.withdraw(${t2}, ${a2})", List.of(
                                new Scenario.Input("t2", "int", Scenario.Domain.INTEGER, List.of("90000000"),
                                        null, null, 0),
                                new Scenario.Input("a2", "int", Scenario.Domain.INTEGER, List.of("70000"),
                                        null, null, 0)), "second step")),
                "ledger.total_within_last_24_hours(${t2})",
                "observation <= 100000",
                200, 42L, 60);
    }

    private static Scenario noInputScenario(String id) {
        return new Scenario(id, "R-001", "x", Scenario.Mode.SINGLE,
                "ledger = Ledger.new",
                List.of(new Scenario.Step("ledger.flush", List.of(), "flush")),
                null, null, 1, 1L, 60);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
