package dev.codewitness.vhdl;

import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.Capability;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.LanguageAdapter;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.vhdl.analysis.VhdlHeuristicAnalyzer;

import java.util.List;
import java.util.Set;

/**
 * The VHDL language adapter.
 *
 * <p>Implements the whole {@link LanguageAdapter} contract for VHDL using GHDL. The individual tools it
 * uses - analyser, testbench runner, analysers, mutation, generated tests - are separate providers
 * registered in {@link VhdlAdapterBundle}; this class wires detection and resolves the {@code ghdl}
 * executable every later phase will use.
 *
 * <p>Nothing is installed: a VHDL tree with no dependencies needs only GHDL, which is what a machine
 * that can simulate a design at all already carries. The adapter declares no coverage and no dependency
 * capability, because GHDL exports no coverage data in the form this engine reads and VHDL has no
 * package manager or advisory ecosystem: the report then says coverage was not measured and
 * dependencies were not analysed, which is the honest statement rather than a zero the adapter cannot
 * support.
 */
public final class VhdlLanguageAdapter implements LanguageAdapter {

    @Override
    public String id() {
        return "vhdl";
    }

    @Override
    public boolean supports(ProjectRef project) {
        return VhdlProjectDetector.looksLikeVhdlProject(project.root());
    }

    @Override
    public ProjectInfo inspect(ProjectRef project, VerificationContext context) {
        // Detection runs against the isolated workspace, but the reported project information keeps the
        // original project reference so reports describe the user's project, not a copy of it.
        ProjectRef workspaceRef = new ProjectRef(context.projectRoot(), project.name(), project.identifier());
        ProjectInfo detected = VhdlProjectDetector.inspect(workspaceRef);
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
        // later phase must use the same executable. Everything detected so far is carried over unchanged.
        String ghdl = VhdlTool.resolve("ghdl", context.projectRoot(), System.getenv());
        VhdlTool.Probe probe = VhdlTool.probe(context, ghdl);
        builder.attribute(VhdlTool.ATTR_GHDL, ghdl);
        if (probe.version() != null) {
            builder.attribute(VhdlTool.ATTR_VERSION, probe.version())
                    .attribute("runtimeVersion", "GHDL " + probe.version());
        }
        if (!probe.available()) {
            builder.attribute("vhdlProbeFailure", probe.failureReason());
        }
        return builder.build();
    }

    @Override
    public BuildResult build(VerificationContext context) {
        return new VhdlBuildProvider().build(context);
    }

    @Override
    public TestResult runTests(VerificationContext context) {
        return new VhdlTestProvider().runTests(context);
    }

    @Override
    public List<Finding> analyze(VerificationContext context) {
        return new VhdlHeuristicAnalyzer().analyze(context);
    }

    @Override
    public Set<Capability> capabilities() {
        // No COVERAGE: GHDL's coverage export is not a form this adapter can measure, so claiming the
        // capability would make the engine schedule a phase this adapter cannot serve. No DEPENDENCY_SCAN:
        // VHDL has no package manager and no advisory ecosystem, so there is nothing to inventory.
        return Set.of(Capability.INSPECT, Capability.BUILD, Capability.TEST, Capability.STATIC_ANALYSIS,
                Capability.MUTATION, Capability.PROPERTY_TESTING, Capability.GENERATED_TESTS,
                Capability.SECURITY_SCAN);
    }
}
