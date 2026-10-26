package dev.codewitness.verilog;

import dev.codewitness.support.adapter.AbstractLanguageAdapter;
import dev.codewitness.support.adapter.AdapterProviders;
import dev.codewitness.verilog.analysis.VerilogHeuristicAnalyzer;
import dev.codewitness.verilog.analysis.VerilogSecurityAnalyzer;
import dev.codewitness.verilog.generate.VerilogGeneratedTestRunner;
import dev.codewitness.verilog.generate.VerilogScenarioTestGenerator;
import dev.codewitness.verilog.mutation.VerilogMutationTester;
import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.Capability;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.verilog.analysis.VerilogHeuristicAnalyzer;

import java.util.List;
import java.util.Set;

/**
 * The Verilog and SystemVerilog language adapter.
 *
 * <p>Implements the whole {@link LanguageAdapter} contract for Verilog using Icarus Verilog. The
 * individual tools it uses - compiler, testbench runner, analyzers, mutation, generated tests - are
 * separate providers registered in {@link VerilogAdapterBundle}; this class wires detection and
 * resolves the {@code iverilog}/{@code vvp} pair every later phase will use.
 *
 * <p>Nothing is installed: a Verilog tree with no dependencies needs only the two executables, which
 * are what a machine that can simulate a design at all already carries. The adapter declares no
 * coverage and no dependency capability, because Icarus Verilog exports neither and Verilog has no
 * package manager: the report then says coverage was not measured and dependencies were not analysed,
 * which is the honest statement rather than a zero the adapter cannot support.
 */
public final class VerilogLanguageAdapter extends AbstractLanguageAdapter {


    /**
     * This language's tools, declared once, for both the adapter and the bundle that registers it.
     *
     * <p>One list, so the phases this adapter runs and the capabilities it advertises cannot drift apart.
     */
    public static final AdapterProviders PROVIDERS = AdapterProviders.builder()
            .compiler(new VerilogBuildProvider())
            .testRunner(new VerilogTestProvider())
            .mutation(new VerilogMutationTester())
            .executor(new VerilogGeneratedTestRunner())
            .generator(new VerilogScenarioTestGenerator())
            .staticAnalyzer(new VerilogHeuristicAnalyzer())
            .securityAnalyzer(new VerilogSecurityAnalyzer())
            .build();

    /** Creates the adapter over its declared provider set. */
    public VerilogLanguageAdapter() {
        super(PROVIDERS);
    }

    @Override
    public String id() {
        return "verilog";
    }

    @Override
    public boolean supports(ProjectRef project) {
        return VerilogProjectDetector.looksLikeVerilogProject(project.root());
    }

    @Override
    public ProjectInfo inspect(ProjectRef project, VerificationContext context) {
        // Detection runs against the isolated workspace, but the reported project information keeps the
        // original project reference so reports describe the user's project, not a copy of it.
        ProjectRef workspaceRef = new ProjectRef(context.projectRoot(), project.name(), project.identifier());
        ProjectInfo detected = VerilogProjectDetector.inspect(workspaceRef);
        ProjectInfo.Builder builder = ProjectInfo.builder(project)
                .language(detected.language())
                .buildSystem(detected.buildSystem())
                .buildRoot(detected.buildRoot())
                .sourceExtensions(detected.sourceExtensions())
                .attributes(detected.attributes())
                .modules(detected.modules());
        detected.buildWrapper().ifPresent(builder::buildWrapper);
        detected.detectedDescriptors().forEach(builder::addDetectedDescriptor);
        detected.testFrameworks().forEach(builder::addTestFramework);

        // The toolchain is resolved and probed here, once, because it needs a process run and every
        // later phase must use the same pair. Everything detected so far is carried over unchanged.
        String iverilog = VerilogTool.resolve("iverilog", context.projectRoot(), System.getenv());
        String vvp = VerilogTool.resolve("vvp", context.projectRoot(), System.getenv());
        VerilogTool.Probe probe = VerilogTool.probe(context, iverilog);
        builder.attribute(VerilogTool.ATTR_IVERILOG, iverilog);
        builder.attribute(VerilogTool.ATTR_VVP, vvp);
        if (probe.version() != null) {
            builder.attribute(VerilogTool.ATTR_VERSION, probe.version())
                    .attribute("runtimeVersion", "Icarus Verilog " + probe.version());
        }
        if (!probe.available()) {
            builder.attribute("verilogProbeFailure", probe.failureReason());
        }
        return builder.build();
    }
}
