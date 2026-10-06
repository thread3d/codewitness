package dev.codewitness.python;

import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.Capability;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.LanguageAdapter;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.python.analysis.PythonHeuristicAnalyzer;

import java.util.List;
import java.util.Set;

/**
 * The Python language adapter.
 *
 * <p>Implements the whole {@link LanguageAdapter} contract for Python 3 projects using any of the
 * common packaging tools, pytest or the standard library test runner. The individual tools it uses -
 * compiler, test runner, analyzers, coverage, mutation, dependencies - are separate providers
 * registered in {@link PythonAdapterBundle}; this class wires detection and resolves the interpreter
 * that every later phase will use.
 *
 * <p>Nothing is installed and no network access is required: a project that is importable from its
 * detected source layout can be syntax-checked, tested, analysed and mutated by the interpreter it
 * already has.
 */
public final class PythonLanguageAdapter implements LanguageAdapter {

    @Override
    public String id() {
        return "python";
    }

    @Override
    public boolean supports(ProjectRef project) {
        return PythonProjectDetector.looksLikePythonProject(project.root());
    }

    @Override
    public ProjectInfo inspect(ProjectRef project, VerificationContext context) {
        // Detection runs against the isolated workspace, but the reported project information keeps
        // the original project reference so reports describe the user's project, not a copy of it.
        ProjectRef workspaceRef = new ProjectRef(context.projectRoot(), project.name(), project.identifier());
        ProjectInfo detected = PythonProjectDetector.inspect(workspaceRef);
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

        // The interpreter is resolved and probed here, once, because it needs a process run and every
        // later phase must use the same one. Everything detected so far is carried over unchanged:
        // rebuilding the ProjectInfo without the test frameworks silently loses them from every report.
        String resolved = PythonInterpreter.resolve(context.projectRoot(), System.getenv());
        PythonInterpreter.Probe probe = PythonInterpreter.probe(context, resolved);
        String interpreter = probe.executable() != null && !probe.executable().isBlank() ? probe.executable()
                : resolved;
        builder.attribute(PythonInterpreter.ATTR_INTERPRETER, interpreter)
                .attribute(PythonInterpreter.ATTR_HAS_PYTEST, String.valueOf(probe.pytest()))
                .attribute(PythonInterpreter.ATTR_HAS_COVERAGE, String.valueOf(probe.coverage()))
                .attribute(PythonInterpreter.ATTR_HAS_MUTMUT, String.valueOf(probe.mutmut()));
        if (probe.version() != null) {
            builder.attribute(PythonInterpreter.ATTR_VERSION, probe.version())
                    .attribute("runtimeVersion", "Python " + probe.version());
        }
        if (!probe.available()) {
            builder.attribute("interpreterProbeFailure", probe.failureReason());
        }
        return builder.build();
    }

    @Override
    public BuildResult build(VerificationContext context) {
        return new PythonBuildProvider().build(context);
    }

    @Override
    public TestResult runTests(VerificationContext context) {
        return new PythonTestProvider().runTests(context);
    }

    @Override
    public List<Finding> analyze(VerificationContext context) {
        return new PythonHeuristicAnalyzer().analyze(context);
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.INSPECT, Capability.BUILD, Capability.TEST, Capability.STATIC_ANALYSIS,
                Capability.COVERAGE, Capability.MUTATION, Capability.PROPERTY_TESTING,
                Capability.GENERATED_TESTS, Capability.SECURITY_SCAN, Capability.DEPENDENCY_SCAN);
    }
}
