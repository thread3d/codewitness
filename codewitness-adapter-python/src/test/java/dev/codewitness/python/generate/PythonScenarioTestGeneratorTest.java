package dev.codewitness.python.generate;

import dev.codewitness.core.config.Scenario;
import dev.codewitness.core.requirements.ObligationKind;
import dev.codewitness.core.requirements.ObligationOrigin;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.python.PythonTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rendering of declared scenarios as executable standard-library tests. */
class PythonScenarioTestGeneratorTest {

    @Test
    @DisplayName("a declared scenario becomes a unittest module with a structured counterexample protocol")
    void rendersScenario(@TempDir Path root, @TempDir Path runDirectory) {
        Scenario scenario = new Scenario(
                "SC-ROLLING", "R-001", "two withdrawals across midnight", Scenario.Mode.SEQUENCE,
                "from ledger import WithdrawalLedger\nledger = WithdrawalLedger()",
                List.of(new Scenario.Step("ledger.withdraw(${t1}, ${a1})", List.of(
                        new Scenario.Input("t1", "int", Scenario.Domain.INTEGER, List.of("85800000"),
                                "0", "172800000", 2),
                        new Scenario.Input("a1", "decimal.Decimal", Scenario.Domain.DECIMAL, List.of("700"),
                                "100", "900", 2)), "first step")),
                "ledger.total_within_last_24_hours(${t1})",
                "observation <= decimal.Decimal(\"1000\")",
                25, 42L, 60);
        Requirement requirement = Requirement.builder("R-001", "rolling limit")
                .title("Rolling withdrawal limit").build();
        VerificationObligation obligation = new VerificationObligation("OB-001", "R-001", "execute scenario",
                ObligationKind.PROPERTY, ObligationOrigin.USER_DECLARED, "SC-ROLLING",
                Map.of("scenario", "SC-ROLLING"), false);
        VerificationContext context = PythonTestContexts.context(root, runDirectory, List.of(scenario));

        List<GeneratedTest> generated = new PythonScenarioTestGenerator()
                .generate(context, requirement, List.of(obligation));

        assertEquals(1, generated.size());
        GeneratedTest test = generated.getFirst();
        assertEquals("test_cw_sc_rolling_ob_001", test.className());
        assertEquals(List.of("OB-001"), test.obligationIds());
        String source = test.sourceCode();
        assertTrue(source.contains("class Cw_sc_rolling_ob_001(unittest.TestCase):"), source);
        assertTrue(source.contains("import unittest"), source);
        assertTrue(source.contains("CODEWITNESS_COUNTEREXAMPLE"), source);
        assertTrue(source.contains("ledger.withdraw(t1, a1)"), "the placeholder must be substituted: " + source);
        assertTrue(source.contains("decimal.Decimal(\"700\")"), source);
        assertTrue(source.contains("def test_case0000(self):"), source);
        assertTrue(source.contains("def test_random_search(self):"), source);
        assertTrue(source.contains("cw_decimal(rnd, \"100\", \"900\", 2)"), source);
        assertFalse(source.contains("${"), "no placeholder may survive into generated code:\n" + source);
    }

    @Test
    @DisplayName("a concurrent scenario gets its own module and a barrier-based worker")
    void rendersConcurrentScenario(@TempDir Path root, @TempDir Path runDirectory) {
        Scenario scenario = new Scenario(
                "SC-CONC", "R-004", "concurrent withdrawals", Scenario.Mode.CONCURRENT,
                "ledger = object()",
                List.of(new Scenario.Step("ledger.withdraw(${amount})", List.of(
                        new Scenario.Input("amount", "int", Scenario.Domain.INTEGER, List.of("200"),
                                "100", "300", 0)), "step")),
                null, null, 5, 7L, 60);
        Requirement requirement = Requirement.builder("R-004", "concurrency").build();
        VerificationObligation obligation = new VerificationObligation("OB-009", "R-004", "execute",
                ObligationKind.CONCURRENCY, ObligationOrigin.USER_DECLARED, "SC-CONC",
                Map.of("scenario", "SC-CONC"), false);
        VerificationContext context = PythonTestContexts.context(root, runDirectory, List.of(scenario));

        List<GeneratedTest> generated = new PythonScenarioTestGenerator()
                .generate(context, requirement, List.of(obligation));

        assertEquals(1, generated.size());
        String source = generated.getFirst().sourceCode();
        assertEquals("test_cw_sc_conc_ob_009_concurrency", generated.getFirst().className());
        assertTrue(source.contains("class Cw_sc_conc_ob_009(unittest.TestCase):"), source);
        assertTrue(source.contains("threading.Barrier(8)"), source);
        assertTrue(source.contains("threading.Thread"), source);
    }

    @Test
    @DisplayName("inputs without boundary values still produce exactly one executed case")
    void rendersOneDefaultCaseWhenNoInputHasBoundaryValues(@TempDir Path root, @TempDir Path runDirectory) {
        Scenario scenario = new Scenario(
                "SC-DEFAULTS", "R-001", "inputs without boundary values", Scenario.Mode.SINGLE,
                "ledger = object()",
                List.of(new Scenario.Step("ledger.record(${label}, ${note})", List.of(
                        new Scenario.Input("label", "str", Scenario.Domain.STRING, List.of("explicit"),
                                null, null, 0),
                        new Scenario.Input("note", "str", Scenario.Domain.STRING, List.of(),
                                null, null, 0)), "record both")),
                null, null, 1, 1L, 60);
        Requirement requirement = Requirement.builder("R-001", "defaults").build();
        VerificationObligation obligation = new VerificationObligation("OB-007", "R-001", "execute scenario",
                ObligationKind.PROPERTY, ObligationOrigin.USER_DECLARED, "SC-DEFAULTS",
                Map.of("scenario", "SC-DEFAULTS"), false);
        VerificationContext context = PythonTestContexts.context(root, runDirectory, List.of(scenario));

        List<GeneratedTest> generated = new PythonScenarioTestGenerator()
                .generate(context, requirement, List.of(obligation));

        assertEquals(1, generated.size());
        String source = generated.getFirst().sourceCode();
        assertEquals(1, countOccurrences(source, "    def test_"),
                "an empty class reports nothing, so exactly one case must be emitted:\n" + source);
        assertTrue(source.contains("def test_case0000(self):"), source);
        assertTrue(source.contains("label = \"explicit\""),
                "the first declared value is used when there is one:\n" + source);
        assertTrue(source.contains("note = \"\""),
                "the type default is used when there is none:\n" + source);
        assertTrue(source.contains("\"label\": label"), source);
        assertTrue(source.contains("\"note\": note"), source);
        assertFalse(source.contains("def test_single_case(self)"), source);
        assertFalse(source.contains("def test_random_search(self):"),
                "a SINGLE scenario with one case has nothing to search over");
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
