package dev.codewitness.swift;

import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.Capability;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.LanguageAdapter;
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
public final class SwiftLanguageAdapter implements LanguageAdapter {

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

    @Override
    public BuildResult build(VerificationContext context) {
        return new SwiftBuildProvider().build(context);
    }

    @Override
    public TestResult runTests(VerificationContext context) {
        return new SwiftTestProvider().runTests(context);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to the adapter's own rule table, exactly as the Ruby and PHP adapters do. The engine
     * consults this method only when no registered analyzer claims the project, so this is the answer for
     * a caller that wired the adapter without its providers; the registered
     * {@link dev.codewitness.swift.analysis.SwiftHeuristicAnalyzer} is what normally runs.
     */
    @Override
    public List<Finding> analyze(VerificationContext context) {
        return new dev.codewitness.swift.analysis.SwiftHeuristicAnalyzer().analyze(context);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Only the phases this adapter can actually carry out are claimed, so the engine leaves the rest
     * unclaimed instead of scheduling them against nothing. Every capability listed here is one
     * {@link SwiftAdapterBundle} registers a provider for when it is asked for analysis as well.
     */
    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.INSPECT, Capability.BUILD, Capability.TEST, Capability.STATIC_ANALYSIS,
                Capability.SECURITY_SCAN, Capability.COVERAGE, Capability.DEPENDENCY_SCAN,
                Capability.MUTATION, Capability.GENERATED_TESTS, Capability.PROPERTY_TESTING);
    }
}
