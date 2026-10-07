package dev.codewitness.go.generate;

import dev.codewitness.core.config.Scenario;
import dev.codewitness.core.requirements.ObligationKind;
import dev.codewitness.core.requirements.ObligationOrigin;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.go.GoTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rendering of declared scenarios as executable Go tests. */
class GoScenarioTestGeneratorTest {

    private static Scenario sequenceScenario() {
        return new Scenario(
                "SC-ROLLING", "R-001", "two withdrawals across midnight", Scenario.Mode.SEQUENCE,
                "l := New()",
                List.of(new Scenario.Step("l.Withdraw(${t1}, ${a1})", List.of(
                        new Scenario.Input("t1", "int64", Scenario.Domain.INTEGER, List.of("85800000"),
                                "0", "172800000", 0),
                        new Scenario.Input("a1", "int64", Scenario.Domain.INTEGER, List.of("70000"),
                                "10000", "90000", 0)), "first step")),
                "l.TotalWithinLast24Hours(${t1})",
                "observation <= 100000",
                25, 42L, 60);
    }

    private static VerificationObligation obligation(String id, String requirement, String scenario) {
        return new VerificationObligation(id, requirement, "execute scenario",
                ObligationKind.PROPERTY, ObligationOrigin.USER_DECLARED, scenario,
                Map.of("scenario", scenario), false);
    }

    @Test
    @DisplayName("a sequence scenario becomes a Go test with the counterexample protocol")
    void rendersSequenceScenario(@TempDir Path root, @TempDir Path runDirectory) {
        Scenario scenario = sequenceScenario();
        Requirement requirement = Requirement.builder("R-001", "rolling limit")
                .title("Rolling withdrawal limit").build();
        VerificationContext context = GoTestContexts.context(root, runDirectory, List.of(scenario));

        List<GeneratedTest> generated = new GoScenarioTestGenerator()
                .generate(context, requirement, List.of(obligation("OB-001", "R-001", "SC-ROLLING")));

        assertEquals(1, generated.size());
        GeneratedTest test = generated.getFirst();
        assertEquals("TestCwScRollingOb001", test.className());
        assertEquals("TestCwScRollingOb001", test.testMethod());
        assertEquals(List.of("OB-001"), test.obligationIds());

        String source = test.sourceCode();
        assertTrue(source.contains("package main"), source);
        assertTrue(source.contains("const cwRequirementTestCwScRollingOb001 = \"R-001\""), source);
        assertTrue(source.contains("func TestCwScRollingOb001Case0000(t *testing.T)"), source);
        assertTrue(source.contains("func TestCwScRollingOb001Random(t *testing.T)"), source);
        assertTrue(source.contains("var t1 int64 = 85800000"), source);
        assertTrue(source.contains("cwIntTestCwScRollingOb001(&rnd, 10000, 90000)"), source);
        assertTrue(source.contains("l.Withdraw(t1, a1)"), "the placeholder must be substituted: " + source);
        assertTrue(source.contains("cwFailTestCwScRollingOb001(t, caseIndex"), source);
        assertTrue(source.contains("CODEWITNESS_COUNTEREXAMPLE"), source);
        assertTrue(source.contains("t.Errorf(\"%s\", text.String())"), source);
        assertFalse(source.contains("${"), "no placeholder may survive into generated code:\n" + source);
        // Each invariant check is scoped so several steps can each declare an observation.
        assertTrue(source.contains("observation <= 100000"), source);
        long observations = source.lines().filter(line -> line.strip().startsWith("observation :=")).count();
        assertEquals(2, observations, "one observation declaration per step:\n" + source);
    }

    @Test
    @DisplayName("a concurrent scenario uses goroutines and a start barrier")
    void rendersConcurrentScenario(@TempDir Path root, @TempDir Path runDirectory) {
        Scenario scenario = new Scenario(
                "SC-CONC", "R-004", "concurrent withdrawals", Scenario.Mode.CONCURRENT,
                "l := New()",
                List.of(new Scenario.Step("l.Withdraw(0, ${amount})", List.of(
                        new Scenario.Input("amount", "int64", Scenario.Domain.INTEGER, List.of("200"),
                                "100", "300", 0)), "step")),
                null, null, 5, 7L, 60);
        Requirement requirement = Requirement.builder("R-004", "concurrency").build();
        VerificationContext context = GoTestContexts.context(root, runDirectory, List.of(scenario));

        List<GeneratedTest> generated = new GoScenarioTestGenerator().generate(
                context, requirement, List.of(obligation("OB-009", "R-004", "SC-CONC")));

        GeneratedTest test = generated.getFirst();
        assertEquals("TestCwScConcOb009Concurrent", test.className());
        String source = test.sourceCode();
        assertTrue(source.contains("func TestCwScConcOb009ConcurrentSearch(t *testing.T)"), source);
        assertTrue(source.contains("var cwWait sync.WaitGroup"), source);
        assertTrue(source.contains("go func()"), source);
        assertTrue(source.contains("close(cwStart)"), source);
    }

    @Test
    @DisplayName("a scenario with no inputs still produces one executable case")
    void rendersSingleCaseWithoutInputs(@TempDir Path root, @TempDir Path runDirectory) {
        Scenario scenario = new Scenario(
                "SC-FEE", "R-002", "fee", Scenario.Mode.SINGLE,
                "l := New()",
                List.of(new Scenario.Step("l.Withdraw(0, 10000); l.ApplyFee(50)", List.of(), "record")),
                "l.TotalWithinLast24Hours(3600000)", "observation == 10050", 1, 1L, 60);
        Requirement requirement = Requirement.builder("R-002", "fee").build();
        VerificationContext context = GoTestContexts.context(root, runDirectory, List.of(scenario));

        List<GeneratedTest> generated = new GoScenarioTestGenerator().generate(
                context, requirement, List.of(obligation("OB-002", "R-002", "SC-FEE")));

        String source = generated.getFirst().sourceCode();
        assertTrue(source.contains("func TestCwScFeeOb002Single(t *testing.T)"), source);
        assertTrue(source.contains("cwInputs := []cwInputTestCwScFeeOb002{}"), source);
        assertFalse(source.contains("func TestCwScFeeOb002Random"), "a single case needs no random search");
    }

    @Test
    @DisplayName("inputs without boundary values still produce exactly one executed case")
    void rendersOneDefaultCaseWhenNoInputHasBoundaryValues(@TempDir Path root, @TempDir Path runDirectory) {
        Scenario scenario = new Scenario(
                "SC-DEFAULTS", "R-001", "inputs without boundary values", Scenario.Mode.SINGLE,
                "l := New()",
                List.of(new Scenario.Step("l.Record(${label}, ${note})", List.of(
                        new Scenario.Input("label", "string", Scenario.Domain.STRING, List.of("explicit"),
                                null, null, 0),
                        new Scenario.Input("note", "string", Scenario.Domain.STRING, List.of(),
                                null, null, 0)), "record both")),
                null, null, 1, 1L, 60);
        Requirement requirement = Requirement.builder("R-001", "defaults").build();
        VerificationContext context = GoTestContexts.context(root, runDirectory, List.of(scenario));

        List<GeneratedTest> generated = new GoScenarioTestGenerator().generate(
                context, requirement, List.of(obligation("OB-007", "R-001", "SC-DEFAULTS")));

        assertEquals(1, generated.size());
        String source = generated.getFirst().sourceCode();
        assertEquals(1, countOccurrences(source, "(t *testing.T) {"),
                "an empty test file reports nothing, so exactly one function must be emitted:\n" + source);
        assertTrue(source.contains("func TestCwScDefaultsOb007Case0000(t *testing.T)"), source);
        assertTrue(source.contains("var label string = \"explicit\""),
                "the first declared value is used when there is one:\n" + source);
        assertTrue(source.contains("var note string = \"\""),
                "the type default is used when there is none:\n" + source);
        assertTrue(source.contains("{\"label\", fmt.Sprintf(\"%v\", label)}"), source);
        assertTrue(source.contains("{\"note\", fmt.Sprintf(\"%v\", note)}"), source);
        assertFalse(source.contains("TestCwScDefaultsOb007Single(t *testing.T)"), source);
        assertFalse(source.contains("TestCwScDefaultsOb007Random(t *testing.T)"), source);
        assertFalse(source.contains("${"), source);
    }

    @Test
    @DisplayName("two scenarios generated into one package carry distinct helper names")
    void helpersAreNamespacedPerGeneratedTest() {
        assertEquals("ScRolling", GoScenarioTestGenerator.camel("SC-ROLLING"));
        assertEquals("Ob001", GoScenarioTestGenerator.camel("OB-001"));
        assertEquals("Case", GoScenarioTestGenerator.camel("---"));
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
