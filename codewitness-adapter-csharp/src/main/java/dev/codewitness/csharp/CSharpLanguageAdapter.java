package dev.codewitness.csharp;

import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.Capability;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.LanguageAdapter;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.csharp.analysis.CSharpHeuristicAnalyzer;

import java.util.List;
import java.util.Set;

/**
 * The C# and .NET language adapter.
 *
 * <p>Implements the whole {@link LanguageAdapter} contract for C# projects using the .NET SDK's
 * {@code dotnet} driver. The individual tools it uses - compiler, test runner, analyzers, coverage,
 * mutation, dependencies, generated tests - are separate providers registered in
 * {@link CSharpAdapterBundle}; this class wires detection and resolves the {@code dotnet} executable
 * every later phase will use.
 *
 * <p>Nothing is installed beyond the SDK the project already builds with, and no third-party package is
 * required by the adapter itself: the fixture projects need xUnit because they are xUnit projects, not
 * because CodeWitness does.
 */
public final class CSharpLanguageAdapter implements LanguageAdapter {

    @Override
    public String id() {
        return "csharp";
    }

    @Override
    public boolean supports(ProjectRef project) {
        return CSharpProjectDetector.looksLikeCSharpProject(project.root());
    }

    @Override
    public ProjectInfo inspect(ProjectRef project, VerificationContext context) {
        // Detection runs against the isolated workspace, but the reported project information keeps the
        // original project reference so reports describe the user's project, not a copy of it.
        ProjectRef workspaceRef = new ProjectRef(context.projectRoot(), project.name(), project.identifier());
        ProjectInfo detected = CSharpProjectDetector.inspect(workspaceRef);
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

        // The SDK is resolved and probed here, once, because it needs a process run and every later phase
        // must use the same toolchain. Everything detected so far is carried over unchanged.
        String dotnet = DotnetTool.resolve(context.projectRoot(), System.getenv());
        DotnetTool.Probe probe = DotnetTool.probe(context, dotnet);
        builder.attribute(DotnetTool.ATTR_DOTNET, dotnet);
        if (probe.version() != null) {
            // The runtime version is recorded so a report can be reproduced against the same toolchain.
            builder.attribute(DotnetTool.ATTR_VERSION, probe.version());
            builder.attribute("runtimeVersion", ".NET " + probe.version());
        }
        if (!probe.available()) {
            builder.attribute("dotnetProbeFailure", probe.failureReason());
        }
        return builder.build();
    }

    @Override
    public BuildResult build(VerificationContext context) {
        return new CSharpBuildProvider().build(context);
    }

    @Override
    public TestResult runTests(VerificationContext context) {
        return new CSharpTestProvider().runTests(context);
    }

    @Override
    public List<Finding> analyze(VerificationContext context) {
        return new CSharpHeuristicAnalyzer().analyze(context);
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.INSPECT, Capability.BUILD, Capability.TEST, Capability.STATIC_ANALYSIS,
                Capability.COVERAGE, Capability.MUTATION, Capability.PROPERTY_TESTING,
                Capability.GENERATED_TESTS, Capability.SECURITY_SCAN, Capability.DEPENDENCY_SCAN);
    }
}
