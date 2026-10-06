package dev.codewitness.go;

import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.Capability;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.LanguageAdapter;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.go.analysis.GoHeuristicAnalyzer;

import java.util.List;
import java.util.Set;

/**
 * The Go language adapter.
 *
 * <p>Implements the whole {@link LanguageAdapter} contract for Go modules using the {@code go} tool.
 * The individual tools it uses — compiler, test runner, analyzers, coverage, mutation, dependencies,
 * generated tests — are separate providers registered in {@link GoAdapterBundle}; this class wires
 * detection and resolves the {@code go} executable every later phase will use.
 *
 * <p>Nothing is installed and no third-party module is required: a dependency-free module can be
 * built, tested, analysed and mutated by the toolchain the project already has, and coverage comes
 * from {@code go test -coverprofile} rather than from an external tool.
 */
public final class GoLanguageAdapter implements LanguageAdapter {

    @Override
    public String id() {
        return "go";
    }

    @Override
    public boolean supports(ProjectRef project) {
        return GoProjectDetector.looksLikeGoProject(project.root());
    }

    @Override
    public ProjectInfo inspect(ProjectRef project, VerificationContext context) {
        // Detection runs against the isolated workspace, but the reported project information keeps
        // the original project reference so reports describe the user's project, not a copy of it.
        ProjectRef workspaceRef = new ProjectRef(context.projectRoot(), project.name(), project.identifier());
        ProjectInfo detected = GoProjectDetector.inspect(workspaceRef);
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

        // Go is resolved and probed here, once, because it needs a process run and every later phase
        // must use the same toolchain. Everything detected so far is carried over unchanged.
        String go = GoTool.resolve(context.projectRoot(), System.getenv());
        GoTool.Probe probe = GoTool.probe(context, go);
        builder.attribute(GoTool.ATTR_GO, go);
        if (probe.version() != null) {
            builder.attribute(GoTool.ATTR_VERSION, probe.version());
        }
        if (!probe.available()) {
            builder.attribute("goProbeFailure", probe.failureReason());
        }
        return builder.build();
    }

    @Override
    public BuildResult build(VerificationContext context) {
        return new GoBuildProvider().build(context);
    }

    @Override
    public TestResult runTests(VerificationContext context) {
        return new GoTestProvider().runTests(context);
    }

    @Override
    public List<Finding> analyze(VerificationContext context) {
        return new GoHeuristicAnalyzer().analyze(context);
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.INSPECT, Capability.BUILD, Capability.TEST, Capability.STATIC_ANALYSIS,
                Capability.COVERAGE, Capability.MUTATION, Capability.PROPERTY_TESTING,
                Capability.GENERATED_TESTS, Capability.SECURITY_SCAN, Capability.DEPENDENCY_SCAN);
    }
}
