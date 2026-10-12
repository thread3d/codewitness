package dev.codewitness.verilog;

import dev.codewitness.core.spi.LanguageAdapterRegistry;
import dev.codewitness.core.spi.VerificationProviderRegistry;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.verilog.analysis.VerilogHeuristicAnalyzer;
import dev.codewitness.verilog.analysis.VerilogSecurityAnalyzer;
import dev.codewitness.verilog.generate.VerilogGeneratedTestRunner;
import dev.codewitness.verilog.generate.VerilogScenarioTestGenerator;
import dev.codewitness.verilog.mutation.VerilogMutationTester;

/**
 * Wires the Verilog adapter and its providers into the verification core.
 *
 * <p>This is the only place that knows which concrete Verilog tools exist. The engine sees
 * {@code LanguageAdapter} and the provider interfaces, so substituting another mutation tester or
 * adding an external linter next to the built-in analyzer is a change to this class and nothing else.
 *
 * <p>There is no coverage provider and no dependency analyzer, and that is deliberate rather than an
 * omission: Icarus Verilog has no coverage export, and Verilog has no package manager or advisory
 * ecosystem. The engine reports both as not measured/not analysed, which is the truth.
 */
public final class VerilogAdapterBundle {

    private VerilogAdapterBundle() {
    }

    /** Registers the Verilog language adapter and every Verilog verification provider. */
    public static void register(
            LanguageAdapterRegistry languages, VerificationProviderRegistry providers, boolean includeAnalysis) {

        languages.register(new VerilogLanguageAdapter());

        providers.register(new VerilogBuildProvider());
        providers.register(new VerilogTestProvider());
        providers.register(new VerilogMutationTester());
        providers.register(new VerilogGeneratedTestRunner());
        providers.register(new VerilogScenarioTestGenerator());

        if (includeAnalysis) {
            providers.register((VerificationTools.StaticAnalyzer) new VerilogHeuristicAnalyzer());
            providers.register((VerificationTools.SecurityAnalyzer) new VerilogSecurityAnalyzer());
        }
    }
}
