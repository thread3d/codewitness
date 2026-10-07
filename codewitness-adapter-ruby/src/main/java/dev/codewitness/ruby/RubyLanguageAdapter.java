package dev.codewitness.ruby;

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
 * The Ruby language adapter.
 *
 * <p>Implements the whole {@link LanguageAdapter} contract for Ruby projects, using the interpreter the
 * machine already has and the Bundler the project or the machine provides. The individual tools -
 * compiler, test runner, analyzers, coverage and dependency inventory - are separate classes wired in
 * {@link RubyAdapterBundle}; this class detects the project and resolves the interpreter every later phase
 * will drive.
 *
 * <p>Nothing is installed by the adapter itself and no network access is needed to inspect a project. The
 * two tools that look like they should be probed here, RSpec and SimpleCov, are not: they are gems that
 * exist only after the build has run {@code bundle install}. The probe records what it saw as a hint, and
 * {@link RubyTestProvider} resolves the runner again when it runs.
 */
public final class RubyLanguageAdapter implements LanguageAdapter {

    @Override
    public String id() {
        return "ruby";
    }

    @Override
    public boolean supports(ProjectRef project) {
        return RubyProjectDetector.looksLikeRubyProject(project.root());
    }

    @Override
    public ProjectInfo inspect(ProjectRef project, VerificationContext context) {
        // Detection runs against the isolated workspace, but the reported project information keeps the
        // original project reference so reports describe the user's project, not a copy of it.
        ProjectRef workspaceRef = new ProjectRef(context.projectRoot(), project.name(), project.identifier());
        ProjectInfo detected = RubyProjectDetector.inspect(workspaceRef);
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
        // later phase must drive the same one. Everything detected so far is carried over unchanged:
        // rebuilding the ProjectInfo without the test frameworks silently loses them from every report.
        String resolved = RubyInterpreter.resolve(context.projectRoot(), System.getenv());
        RubyInterpreter.Probe probe = RubyInterpreter.probe(context, resolved);
        List<String> bundler = RubyInterpreter.bundler(context.projectRoot(), resolved, System.getenv("PATH"));

        builder.attribute(RubyInterpreter.ATTR_RUBY, resolved);
        builder.attribute(RubyInterpreter.ATTR_BUNDLER,
                bundler.isEmpty() ? "unknown" : Path.of(bundler.getLast()).getFileName().toString());
        if (probe.version() != null && !probe.version().isBlank()) {
            // This key is the same one the detector fills from the Gemfile's `ruby` line or a gemspec's
            // `required_ruby_version`, and the probed version deliberately wins: it is the interpreter the
            // sources were actually parsed with, which is the fact a report about what ran must carry.
            builder.attribute(RubyInterpreter.ATTR_VERSION, probe.version())
                    .attribute("runtimeVersion", "Ruby " + probe.version());
        }
        if (probe.bundlerVersion() != null) {
            builder.attribute(RubyInterpreter.ATTR_BUNDLER_VERSION, probe.bundlerVersion());
        }
        if (probe.coverageDriver() != null) {
            builder.attribute(RubyInterpreter.ATTR_COVERAGE_DRIVER, probe.coverageDriver());
        }
        if (probe.rspec() != null) {
            builder.attribute(RubyInterpreter.ATTR_RSPEC, probe.rspec());
        }
        builder.attribute(RubyInterpreter.ATTR_HAS_RSPEC, String.valueOf(probe.rspec() != null));
        if (!probe.available()) {
            builder.attribute(RubyInterpreter.ATTR_PROBE_FAILURE, probe.failureReason());
        }
        return builder.build();
    }

    @Override
    public BuildResult build(VerificationContext context) {
        return new RubyBuildProvider().build(context);
    }

    @Override
    public TestResult runTests(VerificationContext context) {
        return new RubyTestProvider().runTests(context);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to the adapter's own rule table, exactly as the PHP and Python adapters do. The engine
     * consults this method only when no registered analyzer claims the project, so this is the answer for
     * a caller that wired the adapter without its providers; the registered
     * {@link dev.codewitness.ruby.analysis.RubyHeuristicAnalyzer} is what normally runs.
     */
    @Override
    public List<Finding> analyze(VerificationContext context) {
        return new dev.codewitness.ruby.analysis.RubyHeuristicAnalyzer().analyze(context);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Only the phases this adapter can actually carry out are claimed, so the engine leaves the rest
     * unclaimed instead of scheduling them against nothing. Every capability listed here is one
     * {@link RubyAdapterBundle} registers a provider for when it is asked for analysis as well.
     */
    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.INSPECT, Capability.BUILD, Capability.TEST, Capability.STATIC_ANALYSIS,
                Capability.SECURITY_SCAN, Capability.COVERAGE, Capability.DEPENDENCY_SCAN,
                Capability.MUTATION, Capability.GENERATED_TESTS, Capability.PROPERTY_TESTING);
    }
}
