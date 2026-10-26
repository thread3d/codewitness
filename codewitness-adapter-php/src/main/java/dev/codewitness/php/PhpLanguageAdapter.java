package dev.codewitness.php;

import dev.codewitness.php.analysis.PhpHeuristicAnalyzer;
import dev.codewitness.php.analysis.PhpSecurityAnalyzer;
import dev.codewitness.php.coverage.PhpCoverageProvider;
import dev.codewitness.php.dependency.PhpDependencyAnalyzer;
import dev.codewitness.php.generate.PhpGeneratedTestRunner;
import dev.codewitness.php.generate.PhpScenarioTestGenerator;
import dev.codewitness.php.mutation.PhpMutationTester;
import dev.codewitness.support.adapter.AbstractLanguageAdapter;
import dev.codewitness.support.adapter.AdapterProviders;
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
 * The PHP language adapter.
 *
 * <p>Implements the whole {@link LanguageAdapter} contract for PHP 8 projects, using the interpreter the
 * machine already has and the Composer the project or the machine provides. The individual tools -
 * compiler, test runner, analyzers, coverage and dependency inventory - are separate classes wired in
 * {@link PhpAdapterBundle}; this class detects the project and resolves the interpreter every later phase
 * will drive.
 *
 * <p>Nothing is installed by the adapter itself and no network access is needed to inspect a project. The
 * one tool that looks like it should be probed here, PHPUnit, is not: it is a Composer dependency that
 * exists only after the build has created {@code vendor/}. The probe records what it saw as a hint, and
 * {@link PhpTestProvider} resolves the runner again when it runs.
 */
public final class PhpLanguageAdapter extends AbstractLanguageAdapter {


    /**
     * This language's tools, declared once, for both the adapter and the bundle that registers it.
     *
     * <p>One list, so the phases this adapter runs and the capabilities it advertises cannot drift apart.
     */
    public static final AdapterProviders PROVIDERS = AdapterProviders.builder()
            .compiler(new PhpBuildProvider())
            .testRunner(new PhpTestProvider())
            .coverage(new PhpCoverageProvider())
            .dependencies(new PhpDependencyAnalyzer())
            .mutation(new PhpMutationTester())
            .generator(new PhpScenarioTestGenerator())
            .executor(new PhpGeneratedTestRunner())
            .staticAnalyzer(new PhpHeuristicAnalyzer())
            .securityAnalyzer(new PhpSecurityAnalyzer())
            .build();

    /** Creates the adapter over its declared provider set. */
    public PhpLanguageAdapter() {
        super(PROVIDERS);
    }

    @Override
    public String id() {
        return "php";
    }

    @Override
    public boolean supports(ProjectRef project) {
        return PhpProjectDetector.looksLikePhpProject(project.root());
    }

    @Override
    public ProjectInfo inspect(ProjectRef project, VerificationContext context) {
        // Detection runs against the isolated workspace, but the reported project information keeps the
        // original project reference so reports describe the user's project, not a copy of it.
        ProjectRef workspaceRef = new ProjectRef(context.projectRoot(), project.name(), project.identifier());
        ProjectInfo detected = PhpProjectDetector.inspect(workspaceRef);
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
        String resolved = PhpInterpreter.resolve(context.projectRoot(), System.getenv());
        PhpInterpreter.Probe probe = PhpInterpreter.probe(context, resolved);
        List<String> composer = PhpInterpreter.composer(context.projectRoot(), resolved, System.getenv("PATH"));

        builder.attribute(PhpInterpreter.ATTR_PHP, resolved);
        builder.attribute(PhpInterpreter.ATTR_COMPOSER,
                composer.isEmpty() ? "unknown" : Path.of(composer.getLast()).getFileName().toString());
        if (probe.version() != null && !probe.version().isBlank()) {
            // This key is the same one the detector fills from composer.json's `require.php`, and the
            // probed version deliberately wins: it is the interpreter the sources were actually parsed
            // with, which is the fact a report about what ran must carry.
            builder.attribute(PhpInterpreter.ATTR_VERSION, probe.version())
                    .attribute("runtimeVersion", "PHP " + probe.version());
        }
        if (probe.composerVersion() != null) {
            builder.attribute(PhpInterpreter.ATTR_COMPOSER_VERSION, probe.composerVersion());
        }
        if (probe.coverageDriver() != null) {
            builder.attribute(PhpInterpreter.ATTR_COVERAGE_DRIVER, probe.coverageDriver());
        }
        if (probe.phpunit() != null) {
            builder.attribute(PhpInterpreter.ATTR_PHPUNIT, probe.phpunit());
        }
        builder.attribute(PhpInterpreter.ATTR_HAS_PHPUNIT, String.valueOf(probe.phpunit() != null));
        if (!probe.available()) {
            builder.attribute(PhpInterpreter.ATTR_PROBE_FAILURE, probe.failureReason());
        }
        return builder.build();
    }
}
