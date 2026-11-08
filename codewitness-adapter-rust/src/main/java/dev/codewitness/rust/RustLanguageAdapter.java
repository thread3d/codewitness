package dev.codewitness.rust;

import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.Capability;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.rust.analysis.RustHeuristicAnalyzer;
import dev.codewitness.rust.analysis.RustSecurityAnalyzer;
import dev.codewitness.rust.coverage.RustCoverageProvider;
import dev.codewitness.rust.dependency.RustDependencyAnalyzer;
import dev.codewitness.rust.generate.RustGeneratedTestRunner;
import dev.codewitness.rust.generate.RustScenarioTestGenerator;
import dev.codewitness.rust.mutation.RustMutationTester;
import dev.codewitness.support.adapter.AbstractLanguageAdapter;
import dev.codewitness.support.adapter.AdapterProviders;

import java.util.ArrayList;
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
public final class RustLanguageAdapter extends AbstractLanguageAdapter {

    /**
     * Declares the Cargo toolchain once, for both the adapter and the bundle that registers it.
     *
     * <p>One list, so the phases this adapter runs and the capabilities it advertises cannot drift apart.
     *
     * @return the Rust providers
     */
    public static AdapterProviders rustProviders() {
        return AdapterProviders.builder()
                .compiler(new RustBuildProvider())
                .testRunner(new RustTestProvider())
                .staticAnalyzer(new RustHeuristicAnalyzer())
                .securityAnalyzer(new RustSecurityAnalyzer())
                .coverage(new RustCoverageProvider())
                .mutation(new RustMutationTester())
                .dependencies(new RustDependencyAnalyzer())
                .generator(new RustScenarioTestGenerator())
                .executor(new RustGeneratedTestRunner())
                .build();
    }

    /** Creates the adapter over its declared provider set. */
    public RustLanguageAdapter() {
        super(rustProviders());
    }

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
        ProjectInfo info = builder.build();
        noteManifestLocation(context, info);
        return info;
    }

    /**
     * Records which crate the run builds from, and — when the tree holds several — which were not built.
     *
     * <p>A crate whose manifest sits below the checkout root is ordinary, and the tool now builds it
     * instead of refusing. What it must not do is let that read as "the whole tree was verified": with
     * more than one manifest only one crate is compiled and tested, so the others are named as a
     * limitation rather than silently dropped.
     */
    static void noteManifestLocation(VerificationContext context, ProjectInfo info) {
        String manifest = info.attributes().get(RustProjectDetector.ATTR_MANIFEST);
        if (manifest == null || manifest.isBlank()) {
            return;
        }
        String detectedCrate = info.attributes().get(RustProjectDetector.ATTR_CRATE_NAME);
        String crateName = detectedCrate == null || detectedCrate.isBlank() ? "the crate" : detectedCrate;
        if (!"Cargo.toml".equals(manifest)) {
            context.state().note("The Cargo manifest is not at the project root: " + crateName
                    + " is built from " + manifest + ", and every Cargo command this run executes names "
                    + "that manifest explicitly (--manifest-path) rather than running in the project root.");
        }
        String manifests = info.attributes().get(RustProjectDetector.ATTR_MANIFESTS);
        if (manifests != null && !manifests.isBlank()) {
            List<String> others = new ArrayList<>();
            for (String found : manifests.split(",")) {
                if (!found.isBlank() && !found.equals(manifest)) {
                    others.add(found);
                }
            }
            if (!others.isEmpty()) {
                context.state().note("The tree holds " + (others.size() + 1) + " Cargo manifests and none is "
                        + "at the project root; only " + manifest + " (" + crateName + ") was built, tested and "
                        + "mutated, so the evidence in this report is about that crate alone and not about "
                        + String.join(", ", others) + ".");
            }
        }
    }

}
