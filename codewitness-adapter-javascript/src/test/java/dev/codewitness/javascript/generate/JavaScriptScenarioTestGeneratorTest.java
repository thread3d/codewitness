package dev.codewitness.javascript.generate;

import dev.codewitness.core.config.Scenario;
import dev.codewitness.core.requirements.ObligationKind;
import dev.codewitness.core.requirements.ObligationOrigin;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.javascript.JavaScriptTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rendering of declared scenarios as executable node:test modules. */
class JavaScriptScenarioTestGeneratorTest {

    private static Scenario sequenceScenario() {
        return new Scenario(
                "SC-ROLLING", "R-001", "two withdrawals across midnight", Scenario.Mode.SEQUENCE,
                "import { WithdrawalLedger } from \"../src/ledger.js\";\nconst ledger = new WithdrawalLedger();",
                List.of(new Scenario.Step("ledger.withdraw(#{t1}, #{a1})", List.of(
                        new Scenario.Input("t1", "bigint", Scenario.Domain.INTEGER, List.of("85800000"),
                                "0", "172800000", 0),
                        new Scenario.Input("a1", "bigint", Scenario.Domain.INTEGER, List.of("70000"),
                                "10000", "90000", 0)), "first step"),
                        new Scenario.Step("ledger.withdraw(#{t2}, #{a2})", List.of(
                                new Scenario.Input("t2", "bigint", Scenario.Domain.INTEGER, List.of("90000000"),
                                        "0", "90000000", 0),
                                new Scenario.Input("a2", "bigint", Scenario.Domain.INTEGER, List.of("70000"),
                                        "10000", "90000", 0)), "second step")),
                "ledger.totalWithinLast24Hours(#{t2})",
                "observation <= 100000n",
                25, 42L, 60);
    }

    private static VerificationObligation obligation(String scenarioId) {
        return new VerificationObligation("OB-001", "R-001", "execute scenario",
                ObligationKind.PROPERTY, ObligationOrigin.USER_DECLARED, scenarioId,
                Map.of("scenario", scenarioId), false);
    }

    @Test
    @DisplayName("a sequence scenario becomes a node:test module with the counterexample protocol")
    void rendersSequenceScenario(@TempDir Path root, @TempDir Path runDirectory) {
        Scenario scenario = sequenceScenario();
        Requirement requirement = Requirement.builder("R-001", "rolling limit")
                .title("Rolling withdrawal limit").build();
        VerificationContext context = JavaScriptTestContexts.context(root, runDirectory, List.of(scenario));

        List<GeneratedTest> generated = new JavaScriptScenarioTestGenerator()
                .generate(context, requirement, List.of(obligation("SC-ROLLING")));

        assertEquals(1, generated.size());
        GeneratedTest test = generated.getFirst();
        assertEquals("cw_sc_rolling_ob_001", test.className());
        assertEquals("cw_random_search", test.testMethod());
        assertEquals(List.of("OB-001"), test.obligationIds());

        String source = test.sourceCode();
        assertTrue(source.contains("import test from \"node:test\";"), source);
        assertTrue(source.contains("import { WithdrawalLedger } from \"../src/ledger.js\";"),
                "fragment imports must be hoisted to the module top: " + source);
        assertTrue(source.contains("const CW_REQUIREMENT = \"R-001\";"), source);
        assertTrue(source.contains("const CW_OBLIGATION = \"OB-001\";"), source);
        assertTrue(source.contains("test(\"cw_case0000\""), source);
        assertTrue(source.contains("test(\"cw_random_search\""), source);
        assertTrue(source.contains("const t1 = BigInt(85800000);"), source);
        assertTrue(source.contains("BigInt(cwInt(rnd, 10000, 90000))"), source);
        assertTrue(source.contains("ledger.withdraw(t1, a1);"), "the placeholder must be substituted: " + source);
        assertTrue(source.contains("const cwSeedValue = cwSeed(42);"), source);
        assertTrue(source.contains("cwFail(cwCase, cwSeedValue, cwInputs,"), source);
        assertTrue(source.contains("CODEWITNESS_COUNTEREXAMPLE"), source);
        assertTrue(source.contains("value.replace(/\\|/g, \"/\")"),
                "a counterexample field carrying the protocol's delimiter must be sanitised, or the "
                        + "engine cannot split the line: " + source);
        assertFalse(source.contains("#{"), "no placeholder may survive into generated code:\n" + source);
        // The generated module is plain JavaScript: no TypeScript type annotation may appear anywhere.
        assertFalse(source.contains(": bigint"), "the generator must not emit a type annotation:\n" + source);
        assertFalse(source.contains(": number"), "the generator must not emit a type annotation:\n" + source);
        // Template literals are legitimate JavaScript, so a `${...}` in a helper must be left alone.
        assertTrue(source.contains("return `cw-${this.next() % 10000n}`;"), source);
        // JavaScript has no let-shadowing, so each step's observation must live in its own block.
        // One boundary case plus the random search, each with two steps, declare four observations.
        assertEquals(4, source.split("const observation", -1).length - 1,
                "each step declares its own observation: " + source);
        assertTrue(source.contains("{\n      const observation"), source);
        assertTrue(source.contains("const observation = ledger.totalWithinLast24Hours(t2);"),
                "the observation placeholder must be substituted: " + source);
    }

    @Test
    @DisplayName("a concurrent scenario uses Promise.all and a shared barrier")
    void rendersConcurrentScenario(@TempDir Path root, @TempDir Path runDirectory) {
        Scenario scenario = new Scenario(
                "SC-CONC", "R-004", "concurrent withdrawals", Scenario.Mode.CONCURRENT,
                "import { WithdrawalLedger } from \"../src/ledger.js\";\nconst ledger = new WithdrawalLedger();",
                List.of(new Scenario.Step("ledger.withdraw(0n, #{amount});", List.of(
                        new Scenario.Input("amount", "bigint", Scenario.Domain.INTEGER, List.of("200"),
                                "100", "300", 0)), "step")),
                "ledger.totalWithinLast24Hours(0n)", "observation >= 0n", 5, 7L, 60);
        Requirement requirement = Requirement.builder("R-004", "concurrency").build();
        VerificationContext context = JavaScriptTestContexts.context(root, runDirectory, List.of(scenario));

        List<GeneratedTest> generated = new JavaScriptScenarioTestGenerator()
                .generate(context, requirement, List.of(obligation("SC-CONC")));

        assertEquals(1, generated.size());
        GeneratedTest test = generated.getFirst();
        assertEquals("cw_sc_conc_ob_001_concurrency", test.className());
        assertEquals("cw_concurrent_search", test.testMethod());
        String source = test.sourceCode();
        assertTrue(source.contains("await Promise.all(Array.from({ length: 8 }"), source);
        assertTrue(source.contains("cwBarrier(8)"), source);
        assertTrue(source.contains("async () => {"), source);
        assertTrue(source.contains("const amount = BigInt(cwInt(rnd, 100, 300));"),
                "a bigint input is drawn and wrapped without a type annotation: " + source);
    }

    @Test
    @DisplayName("a scenario with no inputs still produces one executable case")
    void rendersSingleCaseWithoutInputs(@TempDir Path root, @TempDir Path runDirectory) {
        Scenario scenario = new Scenario(
                "SC-FEE", "R-002", "fee", Scenario.Mode.SINGLE,
                "import { WithdrawalLedger } from \"../src/ledger.js\";\nconst ledger = new WithdrawalLedger();",
                List.of(new Scenario.Step("ledger.withdraw(0n, 10000n); ledger.applyFee(50n);", List.of(), "record")),
                "ledger.totalWithinLast24Hours(3600000n)", "observation === 10050n", 1, 1L, 60);
        Requirement requirement = Requirement.builder("R-002", "fee").build();
        VerificationContext context = JavaScriptTestContexts.context(root, runDirectory, List.of(scenario));

        List<GeneratedTest> generated = new JavaScriptScenarioTestGenerator()
                .generate(context, requirement, List.of(obligation("SC-FEE")));

        String source = generated.getFirst().sourceCode();
        assertTrue(source.contains("test(\"cw_single_case\""), source);
        assertFalse(source.contains("cw_random_search"), "a single case needs no random search");
        assertFalse(source.contains("const cwInputs = [["),
                "no input vector may be declared when the scenario has no inputs");
    }
}
