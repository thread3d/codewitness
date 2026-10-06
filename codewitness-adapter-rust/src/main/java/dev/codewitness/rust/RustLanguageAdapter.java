package dev.codewitness.rust;

import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.Capability;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.LanguageAdapter;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.rust.analysis.RustHeuristicAnalyzer;

import java.util.List;
import java.util.Set;

/**
 * The Rust language adapter.
 *
 * <p>Implements the whole {@link LanguageAdapter} contract for Rust projects using Cargo. The
 * individual tools it uses — compiler, test runner, analyzers, coverage, mutation, dependencies,
 * generated tests — are separate providers registered in {@link RustAdapterBundle}; this class wires
 * detection and resolves the Cargo executable every later phase will use.
 *
 * <p>Nothing is installed and no third-party crate is required: a dependency-free crate can be
 * built, tested, analysed and mutated by the toolchain the project already has, and optional tools
 * such as {@code cargo-llvm-cov} are used only when they are actually present.
 */
public final class RustLanguageAdapter implements LanguageAdapter {

    @Override
    public String id() {
        return "rust";
    }

    @Override
    public boolean supports(ProjectRef project) {
        return RustProjectDetector.looksLikeRustProject(project.root());
    }

    @Override
    public ProjectInfo inspect(ProjectRef project, VerificationContext context) {
        // Detection runs against the isolated workspace, but the reported project information keeps
        // the original project reference so reports describe the user's project, not a copy of it.
        ProjectRef workspaceRef = new ProjectRef(context.projectRoot(), project.name(), project.identifier());
        ProjectInfo detected = RustProjectDetector.inspect(workspaceRef);
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

        // Cargo is resolved and probed here, once, because it needs a process run and every later
        // phase must use the same one. Everything detected so far is carried over unchanged.
        String cargo = Cargo.resolve(context.projectRoot(), System.getenv());
        Cargo.Probe probe = Cargo.probe(context, cargo);
        builder.attribute(Cargo.ATTR_CARGO, cargo)
                .attribute(Cargo.ATTR_HAS_LLVM_COV, String.valueOf(probe.llvmCov()))
                .attribute(Cargo.ATTR_HAS_TARPAULIN, String.valueOf(probe.tarpaulin()))
                .attribute(Cargo.ATTR_HAS_MUTANTS, String.valueOf(probe.mutants()));
        if (probe.version() != null) {
            builder.attribute(Cargo.ATTR_VERSION, probe.version());
        }
        if (!probe.available()) {
            builder.attribute("cargoProbeFailure", probe.failureReason());
        }
        return builder.build();
    }

    @Override
    public BuildResult build(VerificationContext context) {
        return new RustBuildProvider().build(context);
    }

    @Override
    public TestResult runTests(VerificationContext context) {
        return new RustTestProvider().runTests(context);
    }

    @Override
    public List<Finding> analyze(VerificationContext context) {
        return new RustHeuristicAnalyzer().analyze(context);
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.INSPECT, Capability.BUILD, Capability.TEST, Capability.STATIC_ANALYSIS,
                Capability.COVERAGE, Capability.MUTATION, Capability.PROPERTY_TESTING,
                Capability.GENERATED_TESTS, Capability.SECURITY_SCAN, Capability.DEPENDENCY_SCAN);
    }
}
