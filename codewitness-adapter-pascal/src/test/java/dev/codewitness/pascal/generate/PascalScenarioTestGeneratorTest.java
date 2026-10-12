package dev.codewitness.pascal.generate;

import dev.codewitness.core.config.Scenario;
import dev.codewitness.core.requirements.ObligationKind;
import dev.codewitness.core.requirements.ObligationOrigin;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.spi.GeneratedTest;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.pascal.PascalModule;
import dev.codewitness.pascal.PascalProjectDetector;
import dev.codewitness.pascal.PascalTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A declared scenario becomes a compilable Pascal program that checks its invariant. */
class PascalScenarioTestGeneratorTest {

    private static final String LEDGER = """
            unit ledger;
            interface
            uses SysUtils;
            function Withdraw(balance, amount: Integer; out accepted: Boolean): Integer;
            implementation
            function Withdraw(balance, amount: Integer; out accepted: Boolean): Integer;
            begin
              accepted := amount <= balance;
              if accepted then
                Result := balance - amount
              else
                Result := balance;
            end;
            end.
            """;

    @TempDir
    Path root;

    @TempDir
    Path run;

    private static Scenario scenario() {
        return new Scenario(
                "SC-DEBIT",
                "R-001",
                "An accepted withdrawal reduces the balance by exactly the amount",
                Scenario.Mode.SINGLE,
                "",
                List.of(new Scenario.Step(
                        "observation := ledger.Withdraw(balance, amount, accepted);",
                        List.of(
                                new Scenario.Input("balance", "integer", Scenario.Domain.INTEGER,
                                        List.of("100000"), null, null, 0),
                                new Scenario.Input("amount", "integer", Scenario.Domain.INTEGER,
                                        List.of("1", "99999"), null, null, 0)),
                        "one withdrawal")),
                "observation",
                "(accepted = False) or (observation = balance - amount)",
                1,
                1,
                60);
    }

    private static Requirement requirement() {
        return Requirement.builder("R-001", "An accepted withdrawal reduces the balance by exactly the amount")
                .build();
    }

    private static VerificationObligation obligation() {
        return VerificationObligation.of("OB-001", "R-001", "check the debit", ObligationKind.BOUNDARY_VALUE,
                ObligationOrigin.USER_DECLARED).withParameters(Map.of("scenario", "SC-DEBIT"));
    }

    private VerificationContext context(List<Scenario> scenarios) {
        return PascalTestContexts.context(root, run, scenarios);
    }

    @Test
    @DisplayName("the scenario becomes a program that links the unit under test and checks its invariant")
    void rendersAProgram() throws IOException {
        PascalTestContexts.write(root, "ledger.pas", LEDGER);
        List<GeneratedTest> tests = new PascalScenarioTestGenerator()
                .generate(context(List.of(scenario())), requirement(), List.of(obligation()));

        assertEquals(1, tests.size());
        GeneratedTest test = tests.getFirst();
        assertEquals("cw_sc_sc_debit_ob_001", test.className());
        assertTrue(test.checksInvariant());
        assertEquals(List.of("R-001"), test.requirementIds());
        assertEquals(Long.valueOf(1), test.seed());

        String source = test.sourceCode();
        assertTrue(source.contains("program cw_sc_sc_debit_ob_001;"), source);
        assertTrue(source.contains("ledger"), source);
        assertTrue(source.contains("cw_case := 0;"), source);
        assertTrue(source.contains("observation := ledger.Withdraw(balance, amount, accepted);"), source);
        assertTrue(source.contains("CODEWITNESS_COUNTEREXAMPLE"), source);
        assertTrue(source.contains("Halt(1);"), source);
        assertFalse(source.contains("${"), "every declared input must have been substituted: " + source);
        assertTrue(source.contains("balance: Integer;"), source);
    }

    @Test
    @DisplayName("a project with no unit to exercise produces no generated test")
    void noUnitsNoTests() {
        List<GeneratedTest> tests = new PascalScenarioTestGenerator()
                .generate(context(List.of(scenario())), requirement(), List.of(obligation()));

        assertTrue(tests.isEmpty());
    }

    @Test
    @DisplayName("an obligation that names no scenario is skipped rather than guessed at")
    void skipsObligationsWithoutAScenario() throws IOException {
        PascalTestContexts.write(root, "ledger.pas", LEDGER);
        VerificationObligation withoutScenario = VerificationObligation.of(
                "OB-002", "R-001", "no scenario", ObligationKind.INVARIANT, ObligationOrigin.USER_DECLARED);

        assertTrue(new PascalScenarioTestGenerator()
                .generate(context(List.of(scenario())), requirement(), List.of(withoutScenario)).isEmpty());
    }

    @Test
    @DisplayName("the boundary enumeration is a bounded cartesian product of the declared values")
    void boundaryCasesAreBounded() {
        List<Scenario.Input> inputs = new ArrayList<>();
        inputs.add(new Scenario.Input("a", "integer", Scenario.Domain.INTEGER,
                List.of("1", "2", "3", "4"), "0", "5", 0));
        inputs.add(new Scenario.Input("b", "integer", Scenario.Domain.INTEGER,
                List.of("6", "7", "8", "9"), "5", "10", 0));

        List<Map<String, String>> cases = PascalScenarioTestGenerator.boundaryCases(inputs);

        assertFalse(cases.isEmpty());
        assertTrue(cases.size() <= 12, "the enumeration must stay bounded: " + cases.size());
        assertTrue(cases.stream().allMatch(values -> values.containsKey("a") && values.containsKey("b")));
    }

    @Test
    @DisplayName("a type name maps to the Pascal type the generated program declares")
    void mapsTypes() {
        assertEquals("Integer", PascalScenarioTestGenerator.pascalType("int"));
        assertEquals("Int64", PascalScenarioTestGenerator.pascalType("int64"));
        assertEquals("Double", PascalScenarioTestGenerator.pascalType("double"));
        assertEquals("Boolean", PascalScenarioTestGenerator.pascalType("boolean"));
        assertEquals("String", PascalScenarioTestGenerator.pascalType("string"));
        assertEquals("Currency", PascalScenarioTestGenerator.pascalType("currency"));
    }

    @Test
    @DisplayName("a scenario identifier becomes a legal, stable program name")
    void sanitizesNames() {
        assertEquals("cw_sc_sc_debit_ob_001",
                PascalScenarioTestGenerator.classNameFor("SC-DEBIT", "OB-001"));
        assertEquals("case", PascalScenarioTestGenerator.sanitize("---"));
    }

    @Test
    @DisplayName("the unit under test is derived from the fragment that names it")
    void derivesTheUnitUnderTest() throws IOException {
        PascalTestContexts.write(root, "ledger.pas", LEDGER);
        PascalTestContexts.write(root, "unrelated.pas", "unit unrelated; interface implementation end.");

        List<PascalModule> units = PascalProjectDetector.units(root);
        List<String> uses = PascalScenarioTestGenerator.usesFor(scenario(), units);

        assertTrue(uses.contains("ledger"), uses.toString());
        assertFalse(uses.contains("unrelated"), uses.toString());
    }
}
