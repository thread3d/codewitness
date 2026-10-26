package dev.codewitness.cli;

import dev.codewitness.core.model.Capability;
import dev.codewitness.core.spi.LanguageAdapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What each adapter says it can do, checked against what its providers can actually do.
 *
 * <p>The capability set is now derived from the provider list, so this is a record of the resulting contract rather
 * than a second hand-maintained list: if a provider is added or dropped, the failure here is the reminder that the
 * change is a change in what the tool claims. The three adapters with deliberately fewer capabilities are called out
 * by name, because their omissions are decisions and not oversights.
 */
class AdapterCapabilityTest {

    private static final Set<Capability> EVERYTHING_ELSE = Set.of(
            Capability.INSPECT, Capability.BUILD, Capability.TEST, Capability.STATIC_ANALYSIS,
            Capability.MUTATION, Capability.PROPERTY_TESTING, Capability.GENERATED_TESTS,
            Capability.SECURITY_SCAN);

    private static List<LanguageAdapter> adapters() {
        return CliSupport.wiring().languages().all();
    }

    @Test
    @DisplayName("every bundled adapter is registered and claims at least detection")
    void everyAdapterCanInspect() {
        List<LanguageAdapter> adapters = adapters();

        assertTrue(adapters.size() >= 16, "the bundle registers every adapter: " + adapters.size());
        for (LanguageAdapter adapter : adapters) {
            assertTrue(adapter.capabilities().contains(Capability.INSPECT), adapter.id());
        }
    }

    @Test
    @DisplayName("an HDL design claims no coverage and no dependency inventory")
    void hdlAdaptersClaimNoCoverage() {
        // Verilog and VHDL have neither: Icarus and GHDL export no coverage data in a form this tool reads, and an
        // HDL design has no package manager or advisory ecosystem. Claiming either capability would schedule a phase
        // that runs and finds nothing, which reads identically to a phase that found nothing wrong.
        for (String id : List.of("verilog", "vhdl", "pascal")) {
            LanguageAdapter adapter = adapters().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
            assertEquals(EVERYTHING_ELSE, adapter.capabilities(),
                    id + " should claim exactly the phases it can serve, which excludes coverage and dependencies");
            assertFalse(adapter.capabilities().contains(Capability.COVERAGE), id);
            assertFalse(adapter.capabilities().contains(Capability.DEPENDENCY_SCAN), id);
        }
    }

    @Test
    @DisplayName("the adapters whose phases are pure delegation share one base; the two that differ say so")
    void onlyTheSelectionAdaptersBypassTheBaseClass() {
        // AbstractLanguageAdapter implements build/runTests/analyze as calls into the declared providers. Java and
        // Kotlin cannot: their phases select a build system for the project first (Maven or Gradle) and construct the
        // provider around it. That is real logic, and forcing it through the base class would mean either hiding it
        // behind a provider that resolves per project or dropping it. Naming the two here means a future adapter that
        // skips the base class without a reason is visible.
        List<String> notUsingTheBase = adapters().stream()
                .filter(adapter -> !(adapter instanceof dev.codewitness.support.adapter.AbstractLanguageAdapter))
                .map(LanguageAdapter::id)
                .sorted()
                .toList();

        assertEquals(List.of("java", "kotlin"), notUsingTheBase,
                "an adapter that does not extend AbstractLanguageAdapter must have a reason, and there are two");
    }

    @Test
    @DisplayName("the adapters with no dependency inventory are exactly the four that have none")
    void onlyTheExpectedAdaptersLackDependencies() {
        // SQL joins the HDL adapters and Pascal: it loads a schema rather than resolving packages, so there is no
        // inventory to scan for advisories. This test exists so that a language gaining or losing a dependency
        // analyzer is a deliberate change to what the tool claims, rather than something noticed later in a report.
        List<String> withoutDependencies = adapters().stream()
                .filter(adapter -> !adapter.capabilities().contains(Capability.DEPENDENCY_SCAN))
                .map(LanguageAdapter::id)
                .sorted()
                .toList();

        assertEquals(List.of("pascal", "sql", "verilog", "vhdl"), withoutDependencies,
                "a language that gains or loses a dependency analyzer is a change to what the tool claims");
    }
}
