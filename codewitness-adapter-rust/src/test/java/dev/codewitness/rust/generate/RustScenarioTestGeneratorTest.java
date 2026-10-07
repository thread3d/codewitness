package dev.codewitness.rust.generate;

import dev.codewitness.core.config.Scenario;
import dev.codewitness.core.requirements.ObligationKind;
import dev.codewitness.core.requirements.ObligationOrigin;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.rust.RustTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rendering of declared scenarios as executable Cargo integration tests. */
class RustScenarioTestGeneratorTest {

    private static Scenario sequenceScenario() {
        return new Scenario(
                "SC-ROLLING", "R-001", "two withdrawals across midnight", Scenario.Mode.SEQUENCE,
                "let mut ledger = withdrawal_ledger::WithdrawalLedger::new();",
                List.of(new Scenario.Step("ledger.withdraw(${t1}, ${a1})", List.of(
                        new Scenario.Input("t1", "i64", Scenario.Domain.INTEGER, List.of("85800000"),
                                "0", "172800000", 0),
                        new Scenario.Input("a1", "i64", Scenario.Domain.INTEGER, List.of("70000"),
                                "10000", "90000", 0)), "first step")),
                "ledger.total_within_last_24_hours(${t1})",
                "observation <= 100000",
                25, 42L, 60);
    }

    private static VerificationObligation obligation() {
        return new VerificationObligation("OB-001", "R-001", "execute scenario",
                ObligationKind.PROPERTY, ObligationOrigin.USER_DECLARED, "SC-ROLLING",
                Map.of("scenario", "SC-ROLLING"), false);
    }

    @Test
    @DisplayName("a sequence scenario becomes a Rust integration test with the counterexample protocol")
    void rendersSequenceScenario(@TempDir Path root, @TempDir Path runDirectory) {
        Scenario scenario = sequenceScenario();
        Requirement requirement = Requirement.builder("R-001", "rolling limit")
                .title("Rolling withdrawal limit").build();
        VerificationContext context = RustTestContexts.context(root, runDirectory, List.of(scenario));

        List<GeneratedTest> generated = new RustScenarioTestGenerator()
                .generate(context, requirement, List.of(obligation()));

        assertEquals(1, generated.size());
        GeneratedTest test = generated.getFirst();
        assertEquals("cw_sc_rolling_ob_001", test.className());
        assertEquals("cw_random_search", test.testMethod());
        assertEquals(List.of("OB-001"), test.obligationIds());

        String source = test.sourceCode();
        assertTrue(source.contains("#![allow("), source);
        assertTrue(source.contains("const CW_REQUIREMENT: &str = \"R-001\";"), source);
        assertTrue(source.contains("fn cw_case0000()"), source);
        assertTrue(source.contains("fn cw_random_search()"), source);
        assertTrue(source.contains("let t1: i64 = 85800000;"), source);
        assertTrue(source.contains("cw_int(&mut rnd, 10000, 90000)"), source);
        assertTrue(source.contains("ledger.withdraw(t1, a1);"), "the placeholder must be substituted: " + source);
        assertTrue(source.contains("cw_fail(cw_case, cw_seed_value, &cw_inputs,"), source);
        assertTrue(source.contains("CODEWITNESS_COUNTEREXAMPLE"), source);
        assertTrue(source.contains("std::panic::catch_unwind"), source);
        assertFalse(source.contains("${"), "no placeholder may survive into generated code:\n" + source);
        // Every function that calls cw_fail must hold the same Option<u64> seed type.
        assertTrue(source.contains("let cw_seed_value: Option<u64> = Some(cw_seed(42));"), source);
        assertFalse(source.contains("cw_execute(cw_case, Some(cw_seed_value)"), source);
    }

    @Test
    @DisplayName("a concurrent scenario uses scoped threads and a barrier")
    void rendersConcurrentScenario(@TempDir Path root, @TempDir Path runDirectory) {
        Scenario scenario = new Scenario(
                "SC-CONC", "R-004", "concurrent withdrawals", Scenario.Mode.CONCURRENT,
                "let ledger = std::sync::Mutex::new(1i64);",
                List.of(new Scenario.Step("let _ = ledger.lock().unwrap();", List.of(
                        new Scenario.Input("amount", "i64", Scenario.Domain.INTEGER, List.of("200"),
                                "100", "300", 0)), "step")),
                null, null, 5, 7L, 60);
        Requirement requirement = Requirement.builder("R-004", "concurrency").build();
        VerificationObligation obligation = new VerificationObligation("OB-009", "R-004", "execute",
                ObligationKind.CONCURRENCY, ObligationOrigin.USER_DECLARED, "SC-CONC",
                Map.of("scenario", "SC-CONC"), false);
        VerificationContext context = RustTestContexts.context(root, runDirectory, List.of(scenario));

        List<GeneratedTest> generated = new RustScenarioTestGenerator()
                .generate(context, requirement, List.of(obligation));

        assertEquals(1, generated.size());
        GeneratedTest test = generated.getFirst();
        assertEquals("cw_sc_conc_ob_009_concurrency", test.className());
        assertEquals("cw_concurrent_search", test.testMethod());
        String source = test.sourceCode();
        assertTrue(source.contains("std::sync::Barrier::new(8)"), source);
        assertTrue(source.contains("std::thread::scope"), source);
    }

    @Test
    @DisplayName("a scenario with no inputs still produces one executable case")
    void rendersSingleCaseWithoutInputs(@TempDir Path root, @TempDir Path runDirectory) {
        Scenario scenario = new Scenario(
                "SC-FEE", "R-002", "fee", Scenario.Mode.SINGLE,
                "let mut ledger = withdrawal_ledger::WithdrawalLedger::new();",
                List.of(new Scenario.Step("ledger.withdraw(0, 10000);", List.of(), "record")),
                "ledger.total_within_last_24_hours(3600000)", "observation == 10050", 1, 1L, 60);
        Requirement requirement = Requirement.builder("R-002", "fee").build();
        VerificationObligation obligation = new VerificationObligation("OB-002", "R-002", "execute",
                ObligationKind.PROPERTY, ObligationOrigin.USER_DECLARED, "SC-FEE",
                Map.of("scenario", "SC-FEE"), false);
        VerificationContext context = RustTestContexts.context(root, runDirectory, List.of(scenario));

        List<GeneratedTest> generated = new RustScenarioTestGenerator()
                .generate(context, requirement, List.of(obligation));

        String source = generated.getFirst().sourceCode();
        assertTrue(source.contains("fn cw_single_case()"), source);
        assertTrue(source.contains("let cw_inputs: Vec<(&str, String)> = Vec::new();"), source);
        assertFalse(source.contains("fn cw_random_search()"), "a single case needs no random search");
    }

    @Test
    @DisplayName("inputs without boundary values still produce exactly one executed case")
    void rendersOneDefaultCaseWhenNoInputHasBoundaryValues(@TempDir Path root, @TempDir Path runDirectory) {
        Scenario scenario = new Scenario(
                "SC-DEFAULTS", "R-001", "inputs without boundary values", Scenario.Mode.SINGLE,
                "let ledger = 0i64;",
                List.of(new Scenario.Step("ledger.record(${label}, ${note});", List.of(
                        new Scenario.Input("label", "String", Scenario.Domain.STRING, List.of("explicit"),
                                null, null, 0),
                        new Scenario.Input("note", "String", Scenario.Domain.STRING, List.of(),
                                null, null, 0)), "record both")),
                null, null, 1, 1L, 60);
        Requirement requirement = Requirement.builder("R-001", "defaults").build();
        VerificationObligation obligation = new VerificationObligation("OB-007", "R-001", "execute",
                ObligationKind.PROPERTY, ObligationOrigin.USER_DECLARED, "SC-DEFAULTS",
                Map.of("scenario", "SC-DEFAULTS"), false);
        VerificationContext context = RustTestContexts.context(root, runDirectory, List.of(scenario));

        List<GeneratedTest> generated = new RustScenarioTestGenerator()
                .generate(context, requirement, List.of(obligation));

        assertEquals(1, generated.size());
        String source = generated.getFirst().sourceCode();
        assertEquals(1, countOccurrences(source, "#[test]"),
                "an empty test file reports nothing, so exactly one function must be emitted:\n" + source);
        assertTrue(source.contains("fn cw_case0000()"), source);
        assertTrue(source.contains("let label: String = \"explicit\";"),
                "the first declared value is used when there is one:\n" + source);
        assertTrue(source.contains("let note: String = \"\";"),
                "the type default is used when there is none:\n" + source);
        assertTrue(source.contains("(\"label\", label.to_string())"), source);
        assertTrue(source.contains("(\"note\", note.to_string())"), source);
        assertFalse(source.contains("fn cw_single_case()"), source);
        assertFalse(source.contains("fn cw_random_search()"), source);
        assertFalse(source.contains("${"), source);
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
