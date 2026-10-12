package dev.codewitness.vhdl;

import dev.codewitness.core.spi.LanguageAdapterRegistry;
import dev.codewitness.core.spi.VerificationProviderRegistry;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.vhdl.analysis.VhdlHeuristicAnalyzer;
import dev.codewitness.vhdl.analysis.VhdlSecurityAnalyzer;
import dev.codewitness.vhdl.generate.VhdlGeneratedTestRunner;
import dev.codewitness.vhdl.generate.VhdlScenarioTestGenerator;
import dev.codewitness.vhdl.mutation.VhdlMutationTester;

/**
 * Wires the VHDL adapter and its providers into the verification core.
 *
 * <p>This is the only place that knows which concrete VHDL tools exist. The engine sees
 * {@code LanguageAdapter} and the provider interfaces, so substituting another mutation tester or
 * adding an external linter next to the built-in analyzer is a change to this class and nothing else.
 *
 * <p>There is no coverage provider and no dependency analyzer, and that is deliberate rather than an
 * omission: GHDL's coverage export is not a form this adapter reads, and VHDL has no package manager or
 * advisory ecosystem. The engine reports both as not measured/not analysed, which is the truth.
 */
public final class VhdlAdapterBundle {

    private VhdlAdapterBundle() {
    }

    /** Registers the VHDL language adapter and every VHDL verification provider. */
    public static void register(
            LanguageAdapterRegistry languages, VerificationProviderRegistry providers, boolean includeAnalysis) {

        languages.register(new VhdlLanguageAdapter());

        providers.register(new VhdlBuildProvider());
        providers.register(new VhdlTestProvider());
        providers.register(new VhdlMutationTester());
        providers.register(new VhdlGeneratedTestRunner());
        providers.register(new VhdlScenarioTestGenerator());

        if (includeAnalysis) {
            providers.register((VerificationTools.StaticAnalyzer) new VhdlHeuristicAnalyzer());
            providers.register((VerificationTools.SecurityAnalyzer) new VhdlSecurityAnalyzer());
        }
    }
}
