package dev.codewitness.php;

import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.Capability;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.LanguageAdapterRegistry;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationProviderRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the adapter's wiring and for the claims and commands it builds around it.
 *
 * <p>Every one of them runs with no PHP, Composer or PHPUnit installed: a tool that is not there is
 * pointed at a path that does not exist, and the test asserts the honest answer rather than depending on
 * what the machine happens to have. The one thing the tests must not do is silently pass on a machine
 * that does have PHP, so the resolution and command tests state the inputs they resolve against.
 */
class PhpProviderCommandsTest {

    @Test
    @DisplayName("the adapter identifies itself, claims PHP trees and declares only the phases it can carry out")
    void adapterBasics(@TempDir Path project) throws IOException {
        PhpLanguageAdapter adapter = new PhpLanguageAdapter();

        assertEquals("php", adapter.id());
        assertFalse(adapter.supports(ProjectRef.of(project)), "an empty directory is not claimed");

        PhpTestContexts.write(project, "src/Ledger.php", "<?php\n");
        assertTrue(adapter.supports(ProjectRef.of(project)));

        assertEquals(Set.of(Capability.INSPECT, Capability.BUILD, Capability.TEST,
                        Capability.STATIC_ANALYSIS, Capability.SECURITY_SCAN, Capability.COVERAGE,
                        Capability.DEPENDENCY_SCAN, Capability.MUTATION, Capability.GENERATED_TESTS,
                        Capability.PROPERTY_TESTING),
                adapter.capabilities(),
                "every capability must be one the bundle registers a provider for: a phase nothing "
                        + "implements must not be claimed, or the engine schedules it against nothing");
    }

    @Test
    @DisplayName("a Java tree is not PHP evidence, whatever build files it carries")
    void buildDescriptorsAreNotEvidence(@TempDir Path project) throws IOException {
        PhpTestContexts.write(project, "src/main/java/example/Ledger.java",
                "package example;\n\nclass Ledger {}\n");
        PhpTestContexts.write(project, "pom.xml", "<project/>\n");

        assertFalse(new PhpLanguageAdapter().supports(ProjectRef.of(project)),
                "a Java project is the Java adapter's to verify");
    }

    @Test
    @DisplayName("the bundle registers the adapter and its providers, and the analysis ones only when asked")
    void bundleRegistration() {
        LanguageAdapterRegistry languages = new LanguageAdapterRegistry();
        VerificationProviderRegistry providers = VerificationProviderRegistry.empty();
        PhpAdapterBundle.register(languages, providers, true);

        assertEquals(List.of("php"), languages.ids());
        assertEquals(List.of("compiler:php-build", "test-runner:php-tests", "static-analyzer:php-heuristics",
                        "coverage:php-coverage", "mutation:php-text-mutation", "security:php-security",
                        "dependency:php-dependencies", "test-generator:php-scenario-generator",
                        "generated-test-executor:php-generated-tests"),
                providers.providerIds());

        VerificationProviderRegistry withoutAnalysis = VerificationProviderRegistry.empty();
        PhpAdapterBundle.register(new LanguageAdapterRegistry(), withoutAnalysis, false);
        assertEquals(List.of("compiler:php-build", "test-runner:php-tests", "coverage:php-coverage",
                        "mutation:php-text-mutation", "dependency:php-dependencies",
                        "test-generator:php-scenario-generator",
                        "generated-test-executor:php-generated-tests"),
                withoutAnalysis.providerIds(),
                "the flag withholds the two analysis providers and nothing else: a build, a test runner, "
                        + "coverage, mutation, generated tests and the dependency inventory are measurement, "
                        + "not advice");
    }

    @Test
    @DisplayName("the providers claim PHP projects and no Java project")
    void providersClaimByLanguage(@TempDir Path root) throws IOException {
        PhpTestContexts.write(root, "composer.json", """
                {"name": "codewitness/php-withdrawal-ledger", "require-dev": {"phpunit/phpunit": "^11.5"}}
                """);
        PhpTestContexts.write(root, "src/Ledger.php", "<?php\n");
        PhpTestContexts.write(root, "tests/LedgerTest.php", "<?php\n");
        ProjectInfo php = PhpTestContexts.projectInfo(root);
        assertEquals(LanguageId.PHP, php.language());
        assertEquals(BuildSystemId.COMPOSER, php.buildSystem());

        VerificationProviderRegistry providers = VerificationProviderRegistry.empty();
        PhpAdapterBundle.register(new LanguageAdapterRegistry(), providers, true);

        assertEquals(1, providers.compilers(php).size());
        assertEquals("php-build", providers.compilers(php).getFirst().id());
        assertEquals(1, providers.testRunners(php).size());
        assertEquals("php-tests", providers.testRunners(php).getFirst().id());

        ProjectInfo java = ProjectInfo.builder(php.project())
                .language(LanguageId.JAVA)
                .buildSystem(BuildSystemId.MAVEN)
                .build();
        assertTrue(providers.compilers(java).isEmpty(),
                "a Java project's build is the Java adapter's to run, not this one's");
        assertTrue(providers.testRunners(java).isEmpty(),
                "and its tests too, or two runners would both execute the same suite");
    }

    @Test
    @DisplayName("PHPUnit resolution prefers vendor/bin/phpunit, which is what this run's own build installed")
    void theTestRunnerPrefersTheVendorBinary(@TempDir Path root) throws IOException {
        Path vendor = PhpTestContexts.write(root, "vendor/bin/phpunit", "#!/usr/bin/env php\n");

        List<String> resolved = PhpInterpreter.phpunit(root, PhpTestContexts.MISSING_PHP, "/elsewhere/phpunit.phar");

        assertEquals(vendor.toAbsolutePath().normalize().toString(), resolved.getLast(),
                "vendor/bin/phpunit is the only runner guaranteed to match the project's composer.lock");
        assertFalse(String.join(" ", resolved).contains("elsewhere"),
                "the inspection-time hint must not outrank the binary the build just produced: " + resolved);
    }

    @Test
    @DisplayName("the probe's hint is used when the build installed nothing, and a .phar goes through the interpreter")
    void theProbeHintIsTheFallback(@TempDir Path root) {
        assertEquals(List.of(PhpTestContexts.MISSING_PHP, "/opt/phpunit.phar"),
                PhpInterpreter.phpunit(root, PhpTestContexts.MISSING_PHP, "/opt/phpunit.phar"),
                "a .phar is not executable on its own, so it is invoked through the interpreter");
    }

    @Test
    @DisplayName("phpunit.phar beside the interpreter is the last resort, for a machine that ships both")
    void phpunitBesideTheInterpreterIsTheLastResort(@TempDir Path root) throws IOException {
        Path php = PhpTestContexts.write(root, "bin/php", "#!/bin/sh\n");
        Path phar = PhpTestContexts.write(root, "bin/phpunit.phar", "phar\n");

        assertEquals(List.of(php.toAbsolutePath().normalize().toString(),
                        phar.toAbsolutePath().normalize().toString()),
                PhpInterpreter.phpunit(root, php.toString(), null));
    }

    @Test
    @DisplayName("with no runner anywhere the resolution is empty, so the phase must report 'not executed'")
    void noPhpunitAnywhereResolvesToNothing(@TempDir Path root) {
        assertTrue(PhpInterpreter.phpunit(root, PhpTestContexts.MISSING_PHP, null).isEmpty());
        assertTrue(PhpInterpreter.phpunit(root, PhpTestContexts.MISSING_PHP, "   ").isEmpty());
    }

    @Test
    @DisplayName("the test command names the report, the configuration file and no result cache")
    void theTestCommandNamesTheReportAndTheConfiguration(@TempDir Path root, @TempDir Path run) throws IOException {
        PhpTestContexts.write(root, "phpunit.xml", "<phpunit/>\n");
        PhpTestContexts.write(root, "tests/LedgerTest.php", "<?php\n");
        PhpTestContexts.write(root, "vendor/bin/phpunit", "#!/usr/bin/env php\n");
        VerificationContext context = PhpTestContexts.context(
                PhpTestContexts.withAttributes(root, Map.of(PhpInterpreter.ATTR_PHP, PhpTestContexts.MISSING_PHP)),
                run);

        List<String> command = new PhpTestProvider().testCommand(context);
        String rendered = String.join(" ", command);

        assertTrue(rendered.contains("--log-junit"), rendered);
        assertTrue(rendered.contains(PhpTestProvider.reportPath(context).toString()), rendered);
        assertTrue(rendered.contains("--do-not-cache-result"),
                "a cached outcome would be a previous run's observation, not this one's: " + rendered);
        assertTrue(rendered.contains("--colors=never"), rendered);
        assertTrue(rendered.contains("--configuration"),
                "the configuration file is the bootstrap, so without it PHPUnit cannot load a test class: " + rendered);
        assertTrue(rendered.contains(root.resolve("phpunit.xml").toString()), rendered);
    }

    @Test
    @DisplayName("without a configuration file the detected test roots are passed as the paths to run")
    void theTestRootsArePassedWithoutAConfiguration(@TempDir Path root, @TempDir Path run) throws IOException {
        PhpTestContexts.write(root, "tests/LedgerTest.php", "<?php\n");
        PhpTestContexts.write(root, "vendor/bin/phpunit", "#!/usr/bin/env php\n");
        VerificationContext context = PhpTestContexts.context(
                PhpTestContexts.withAttributes(root, Map.of(PhpInterpreter.ATTR_PHP, PhpTestContexts.MISSING_PHP)),
                run);

        List<String> command = new PhpTestProvider().testCommand(context);

        assertTrue(command.contains(root.resolve("tests").toString()),
                "with no configuration file the test root is the only thing PHPUnit can find tests from: " + command);
        assertFalse(String.join(" ", command).contains("--configuration"), command.toString());
    }

    @Test
    @DisplayName("a project without PHPUnit is reported as not executed, never as a suite with no failures")
    void theRunIsNotExecutedWithoutPhpunit(@TempDir Path root, @TempDir Path run) throws IOException {
        PhpTestContexts.write(root, "tests/LedgerTest.php", "<?php\n");
        VerificationContext context = PhpTestContexts.context(
                PhpTestContexts.withAttributes(root, Map.of(PhpInterpreter.ATTR_PHP, PhpTestContexts.MISSING_PHP)),
                run);

        TestResult result = new PhpTestProvider().runTests(context);

        assertFalse(result.executed());
        assertEquals(0, result.total());
        assertTrue(result.note().contains("PHPUnit was not found"), result.note());
    }

    @Test
    @DisplayName("the replay command selects exactly one case with PHPUnit's own filter syntax")
    void theReplayCommandSelectsOneCase(@TempDir Path root, @TempDir Path run) throws IOException {
        PhpTestContexts.write(root, "vendor/bin/phpunit", "#!/usr/bin/env php\n");
        VerificationContext context = PhpTestContexts.context(
                PhpTestContexts.withAttributes(root, Map.of(PhpInterpreter.ATTR_PHP, PhpTestContexts.MISSING_PHP)),
                run);
        TestCaseResult failing = new TestCaseResult("CodeWitness.Fixture.Tests.WithdrawalLedgerTest",
                "testRejectsNegativeAmounts", TestOutcome.FAILED, Duration.ZERO,
                "ExpectationFailedException", "boom", "trace");

        List<String> replay = new PhpTestProvider().replayCommand(context, failing);

        assertTrue(replay.contains("--filter"), replay.toString());
        assertTrue(replay.contains("CodeWitness.Fixture.Tests.WithdrawalLedgerTest::testRejectsNegativeAmounts"),
                "the counterexample's command must run that one test: " + replay);
        assertTrue(replay.contains("--do-not-cache-result"), replay.toString());
    }

    @Test
    @DisplayName("Composer is resolved from the project, then PATH, then the project's .tools directory")
    void composerResolution(@TempDir Path root) throws IOException {
        assertTrue(PhpInterpreter.composer(root, PhpTestContexts.MISSING_PHP, "").isEmpty(),
                "a tree with no Composer has none to run, and must say so rather than invent one");

        Path phar = PhpTestContexts.write(root, "composer.phar", "<?php\n");
        assertEquals(List.of(PhpTestContexts.MISSING_PHP, phar.toString()),
                PhpInterpreter.composer(root, PhpTestContexts.MISSING_PHP, ""));
    }

    @Test
    @DisplayName("the interpreter is resolved from CODEWITNESS_PHP, then the project, then PATH")
    void interpreterResolution(@TempDir Path root) throws IOException {
        assertTrue(PhpInterpreter.resolve(root, Map.of("PATH", "")).startsWith("php"),
                "with nothing to find, the bare name is returned for the sandbox's PATH to resolve");

        Path projectPhp = PhpTestContexts.write(root, ".tools/php/php", "#!/bin/sh\n");
        assertTrue(projectPhp.toFile().setExecutable(true), "the test needs an executable candidate");
        assertEquals(projectPhp.toAbsolutePath().normalize().toString(),
                PhpInterpreter.resolve(root, Map.of("PATH", "")),
                "a project that pins its own interpreter is tested with that interpreter");

        Path explicit = PhpTestContexts.write(root, "custom/php", "#!/bin/sh\n");
        assertTrue(explicit.toFile().setExecutable(true));
        assertEquals(explicit.toAbsolutePath().normalize().toString(),
                PhpInterpreter.resolve(root, Map.of("CODEWITNESS_PHP", explicit.toString(), "PATH", "")),
                "an explicit request outranks everything the project happens to ship");
    }

    @Test
    @DisplayName("a probe of an interpreter that is not there reports unavailable, not a version")
    void theProbeOfAMissingInterpreterIsUnavailable(@TempDir Path root, @TempDir Path run) {
        VerificationContext context = PhpTestContexts.context(root, run);

        PhpInterpreter.Probe probe = PhpInterpreter.probe(context, PhpTestContexts.MISSING_PHP);

        assertFalse(probe.available());
        assertNull(probe.version());
        assertNull(probe.composerVersion());
        assertNull(probe.phpunit());
        assertNull(probe.coverageDriver(), "no driver was observed, so none may be named");
        assertFalse(probe.hasCoverageDriver());
        assertTrue(probe.failureReason() != null && !probe.failureReason().isBlank());
    }

    @Test
    @DisplayName("Composer's home and cache are inside the run, never in the developer's own home")
    void composerIsConfinedToTheRun(@TempDir Path root, @TempDir Path run) {
        Map<String, String> environment = PhpInterpreter.environment(PhpTestContexts.projectInfo(root), root, run);

        assertEquals(run.resolve("php/composer-home").toString(), environment.get("COMPOSER_HOME"));
        assertEquals(run.resolve("php/composer-cache").toString(), environment.get("COMPOSER_CACHE_DIR"));
        assertEquals("1", environment.get("COMPOSER_NO_INTERACTION"));
        assertEquals("1", environment.get("COMPOSER_NO_AUDIT"),
                "CodeWitness runs its own advisory scan, so Composer's would only cost time");
        assertEquals("1", environment.get("COMPOSER_ALLOW_SUPERUSER"));
        assertEquals("1", environment.get("COMPOSER_DISABLE_XDEBUG_WARN"));
    }
}
