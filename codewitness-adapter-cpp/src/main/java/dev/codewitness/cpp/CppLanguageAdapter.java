package dev.codewitness.cpp;

import dev.codewitness.cpp.analysis.CppHeuristicAnalyzer;
import dev.codewitness.cpp.analysis.CppSecurityAnalyzer;
import dev.codewitness.cpp.coverage.CppCoverageProvider;
import dev.codewitness.cpp.dependency.CppDependencyAnalyzer;
import dev.codewitness.cpp.generate.CppGeneratedTestRunner;
import dev.codewitness.cpp.generate.CppScenarioTestGenerator;
import dev.codewitness.cpp.mutation.CppMutationTester;
import dev.codewitness.support.adapter.AbstractLanguageAdapter;
import dev.codewitness.support.adapter.AdapterProviders;
import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.Capability;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.cpp.analysis.CppHeuristicAnalyzer;

import java.util.List;
import java.util.Set;

/**
 * The C and C++ language adapter.
 *
 * <p>Implements the whole {@link LanguageAdapter} contract for C and C++ projects built by CMake. The
 * individual tools it uses - configure and compiler, CTest, the rule table, gcov coverage, the package
 * inventory, bounded source mutation and generated test executables - are separate providers registered in
 * {@link CppAdapterBundle}; this class wires detection and resolves the {@code cmake} and {@code ctest}
 * executables every later phase will use.
 *
 * <p>Nothing is installed beyond the toolchain the project already builds with, and no third-party package
 * is required by the adapter itself: a fixture project needs CMake because it is a CMake project, not
 * because CodeWitness does. A tree whose C or C++ sources are built by a hand-written {@code Makefile} or
 * by Meson is recognised by {@code CppLanguageDetector} and reported as present and not verified, which is
 * the honest answer for a build this adapter does not drive.
 */
public final class CppLanguageAdapter extends AbstractLanguageAdapter {


    /**
     * This language's tools, declared once, for both the adapter and the bundle that registers it.
     *
     * <p>One list, so the phases this adapter runs and the capabilities it advertises cannot drift apart.
     */
    public static final AdapterProviders PROVIDERS = AdapterProviders.builder()
            .compiler(new CppBuildProvider())
            .testRunner(new CppTestProvider())
            .coverage(new CppCoverageProvider())
            .mutation(new CppMutationTester())
            .dependencies(new CppDependencyAnalyzer())
            .executor(new CppGeneratedTestRunner())
            .generator(new CppScenarioTestGenerator())
            .staticAnalyzer(new CppHeuristicAnalyzer())
            .securityAnalyzer(new CppSecurityAnalyzer())
            .build();

    /** Creates the adapter over its declared provider set. */
    public CppLanguageAdapter() {
        super(PROVIDERS);
    }

    @Override
    public String id() {
        return "cpp";
    }

    @Override
    public boolean supports(ProjectRef project) {
        return CppProjectDetector.looksLikeCppProject(project.root());
    }

    @Override
    public ProjectInfo inspect(ProjectRef project, VerificationContext context) {
        // Detection runs against the isolated workspace, but the reported project information keeps the
        // original project reference so reports describe the user's project, not a copy of it.
        ProjectRef workspaceRef = new ProjectRef(context.projectRoot(), project.name(), project.identifier());
        ProjectInfo detected = CppProjectDetector.inspect(workspaceRef);
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
        // phase must use the same one. Everything detected so far is carried over unchanged.
        String cmake = CppBuildTools.resolve(context.projectRoot(), System.getenv());
        builder.attribute(CppBuildTools.ATTR_CMAKE, cmake);
        CppBuildTools.Probe probe = CppBuildTools.probe(context.runner(), cmake, context.projectRoot());
        if (probe.version() != null) {
            // The toolchain version is recorded so a report can be reproduced against the same CMake.
            builder.attribute(CppBuildTools.ATTR_VERSION, probe.version());
            builder.attribute("runtimeVersion", "CMake " + probe.version());
        }
        if (!probe.available()) {
            builder.attribute(CppBuildTools.ATTR_PROBE_FAILURE, probe.failureReason());
        }
        String ctest = CppBuildTools.resolveExecutable("ctest");
        builder.attribute(CppBuildTools.ATTR_CTEST, ctest == null ? "ctest" : ctest);
        String ninja = CppBuildTools.resolveExecutable("ninja");
        builder.attribute(CppBuildTools.ATTR_GENERATOR, ninja == null ? "CMake default" : "Ninja");
        return builder.build();
    }
}
