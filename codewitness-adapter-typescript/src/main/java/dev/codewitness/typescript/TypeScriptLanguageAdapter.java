package dev.codewitness.typescript;

import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.Capability;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.LanguageAdapter;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.typescript.analysis.TypeScriptHeuristicAnalyzer;

import java.util.List;
import java.util.Set;

/**
 * The TypeScript language adapter.
 *
 * <p>Implements the whole {@link LanguageAdapter} contract for Node/TypeScript projects. The individual
 * tools it uses — build, test runner, analyzers, coverage, mutation, dependencies, generated tests — are
 * separate providers registered in {@link TypeScriptAdapterBundle}; this class wires detection and
 * resolves the Node and TypeScript executables every later phase will use.
 *
 * <p>Nothing is installed and no third-party package is required: a dependency-free TypeScript project
 * can be built, tested, analysed and mutated by the Node runtime the environment already has. A project
 * that pins its own TypeScript compiler is type checked with it; a project that does not is checked with
 * the runtime's own parser, and the difference is reported rather than hidden.
 */
public final class TypeScriptLanguageAdapter implements LanguageAdapter {

    @Override
    public String id() {
        return "typescript";
    }

    @Override
    public boolean supports(ProjectRef project) {
        return TypeScriptProjectDetector.looksLikeTypeScriptProject(project.root());
    }

    @Override
    public ProjectInfo inspect(ProjectRef project, VerificationContext context) {
        // Detection runs against the isolated workspace, but the reported project information keeps the
        // original project reference so reports describe the user's project, not a copy of it.
        ProjectRef workspaceRef = new ProjectRef(context.projectRoot(), project.name(), project.identifier());
        ProjectInfo detected = TypeScriptProjectDetector.inspect(workspaceRef);
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

        // Node and tsc are resolved and probed here, once, because the probe needs a process run and
        // every later phase must use the same executables. Everything detected so far is carried over.
        String node = NodeRuntime.resolve(context.projectRoot(), System.getenv());
        NodeRuntime.Probe probe = NodeRuntime.probe(context, node);
        builder.attribute(NodeRuntime.ATTR_NODE, node);
        if (probe.version() != null) {
            builder.attribute(NodeRuntime.ATTR_VERSION, probe.version())
                    .attribute("runtimeVersion", "Node " + probe.version());
        }
        if (!probe.available()) {
            builder.attribute("nodeProbeFailure", probe.failureReason());
        } else if (!probe.typeStripping()) {
            builder.attribute("runtimeWarning", NodeRuntime.typeStrippingWarning(probe.version()));
        }
        String tsc = NodeRuntime.resolveTsc(context.projectRoot(), System.getenv());
        if (tsc != null) {
            builder.attribute(NodeRuntime.ATTR_TSC, tsc);
            builder.attribute(NodeRuntime.ATTR_TSC_SOURCE,
                    tsc.startsWith(context.projectRoot().toString()) ? "project" : "PATH");
        } else {
            builder.attribute(NodeRuntime.ATTR_TSC_SOURCE, "none");
        }
        return builder.build();
    }

    @Override
    public BuildResult build(VerificationContext context) {
        return new TypeScriptBuildProvider().build(context);
    }

    @Override
    public TestResult runTests(VerificationContext context) {
        return new TypeScriptTestProvider().runTests(context);
    }

    @Override
    public List<Finding> analyze(VerificationContext context) {
        return new TypeScriptHeuristicAnalyzer().analyze(context);
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.INSPECT, Capability.BUILD, Capability.TEST, Capability.STATIC_ANALYSIS,
                Capability.COVERAGE, Capability.MUTATION, Capability.PROPERTY_TESTING,
                Capability.GENERATED_TESTS, Capability.SECURITY_SCAN, Capability.DEPENDENCY_SCAN);
    }
}
