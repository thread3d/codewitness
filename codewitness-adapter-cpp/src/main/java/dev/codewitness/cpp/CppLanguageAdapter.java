package dev.codewitness.cpp;

import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.Capability;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.LanguageAdapter;
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
public final class CppLanguageAdapter implements LanguageAdapter {

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

    @Override
    public BuildResult build(VerificationContext context) {
        return new CppBuildProvider().build(context);
    }

    @Override
    public TestResult runTests(VerificationContext context) {
        return new CppTestProvider().runTests(context);
    }

    @Override
    public List<Finding> analyze(VerificationContext context) {
        return new CppHeuristicAnalyzer().analyze(context);
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.INSPECT, Capability.BUILD, Capability.TEST, Capability.STATIC_ANALYSIS,
                Capability.COVERAGE, Capability.MUTATION, Capability.PROPERTY_TESTING,
                Capability.GENERATED_TESTS, Capability.SECURITY_SCAN, Capability.DEPENDENCY_SCAN);
    }
}
