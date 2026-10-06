package dev.codewitness.core.config;

import dev.codewitness.core.result.Budget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The effective configuration is fingerprinted so two runs can be compared for reproducibility. */
class VerificationConfigTest {

    @Test
    @DisplayName("a configuration built from nulls falls back to conservative defaults")
    void nullSectionsFallBackToDefaults() {
        VerificationConfig config = new VerificationConfig(null, null, null, null, null, null, null, null,
                null, null, null, null);

        assertNotNull(config.project());
        assertNotNull(config.llm());
        assertFalse(config.llm().enabled());
        assertFalse(config.sandbox().allowNetwork());
        assertEquals(List.of(), config.scenarios());
    }

    @Test
    @DisplayName("the fingerprint is stable for equal configuration and changes when it changes")
    void fingerprintIsStableAndSensitive() {
        VerificationConfig defaults = VerificationConfig.defaults();

        assertEquals(defaults.fingerprint(), VerificationConfig.defaults().fingerprint());
        assertNotEquals(defaults.fingerprint(),
                defaults.withOutputDirectory(Path.of("/tmp/out")).fingerprint());
    }

    @Test
    @DisplayName("enabling the language model changes the fingerprint")
    void llmEnablingChangesFingerprint() {
        VerificationConfig config = VerificationConfig.defaults();
        VerificationConfig withLlm = new VerificationConfig(config.project(), config.language(), config.limits(),
                config.tests(), config.mutation(), config.propertyTesting(), config.staticAnalysis(),
                config.security(), new VerificationConfig.Llm(true, "openai", "gpt-4o-mini", null, "KEY", 10, 0.2,
                        Duration.ofSeconds(30)),
                config.report(), config.sandbox(), config.scenarios());

        assertNotEquals(config.fingerprint(), withLlm.fingerprint());
    }

    @Test
    @DisplayName("changing limits changes the fingerprint")
    void limitChangeChangesFingerprint() {
        VerificationConfig config = VerificationConfig.defaults();
        VerificationConfig tightened = new VerificationConfig(config.project(), config.language(),
                new VerificationConfig.Limits(Duration.ofMinutes(1), Duration.ofMinutes(1), 2, 10,
                        Duration.ofMinutes(1), true),
                config.tests(), config.mutation(), config.propertyTesting(), config.staticAnalysis(),
                config.security(), config.llm(), config.report(), config.sandbox(), config.scenarios());

        assertNotEquals(config.fingerprint(), tightened.fingerprint());
    }

    @Test
    @DisplayName("adding a scenario changes the fingerprint")
    void scenarioChangeChangesFingerprint() {
        VerificationConfig config = VerificationConfig.defaults();
        Scenario scenario = new Scenario("SC-1", "R-001", "desc", Scenario.Mode.SEQUENCE, null,
                List.of(new Scenario.Step("ledger.run()", List.of(), null)), null, null, 10, 1L, 30);

        assertNotEquals(config.fingerprint(), config.withScenarios(List.of(scenario)).fingerprint());
    }

    @Test
    @DisplayName("the fingerprint does not depend on the report output directory only... it does, by design")
    void outputDirectoryIsPartOfTheFingerprint() {
        // The report section is serialised wholesale into the fingerprint, so a changed output path is
        // visible in the recorded identity of a run. Pinned so the behaviour is intentional.
        VerificationConfig config = VerificationConfig.defaults();

        assertNotEquals(config.fingerprint(),
                config.withOutputDirectory(Path.of("/somewhere/else")).fingerprint());
        assertEquals(config.fingerprint(), config.withOutputDirectory(null).fingerprint());
    }

    @Test
    @DisplayName("toBudget copies the limits into an executable budget")
    void toBudgetReflectsLimits() {
        VerificationConfig config = VerificationConfig.defaults();
        Budget budget = config.toBudget();

        assertEquals(config.limits().maxDuration(), budget.maxDuration());
        assertEquals(config.limits().maxTestGenerationIterations(), budget.maxTestGenerationIterations());
        assertEquals(config.limits().maxGeneratedTests(), budget.maxGeneratedTests());
        assertEquals(config.limits().maxMutationTime(), budget.maxMutationTime());
        assertEquals(config.llm().maxCalls(), budget.maxLlmCalls());
    }

    @Test
    @DisplayName("withBudget writes the budget back into limits and the llm section")
    void withBudgetRoundTrips() {
        VerificationConfig config = VerificationConfig.defaults();
        Budget budget = new Budget(Duration.ofMinutes(3), 4, 12, Duration.ofMinutes(2), 7);

        VerificationConfig updated = config.withBudget(budget);

        assertEquals(Duration.ofMinutes(3), updated.limits().maxDuration());
        assertEquals(4, updated.limits().maxTestGenerationIterations());
        assertEquals(12, updated.limits().maxGeneratedTests());
        assertEquals(7, updated.llm().maxCalls());
        assertEquals(config.limits().timeout(), updated.limits().timeout());
    }

    @Test
    @DisplayName("withScenarios replaces the scenario list wholesale")
    void withScenariosReplacesList() {
        VerificationConfig config = VerificationConfig.defaults();
        Scenario first = new Scenario("SC-1", "R-001", null, Scenario.Mode.SINGLE, null,
                List.of(new Scenario.Step("a()", List.of(), null)), null, null, 1, 1L, 10);
        Scenario second = new Scenario("SC-2", "R-002", null, Scenario.Mode.SINGLE, null,
                List.of(new Scenario.Step("b()", List.of(), null)), null, null, 1, 1L, 10);

        assertEquals(List.of(second), config.withScenarios(List.of(first)).withScenarios(List.of(second)).scenarios());
    }

    @Test
    @DisplayName("asMap exposes every section needed by a report")
    void asMapExposesSections() {
        Map<String, Object> map = VerificationConfig.defaults().asMap();

        assertTrue(map.containsKey("project"));
        assertTrue(map.containsKey("verification"));
        assertTrue(map.containsKey("llm"));
        assertTrue(map.containsKey("sandbox"));
        assertTrue(map.containsKey("scenarios"));
        @SuppressWarnings("unchecked")
        Map<String, Object> verification = (Map<String, Object>) map.get("verification");
        assertEquals(VerificationConfig.Limits.defaults().maxGeneratedTests(),
                verification.get("maxGeneratedTests"));
    }

    @Test
    @DisplayName("asMap renders a scenario list")
    void asMapRendersScenarios() {
        Scenario scenario = new Scenario("SC-1", "R-001", null, Scenario.Mode.CONCURRENT, null,
                List.of(new Scenario.Step("a()", List.of(), null)), null, null, 5, 3L, 10);
        Map<String, Object> map = VerificationConfig.defaults().withScenarios(List.of(scenario)).asMap();

        @SuppressWarnings("unchecked")
        List<Object> scenarios = (List<Object>) map.get("scenarios");
        assertEquals(1, scenarios.size());
        assertEquals(scenario.asMap(), scenarios.get(0));
    }

    @Test
    @DisplayName("the scenario record enforces at least one step and sane numbers")
    void scenarioValidation() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new Scenario("SC-1", "R-001", null,
                Scenario.Mode.SINGLE, null, List.of(), null, null, 1, 1L, 10))
                .getMessage().contains("no steps"));

        Scenario scenario = new Scenario("SC-1", "R-001", null, Scenario.Mode.SINGLE, null,
                List.of(new Scenario.Step("a()", List.of(), null)), null, null, 0, 1L, 0);
        assertEquals(1, scenario.cases());
        assertEquals(60, scenario.timeoutSeconds());
    }

    @Test
    @DisplayName("Scenario.Domain.fromType maps Java types onto searchable domains")
    void domainFromType() {
        assertEquals(Scenario.Domain.DECIMAL, Scenario.Domain.fromType("java.math.BigDecimal"));
        assertEquals(Scenario.Domain.INTEGER, Scenario.Domain.fromType("int"));
        assertEquals(Scenario.Domain.LONG, Scenario.Domain.fromType("long"));
        assertEquals(Scenario.Domain.DOUBLE, Scenario.Domain.fromType("double"));
        assertEquals(Scenario.Domain.BOOLEAN, Scenario.Domain.fromType("boolean"));
        assertEquals(Scenario.Domain.STRING, Scenario.Domain.fromType("java.lang.String"));
        assertEquals(Scenario.Domain.INSTANT_MILLIS, Scenario.Domain.fromType("java.time.Instant"));
        assertEquals(Scenario.Domain.RAW, Scenario.Domain.fromType("com.example.Money"));
    }

    @Test
    @DisplayName("Scenario.Input.boundaryValues uses explicit values or the declared range")
    void inputBoundaryValues() {
        Scenario.Input explicit = new Scenario.Input("n", "int", Scenario.Domain.INTEGER,
                List.of("1", "2"), "0", "10", 0);
        assertEquals(List.of("1", "2"), explicit.boundaryValues());

        Scenario.Input ranged = new Scenario.Input("n", "int", Scenario.Domain.INTEGER,
                List.of(), "0", "10", 0);
        assertEquals(List.of("0", "10"), ranged.boundaryValues());

        Scenario.Input text = new Scenario.Input("s", "java.lang.String", Scenario.Domain.STRING,
                List.of(), null, null, 0);
        assertEquals(List.of(), text.boundaryValues());
    }

    @Test
    @DisplayName("Scenario.Input defaults its domain from its type")
    void inputDomainDefaultsFromType() {
        Scenario.Input input = new Scenario.Input("n", "java.math.BigDecimal", null, List.of(), null, null, 2);

        assertEquals(Scenario.Domain.DECIMAL, input.domain());
    }
}
