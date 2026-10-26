package dev.codewitness.javascript;

import dev.codewitness.javascript.analysis.JavaScriptHeuristicAnalyzer;
import dev.codewitness.javascript.analysis.JavaScriptSecurityAnalyzer;
import dev.codewitness.javascript.coverage.JavaScriptCoverageProvider;
import dev.codewitness.javascript.dependency.JavaScriptDependencyAnalyzer;
import dev.codewitness.javascript.generate.JavaScriptGeneratedTestRunner;
import dev.codewitness.javascript.generate.JavaScriptScenarioTestGenerator;
import dev.codewitness.javascript.mutation.JavaScriptMutationTester;
import dev.codewitness.support.adapter.AbstractLanguageAdapter;
import dev.codewitness.support.adapter.AdapterProviders;
import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.Capability;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.javascript.analysis.JavaScriptHeuristicAnalyzer;

import java.util.List;
import java.util.Set;

/**
 * The JavaScript language adapter.
 *
 * <p>Implements the whole {@link LanguageAdapter} contract for Node/JavaScript projects. The individual
 * tools it uses — build, test runner, analyzers, coverage, mutation, dependencies, generated tests — are
 * separate providers registered in {@link JavaScriptAdapterBundle}; this class wires detection and
 * resolves the Node executable every later phase will use.
 *
 * <p>Nothing is installed and no third-party package is required: JavaScript is what Node executes
 * natively, so a dependency-free project can be built, tested, analysed and mutated by the runtime the
 * environment already has. Unlike the TypeScript adapter there is no compiler and no type check, and
 * the build reports that as a fact rather than pretending otherwise.
 *
 * <p>This adapter refuses a project that has a {@code tsconfig.json} or TypeScript sources, leaving it
 * to {@code codewitness-adapter-typescript}.
 */
public final class JavaScriptLanguageAdapter extends AbstractLanguageAdapter {


    /**
     * This language's tools, declared once, for both the adapter and the bundle that registers it.
     *
     * <p>One list, so the phases this adapter runs and the capabilities it advertises cannot drift apart.
     */
    public static final AdapterProviders PROVIDERS = AdapterProviders.builder()
            .compiler(new JavaScriptBuildProvider())
            .testRunner(new JavaScriptTestProvider())
            .coverage(new JavaScriptCoverageProvider())
            .mutation(new JavaScriptMutationTester())
            .dependencies(new JavaScriptDependencyAnalyzer())
            .executor(new JavaScriptGeneratedTestRunner())
            .generator(new JavaScriptScenarioTestGenerator())
            .staticAnalyzer(new JavaScriptHeuristicAnalyzer())
            .securityAnalyzer(new JavaScriptSecurityAnalyzer())
            .build();

    /** Creates the adapter over its declared provider set. */
    public JavaScriptLanguageAdapter() {
        super(PROVIDERS);
    }

    @Override
    public String id() {
        return "javascript";
    }

    @Override
    public boolean supports(ProjectRef project) {
        return JavaScriptProjectDetector.looksLikeJavaScriptProject(project.root());
    }

    @Override
    public ProjectInfo inspect(ProjectRef project, VerificationContext context) {
        // Detection runs against the isolated workspace, but the reported project information keeps the
        // original project reference so reports describe the user's project, not a copy of it.
        ProjectRef workspaceRef = new ProjectRef(context.projectRoot(), project.name(), project.identifier());
        ProjectInfo detected = JavaScriptProjectDetector.inspect(workspaceRef);
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

        // Node is resolved and probed here, once, because the probe needs a process run and every later
        // phase must use the same executable. Everything detected so far is carried over.
        String node = NodeRuntime.resolve(context.projectRoot(), System.getenv());
        NodeRuntime.Probe probe = NodeRuntime.probe(context, node);
        builder.attribute(NodeRuntime.ATTR_NODE, node);
        if (probe.version() != null) {
            builder.attribute(NodeRuntime.ATTR_VERSION, probe.version())
                    .attribute("runtimeVersion", "Node " + probe.version());
        }
        if (!probe.available()) {
            builder.attribute("nodeProbeFailure", probe.failureReason());
        } else {
            builder.attribute(NodeRuntime.ATTR_TEST_RUNNER, String.valueOf(probe.testRunner()));
            if (!probe.testRunner()) {
                builder.attribute("runtimeWarning",
                        "this Node runtime (" + probe.version() + ") cannot write the JUnit report the test "
                                + "phase reads; Node " + NodeRuntime.MINIMUM_TEST_RUNNER_VERSION + " or later is "
                                + "required");
            }
        }
        return builder.build();
    }
}
