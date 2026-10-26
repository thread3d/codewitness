package dev.codewitness.swift;

import dev.codewitness.support.adapter.AbstractLanguageAdapter;
import dev.codewitness.support.adapter.AdapterProviders;
import dev.codewitness.swift.analysis.SwiftHeuristicAnalyzer;
import dev.codewitness.swift.analysis.SwiftSecurityAnalyzer;
import dev.codewitness.swift.coverage.SwiftCoverageProvider;
import dev.codewitness.swift.dependency.SwiftDependencyAnalyzer;
import dev.codewitness.swift.generate.SwiftGeneratedTestRunner;
import dev.codewitness.swift.generate.SwiftScenarioTestGenerator;
import dev.codewitness.swift.mutation.SwiftMutationTester;
import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.Capability;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.VerificationContext;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * The Swift language adapter.
 *
 * <p>Implements the whole {@link LanguageAdapter} contract for Swift packages, driving the Swift
 * Package Manager the machine already has. The individual tools - compiler, test runner, analyzers,
 * coverage and dependency inventory - are separate classes wired in {@link SwiftAdapterBundle}; this
 * class detects the package and resolves the toolchain every later phase will drive.
 *
 * <p>Nothing is installed by the adapter itself and no network access is needed to inspect a package.
 * A package with no external dependencies needs no network to build or test either, because XCTest
 * ships with the toolchain.
 */
public final class SwiftLanguageAdapter extends AbstractLanguageAdapter {


    /**
     * This language's tools, declared once, for both the adapter and the bundle that registers it.
     *
     * <p>One list, so the phases this adapter runs and the capabilities it advertises cannot drift apart.
     */
    public static final AdapterProviders PROVIDERS = AdapterProviders.builder()
            .compiler(new SwiftBuildProvider())
            .testRunner(new SwiftTestProvider())
            .coverage(new SwiftCoverageProvider())
            .dependencies(new SwiftDependencyAnalyzer())
            .mutation(new SwiftMutationTester())
            .generator(new SwiftScenarioTestGenerator())
            .executor(new SwiftGeneratedTestRunner())
            .staticAnalyzer(new SwiftHeuristicAnalyzer())
            .securityAnalyzer(new SwiftSecurityAnalyzer())
            .build();

    /** Creates the adapter over its declared provider set. */
    public SwiftLanguageAdapter() {
        super(PROVIDERS);
    }

    @Override
    public String id() {
        return "swift";
    }

    @Override
    public boolean supports(ProjectRef project) {
        return SwiftProjectDetector.looksLikeSwiftProject(project.root());
    }

    @Override
    public ProjectInfo inspect(ProjectRef project, VerificationContext context) {
        // Detection runs against the isolated workspace, but the reported project information keeps the
        // original project reference so reports describe the user's project, not a copy of it.
        ProjectRef workspaceRef = new ProjectRef(context.projectRoot(), project.name(), project.identifier());
        ProjectInfo detected = SwiftProjectDetector.inspect(workspaceRef);
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

        // The toolchain is resolved and probed here, once, because it needs a process run and every later
        // phase must drive the same one. Everything detected so far is carried over unchanged: rebuilding
        // the ProjectInfo without the test frameworks silently loses them from every report.
        String resolved = SwiftToolchain.resolve(context.projectRoot(), System.getenv());
        SwiftToolchain.Probe probe = SwiftToolchain.probe(context, resolved);
        if (resolved != null) {
            builder.attribute(SwiftToolchain.ATTR_SWIFT, resolved);
        }
        if (probe.version() != null && !probe.version().isBlank()) {
            builder.attribute(SwiftToolchain.ATTR_SWIFT_VERSION, probe.version())
                    .attribute("runtimeVersion", "Swift " + probe.version());
        }
        if (probe.toolchain() != null && !probe.toolchain().isBlank()) {
            builder.attribute(SwiftToolchain.ATTR_TOOLCHAIN, probe.toolchain());
        }
        if (!probe.available()) {
            builder.attribute(SwiftToolchain.ATTR_PROBE_FAILURE, probe.failureReason());
        }
        return builder.build();
    }
}
