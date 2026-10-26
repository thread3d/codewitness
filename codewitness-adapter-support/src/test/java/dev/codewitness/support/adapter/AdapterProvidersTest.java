package dev.codewitness.support.adapter;

import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.Capability;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationProviderRegistry;
import dev.codewitness.core.spi.VerificationTools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The capability set is computed from the providers rather than declared beside them. These tests pin the derivation,
 * because what it prevents is an adapter advertising a phase that will never run.
 */
class AdapterProvidersTest {

    private static VerificationTools.Compiler compiler() {
        return new VerificationTools.Compiler() {
            @Override
            public String id() {
                return "compiler";
            }

            @Override
            public boolean supports(ProjectInfo project) {
                return true;
            }

            @Override
            public BuildResult build(VerificationContext context) {
                return BuildResult.notAttempted("not called");
            }
        };
    }

    private static VerificationTools.TestRunner runner() {
        return new VerificationTools.TestRunner() {
            @Override
            public String id() {
                return "runner";
            }

            @Override
            public boolean supports(ProjectInfo project) {
                return true;
            }

            @Override
            public TestResult runTests(VerificationContext context) {
                return TestResult.notExecuted("not called");
            }
        };
    }

    private static VerificationTools.CoverageProvider coverage() {
        return new VerificationTools.CoverageProvider() {
            @Override
            public String id() {
                return "coverage";
            }

            @Override
            public boolean supports(ProjectInfo project) {
                return true;
            }

            @Override
            public CoverageReport measure(VerificationContext context) {
                return CoverageReport.notMeasured("coverage", "not called");
            }
        };
    }

    private static VerificationTools.StaticAnalyzer analyzer() {
        return new VerificationTools.StaticAnalyzer() {
            @Override
            public String id() {
                return "analyzer";
            }

            @Override
            public boolean supports(ProjectInfo project) {
                return true;
            }

            @Override
            public List<Finding> analyze(VerificationContext context) {
                return List.of();
            }
        };
    }

    @Test
    @DisplayName("capabilities are exactly the phases the declared providers can serve")
    void capabilitiesFollowTheProviders() {
        assertEquals(Set.of(Capability.INSPECT), AdapterProviders.builder().build().capabilities(),
                "an adapter that declares nothing can still be asked what language a tree is");

        AdapterProviders tools = AdapterProviders.builder()
                .compiler(compiler())
                .testRunner(runner())
                .coverage(coverage())
                .staticAnalyzer(analyzer())
                .build();

        assertEquals(Set.of(Capability.INSPECT, Capability.BUILD, Capability.TEST, Capability.COVERAGE,
                Capability.STATIC_ANALYSIS), tools.capabilities());
        assertFalse(tools.capabilities().contains(Capability.MUTATION),
                "a capability nobody declared must not be advertised");
    }

    @Test
    @DisplayName("generated tests need both a generator and an executor")
    void generatedTestsNeedBothHalves() {
        // An adapter that can write a test but not run it would advertise a phase whose evidence can never exist.
        VerificationTools.TestGenerator generator = new VerificationTools.TestGenerator() {
            @Override
            public String id() {
                return "generator";
            }

            @Override
            public boolean supports(ProjectInfo project) {
                return true;
            }

            @Override
            public List<dev.codewitness.core.spi.GeneratedTest> generate(VerificationContext context,
                    dev.codewitness.core.requirements.Requirement requirement,
                    List<dev.codewitness.core.requirements.VerificationObligation> obligations) {
                return List.of();
            }
        };

        assertTrue(AdapterProviders.builder().generator(generator).build().capabilities()
                        .contains(Capability.PROPERTY_TESTING),
                "a generator alone may propose scenarios");
        assertFalse(AdapterProviders.builder().generator(generator).build().capabilities()
                        .contains(Capability.GENERATED_TESTS),
                "without an executor, generated tests cannot be run and must not be claimed");
    }

    @Test
    @DisplayName("registering without analysis withdraws the analysis capabilities to match")
    void includeAnalysisWithdrawsAnalysisCapabilities() {
        AdapterProviders tools = AdapterProviders.builder()
                .compiler(compiler())
                .staticAnalyzer(analyzer())
                .build();

        VerificationProviderRegistry withAnalysis = VerificationProviderRegistry.empty();
        tools.registerInto(withAnalysis, true);
        VerificationProviderRegistry withoutAnalysis = VerificationProviderRegistry.empty();
        tools.registerInto(withoutAnalysis, false);

        assertEquals(1, withAnalysis.staticAnalyzers(null).size(), "the analyzer is registered when analysis runs");
        assertTrue(withoutAnalysis.staticAnalyzers(null).isEmpty(), "and not when it does not");
    }

    @Test
    @DisplayName("calling a phase whose provider was never declared fails loudly instead of pretending")
    void aMissingProviderIsAnError() {
        AdapterProviders tools = AdapterProviders.builder().build();

        IllegalStateException build = assertThrows(IllegalStateException.class, () -> tools.build(null));
        assertTrue(build.getMessage().contains("Capability.BUILD"), build.getMessage());
        assertTrue(tools.analyze(null).isEmpty(), "an adapter with no analyzer reports no findings, not a crash");
    }

    @Test
    @DisplayName("the capability set cannot be mutated by a caller")
    void capabilitiesAreImmutable() {
        AdapterProviders tools = AdapterProviders.builder().compiler(compiler()).build();

        assertThrows(UnsupportedOperationException.class, () -> tools.capabilities().add(Capability.MUTATION));
    }
}
