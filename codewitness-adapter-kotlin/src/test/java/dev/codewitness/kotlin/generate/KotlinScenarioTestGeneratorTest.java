package dev.codewitness.kotlin.generate;

import dev.codewitness.core.config.Scenario;
import dev.codewitness.core.requirements.ObligationKind;
import dev.codewitness.core.requirements.ObligationOrigin;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.kotlin.KotlinTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generator produces the mechanical part of property based testing: boundary values, a seeded random
 * search, and the counterexample protocol. These tests pin the shape of the emitted Kotlin.
 *
 * <p>That the emitted source <em>compiles</em> is proved where it can be: the Kotlin end-to-end test in
 * {@code codewitness-cli} builds the fixture with the real Kotlin compiler, generates tests into it and
 * requires the verdicts that only a compiled, executed test can produce. Compiling Kotlin from a unit test
 * would mean putting a compiler on this module's test classpath, which is a large dependency for a check the
 * end-to-end suite already makes against the real toolchain.
 */
class KotlinScenarioTestGeneratorTest {

    @TempDir
    Path tmp;

    private final KotlinScenarioTestGenerator generator = new KotlinScenarioTestGenerator();

    @Test
    @DisplayName("a sequence scenario produces boundary cases and a seeded random search")
    void sequenceScenarioProducesBoundaryCasesAndARandomSearch() throws IOException {
        GeneratedTest generated = generateSequence();

        assertEquals("dev.codewitness.generated.Cw_SC_001_OB_001_Test", generated.className());
        assertEquals("Cw_SC_001_OB_001_Test", generated.simpleName());
        assertEquals(List.of("R-001"), generated.requirementIds());
        assertEquals(List.of("OB-001"), generated.obligationIds());
        assertEquals("randomSearch", generated.testMethod());
        assertEquals(42L, generated.seed());

        String source = generated.sourceCode();
        assertTrue(source.startsWith("package dev.codewitness.generated\n"), source.substring(0, 60));
        assertTrue(source.contains("class Cw_SC_001_OB_001_Test {"),
                "the class name must match the file name");
        assertTrue(source.contains("CW_REQUIREMENT = \"R-001\""), "the requirement id must be recorded");
        assertTrue(source.contains("CW_OBLIGATION = \"OB-001\""), "the obligation id must be recorded");
        assertTrue(source.contains("\"observation >= 0\""), "the invariant string must be recorded");
        assertTrue(source.contains("CODEWITNESS_COUNTEREXAMPLE"),
                "the counterexample protocol marker must be emitted by the protocol helper");
    }

    @Test
    @DisplayName("one test function is emitted per boundary case, plus the search")
    void sequenceScenarioHasOneTestPerBoundaryCase() throws IOException {
        String source = generateSequence().sourceCode();

        assertEquals(4, countOccurrences(source, "@org.junit.jupiter.api.Test"),
                "three boundary cases plus the seeded random search:\n" + source);
        assertTrue(source.contains("fun case0000()"), source);
        assertTrue(source.contains("fun case0001()"), source);
        assertTrue(source.contains("fun case0002()"), source);
        assertTrue(source.contains("fun randomSearch()"), source);
        assertTrue(source.contains("val seed = cwSeed(42L)"), "the declared seed must be baked in");
        assertTrue(source.contains("val cases = cwCases(10)"), "the declared case count must be baked in");
        assertTrue(source.contains("if (pinned >= 0 && cwCase != pinned) { continue }"),
                "a single case must be replayable by index");
        assertTrue(source.contains("for (cwCase in 0 until cases)"), source);
    }

    @Test
    @DisplayName("the emitted Kotlin declares locals without types and the case signature with them")
    void localsAreInferredAndTheSignatureIsTyped() throws IOException {
        String source = generateSequence().sourceCode();

        assertTrue(source.contains("private fun runCase(cwCase: Int, cwSeed: Long?, amount: Int)"),
                "the case runner names the Kotlin type the declaration's type maps to:\n" + source);
        assertTrue(source.contains("val amount = "),
                "a local is declared without a type annotation, so the initialiser decides:\n" + source);
        assertTrue(source.contains("runCase(0, null, amount)"), source);
        assertTrue(source.contains("cwInputs(\"amount\" to amount)"), source);
    }

    @Test
    @DisplayName("declared types are spelled the Kotlin way")
    void declaredTypesAreTranslated() {
        assertEquals("Long", KotlinScenarioTestGenerator.kotlinType("long"));
        assertEquals("Int", KotlinScenarioTestGenerator.kotlinType("int"));
        assertEquals("Double", KotlinScenarioTestGenerator.kotlinType("double"));
        assertEquals("Boolean", KotlinScenarioTestGenerator.kotlinType("boolean"));
        assertEquals("String", KotlinScenarioTestGenerator.kotlinType("java.lang.String"));
        assertEquals("java.math.BigDecimal", KotlinScenarioTestGenerator.kotlinType("java.math.BigDecimal"),
                "a JVM class is spelled the same way in both languages");
        assertEquals("Any?", KotlinScenarioTestGenerator.kotlinType(""),
                "an undeclared type accepts anything rather than failing to compile");
    }

    @Test
    @DisplayName("a Double boundary is written with a decimal point, because Kotlin has no d suffix")
    void doubleLiteralsAreValidKotlin() throws IOException {
        Scenario scenario = new Scenario(
                "SC-DBL", "R-001", "a double boundary", Scenario.Mode.SINGLE, null,
                List.of(new Scenario.Step("cwSeen.add(${rate})", List.of(
                        new Scenario.Input("rate", "double", Scenario.Domain.DOUBLE,
                                List.of("1000"), null, null, 0)), "record the rate")),
                null, null, 1, 1L, 60);
        GeneratedTest generated = generate(scenario, obligation("OB-DBL", "SC-DBL"));

        assertTrue(generated.sourceCode().contains("val rate = 1000.0"),
                "`1000` would be an Int and would not compile against a Double parameter:\n"
                        + generated.sourceCode());
    }

    @Test
    @DisplayName("a dollar sign in a message is escaped, or Kotlin would read it as a template")
    void dollarSignsInMessagesAreEscaped() throws IOException {
        Scenario scenario = new Scenario(
                "SC-DOLLAR", "R-001", "a message with a dollar sign", Scenario.Mode.SINGLE, null,
                List.of(new Scenario.Step("cwSeen.add(1)", List.of(), "record")),
                "cwSeen.size()", "observation >= 0 && \"cost $5\".isNotEmpty()", 1, 1L, 60);
        GeneratedTest generated = generate(scenario, obligation("OB-DOLLAR", "SC-DOLLAR"));

        assertTrue(generated.sourceCode().contains("\\$5"),
                "an unescaped $ would be read as a template by the Kotlin compiler:\n"
                        + generated.sourceCode());
    }

    @Test
    @DisplayName("a concurrent scenario releases all threads together with a latch")
    void concurrentScenarioReleasesAllThreadsWithACountDownLatch() throws IOException {
        Scenario scenario = new Scenario(
                "SC-002", "R-001", "concurrent withdrawals", Scenario.Mode.CONCURRENT,
                "val cwSeen = java.util.ArrayList<Int>()",
                List.of(new Scenario.Step("cwSeen.add(${amount})", List.of(amount()), "record the amount")),
                "cwSeen.size", "observation >= 0", 5, 7L, 60);
        GeneratedTest generated = generate(scenario, obligation("OB-002", "SC-002"));

        assertEquals("dev.codewitness.generated.Cw_SC_002_OB_002_ConcurrencyTest", generated.className());
        String source = generated.sourceCode();
        assertTrue(source.contains("fun concurrentSearch()"), source);
        assertTrue(source.contains("val cwStart = java.util.concurrent.CountDownLatch(1)"),
                "threads must be released together:\n" + source);
        assertTrue(source.contains("cwStart.await()"), source);
        assertTrue(source.contains("cwStart.countDown()"), source);
        assertTrue(source.contains("Executors.newFixedThreadPool(8)"), source);
        assertTrue(source.contains("cwPool.shutdown()"), source);
        assertTrue(source.contains("java.util.concurrent.Callable<Unit>"),
                "a Kotlin lambda is ambiguous between Runnable and Callable, so the SAM type is explicit:\n"
                        + source);
    }

    @Test
    @DisplayName("a scenario with no searchable input produces exactly one case")
    void aScenarioWithNoSearchableInputsProducesExactlyOneSingleCase() throws IOException {
        Scenario scenario = new Scenario(
                "SC-003", "R-001", "a fixed sequence of calls is still an experiment",
                Scenario.Mode.SINGLE, null,
                List.of(new Scenario.Step("java.lang.Math.abs(-1)", List.of(), "call something")),
                null, null, 1, 1L, 60);
        GeneratedTest generated = generate(scenario, obligation("OB-003", "SC-003"));

        String source = generated.sourceCode();
        assertEquals(1, countOccurrences(source, "@org.junit.jupiter.api.Test"), source);
        assertTrue(source.contains("fun singleCase()"), source);
        assertFalse(source.contains("fun randomSearch()"), "there is nothing to search over");
        assertFalse(source.contains("fun case0000()"), source);
        assertTrue(source.contains("CODEWITNESS_COUNTEREXAMPLE"),
                "the counterexample protocol helper is always generated");
    }

    @Test
    @DisplayName("a scenario with inputs but no boundary values still produces one executed case")
    void aScenarioWithInputsButNoBoundaryValuesProducesExactlyOneCase() throws IOException {
        Scenario scenario = new Scenario(
                "SC-004", "R-001", "inputs without boundary values",
                Scenario.Mode.SINGLE, null,
                List.of(new Scenario.Step(
                        "requireNotNull(label); requireNotNull(note)",
                        List.of(
                                new Scenario.Input("label", "String", Scenario.Domain.STRING,
                                        List.of("explicit"), null, null, 0),
                                new Scenario.Input("note", "String", Scenario.Domain.STRING,
                                        List.of(), null, null, 0)), "record both")),
                null, null, 1, 1L, 60);
        GeneratedTest generated = generate(scenario, obligation("OB-004", "SC-004"));

        String source = generated.sourceCode();
        assertEquals(1, countOccurrences(source, "@org.junit.jupiter.api.Test"),
                "an empty test class reports nothing, so exactly one case must be emitted:\n" + source);
        assertTrue(source.contains("fun case0000()"), source);
        assertTrue(source.contains("val label = \"explicit\""),
                "the first declared value is used when there is one:\n" + source);
        assertTrue(source.contains("val note = \"\""),
                "the type default is used when there is none:\n" + source);
        assertTrue(source.contains("runCase(0, null, label, note)"), source);
        assertFalse(source.contains("fun singleCase()"), source);
        assertFalse(source.contains("fun randomSearch()"), source);
    }

    @Test
    @DisplayName("an obligation without a declared scenario produces nothing")
    void anObligationWithoutAScenarioProducesNothing() throws IOException {
        VerificationContext context = KotlinTestContexts.context(
                KotlinTestContexts.project(tmp.resolve("empty")), tmp.resolve("empty-run"));

        List<GeneratedTest> generated = generator.generate(
                context, requirement(), List.of(obligation("OB-404", "SC-missing")));

        assertTrue(generated.isEmpty(), "a scenario declared in the config is required to generate anything");
    }

    @Test
    @DisplayName("the generator speaks only for Kotlin and reports the counterexample protocol helper")
    void supportsOnlyKotlin() throws IOException {
        Path root = KotlinTestContexts.project(tmp.resolve("supported"));

        assertTrue(generator.supports(KotlinTestContexts.projectInfo(root)));
        assertFalse(generator.supports(dev.codewitness.core.model.ProjectInfo
                .builder(dev.codewitness.core.model.ProjectRef.of(root))
                .language(dev.codewitness.core.model.LanguageId.JAVA)
                .build()));
        assertEquals("kotlin-scenario-generator", generator.id());
    }

    // ------------------------------------------------------------------ helpers

    private GeneratedTest generateSequence() throws IOException {
        Scenario scenario = new Scenario(
                "SC-001", "R-001", "withdrawals never exceed the limit", Scenario.Mode.SEQUENCE,
                "val cwSeen = java.util.ArrayList<Int>()",
                List.of(new Scenario.Step("cwSeen.add(${amount})", List.of(amount()), "record the amount")),
                "cwSeen.size", "observation >= 0", 10, 42L, 60);
        return generate(scenario, obligation("OB-001", "SC-001"));
    }

    private GeneratedTest generate(Scenario scenario, VerificationObligation obligation) throws IOException {
        Path root = KotlinTestContexts.project(tmp.resolve("project-" + scenario.id()));
        VerificationContext context = KotlinTestContexts.context(root, root.resolve(".codewitness"),
                List.of(scenario));
        List<GeneratedTest> generated = generator.generate(context, requirement(), List.of(obligation));
        assertEquals(1, generated.size(), "one scenario produces one generated test");
        return generated.get(0);
    }

    private static Requirement requirement() {
        return Requirement.builder("R-001", "A customer may withdraw at most 1,000 per rolling 24 hours.")
                .title("Withdrawal limit")
                .build();
    }

    private static VerificationObligation obligation(String id, String scenarioId) {
        return new VerificationObligation(id, "R-001", "Execute scenario " + scenarioId,
                ObligationKind.PROPERTY, ObligationOrigin.USER_DECLARED, scenarioId,
                Map.of("scenario", scenarioId), false);
    }

    private static Scenario.Input amount() {
        return new Scenario.Input("amount", "int", Scenario.Domain.INTEGER,
                List.of("0", "1000", "1001"), "0", "1001", 0);
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
