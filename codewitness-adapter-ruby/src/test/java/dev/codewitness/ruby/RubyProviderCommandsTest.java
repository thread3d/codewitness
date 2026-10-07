package dev.codewitness.ruby;

import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.Capability;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestOutcome;
import dev.codewitness.core.spi.LanguageAdapterRegistry;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationProviderRegistry;
import dev.codewitness.ruby.dependency.RubyDependencyAnalyzer;
import dev.codewitness.support.Platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the adapter's wiring and for the commands it builds around the Ruby toolchain.
 *
 * <p>Every one of them runs with no Ruby, Bundler or RSpec installed: the interpreter is pointed at a path
 * that does not exist, or the resolution is asked a question whose answer the project itself decides, and
 * the test asserts the honest answer rather than depending on what the machine happens to have. The one
 * thing the tests must not do is silently pass on a machine that does have RSpec, so the resolution cases
 * pass an empty {@code PATH} where a directory search would otherwise be consulted.
 *
 * <p>RSpec and SimpleCov are not tools of the machine: they are gems the project's own build installs, so
 * the command that runs RSpec is resolved at <em>test</em> time. Bundler is the exception, and is resolved
 * the way the interpreter is.
 */
class RubyProviderCommandsTest {

    @Test
    @DisplayName("the adapter identifies itself, claims Ruby trees and declares only the phases it can carry out")
    void adapterBasics(@TempDir Path project) throws IOException {
        RubyLanguageAdapter adapter = new RubyLanguageAdapter();

        assertEquals("ruby", adapter.id());
        assertFalse(adapter.supports(ProjectRef.of(project)), "an empty directory is not claimed");

        RubyTestContexts.write(project, "lib/ledger.rb", "class Ledger\nend\n");
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
    @DisplayName("a Java tree is not Ruby evidence, whatever build files it carries")
    void buildDescriptorsAreNotEvidence(@TempDir Path project) throws IOException {
        RubyTestContexts.write(project, "src/main/java/example/Ledger.java",
                "package example;\n\nclass Ledger {}\n");
        RubyTestContexts.write(project, "pom.xml", "<project/>\n");

        assertFalse(new RubyLanguageAdapter().supports(ProjectRef.of(project)),
                "a Java project is the Java adapter's to verify");
    }

    @Test
    @DisplayName("the bundle registers the adapter and its providers, and the analysis ones only when asked")
    void bundleRegistration() {
        LanguageAdapterRegistry languages = new LanguageAdapterRegistry();
        VerificationProviderRegistry providers = VerificationProviderRegistry.empty();
        RubyAdapterBundle.register(languages, providers, true);

        assertEquals(List.of("ruby"), languages.ids());
        assertEquals(List.of("compiler:ruby-build", "test-runner:rspec", "static-analyzer:ruby-heuristics",
                        "coverage:simplecov", "mutation:ruby-mutation", "security:ruby-security",
                        "dependency:bundler", "test-generator:ruby-scenarios",
                        "generated-test-executor:rspec-generated"),
                providers.providerIds());

        VerificationProviderRegistry withoutAnalysis = VerificationProviderRegistry.empty();
        RubyAdapterBundle.register(new LanguageAdapterRegistry(), withoutAnalysis, false);
        assertEquals(List.of("compiler:ruby-build", "test-runner:rspec", "coverage:simplecov",
                        "mutation:ruby-mutation", "dependency:bundler", "test-generator:ruby-scenarios",
                        "generated-test-executor:rspec-generated"),
                withoutAnalysis.providerIds(),
                "the flag withholds the two analysis providers and nothing else: a build, a test runner, "
                        + "coverage, mutation, generated tests and the dependency inventory are measurement, "
                        + "not advice");
    }

    @Test
    @DisplayName("the providers claim Ruby projects and no Java project")
    void providersClaimByLanguage(@TempDir Path root) throws IOException {
        RubyTestContexts.write(root, "Gemfile", "gem \"rspec\", \"~> 3.13\"\n");
        RubyTestContexts.write(root, "lib/ledger.rb", "class Ledger\nend\n");
        RubyTestContexts.write(root, "spec/ledger_spec.rb", "RSpec.describe('Ledger') {}\n");
        ProjectInfo ruby = RubyTestContexts.projectInfo(root);
        assertEquals(LanguageId.RUBY, ruby.language());
        assertEquals(BuildSystemId.BUNDLER, ruby.buildSystem());

        VerificationProviderRegistry providers = VerificationProviderRegistry.empty();
        RubyAdapterBundle.register(new LanguageAdapterRegistry(), providers, true);

        assertEquals(1, providers.compilers(ruby).size());
        assertEquals("ruby-build", providers.compilers(ruby).getFirst().id());
        assertEquals(1, providers.testRunners(ruby).size());
        assertEquals("rspec", providers.testRunners(ruby).getFirst().id());

        ProjectInfo java = ProjectInfo.builder(ruby.project())
                .language(LanguageId.JAVA)
                .buildSystem(BuildSystemId.MAVEN)
                .build();
        assertTrue(providers.compilers(java).isEmpty(),
                "a Java project's build is the Java adapter's to run, not this one's");
        assertTrue(providers.testRunners(java).isEmpty(),
                "and its tests too, or two runners would both execute the same suite");
    }

    @Test
    @DisplayName("the interpreter is resolved from CODEWITNESS_RUBY, then the project, then PATH")
    void interpreterResolution(@TempDir Path root) throws IOException {
        assertEquals(rubyName(), RubyInterpreter.resolve(root, Map.of("PATH", "")),
                "with nothing to find, the bare name is returned for the sandbox's PATH to resolve");

        Path projectRuby = RubyTestContexts.write(root, ".tools/ruby/bin/" + rubyName(), "#!/bin/sh\n");
        assertTrue(projectRuby.toFile().setExecutable(true), "the test needs an executable candidate");
        assertEquals(projectRuby.toAbsolutePath().normalize().toString(),
                RubyInterpreter.resolve(root, Map.of("PATH", "")),
                "a project that pins its own interpreter is tested with that interpreter");

        Path explicit = RubyTestContexts.write(root, "custom/ruby", "#!/bin/sh\n");
        assertTrue(explicit.toFile().setExecutable(true));
        assertEquals(explicit.toAbsolutePath().normalize().toString(),
                RubyInterpreter.resolve(root, Map.of("CODEWITNESS_RUBY", explicit.toString(), "PATH", "")),
                "an explicit request outranks everything the project happens to ship");
    }

    @Test
    @DisplayName("the recorded interpreter is used, and a project with none has one resolved for it")
    void theInterpreterPreference(@TempDir Path root, @TempDir Path run) throws IOException {
        Path projectRuby = RubyTestContexts.write(root, ".tools/ruby/bin/" + rubyName(), "#!/bin/sh\n");
        assertTrue(projectRuby.toFile().setExecutable(true));

        ProjectInfo detected = RubyTestContexts.projectInfo(root);
        VerificationContext resolved = RubyTestContexts.context(detected, run);
        assertEquals(projectRuby.toAbsolutePath().normalize().toString(),
                RubyInterpreter.ruby(detected, resolved),
                "with nothing recorded, the interpreter is resolved from the project's own tree");

        ProjectInfo recorded = RubyTestContexts.withAttributes(root,
                Map.of(RubyInterpreter.ATTR_RUBY, RubyTestContexts.MISSING_RUBY));
        VerificationContext recordedContext = RubyTestContexts.context(recorded, run);
        assertEquals(RubyTestContexts.MISSING_RUBY, RubyInterpreter.ruby(recorded, recordedContext),
                "every phase must drive the one interpreter inspection recorded, not re-resolve one");
    }

    @Test
    @DisplayName("Bundler is resolved from the project's binstub, then the bundle tree, then the interpreter")
    void bundlerResolution(@TempDir Path root) throws IOException {
        Path nothing = root.resolve("nothing");
        Files.createDirectories(nothing);
        assertEquals(List.of(RubyTestContexts.MISSING_RUBY, "-S", "bundle"),
                RubyInterpreter.bundler(nothing, RubyTestContexts.MISSING_RUBY, ""),
                "a Ruby that ships Bundler as a default gem has no standalone executable but can always "
                        + "run one through -S");

        Path binstubProject = root.resolve("binstub");
        Path binstub = RubyTestContexts.write(binstubProject, "bin/" + bundlerName(),
                "#!/usr/bin/env ruby\n");
        assertEquals(List.of(binstub.toString()),
                RubyInterpreter.bundler(binstubProject, RubyTestContexts.MISSING_RUBY, ""),
                "the project's own binstub is the version its layout decided on");

        Path bundleTree = root.resolve("bundle-tree");
        Path vendored = RubyTestContexts.write(bundleTree,
                "vendor/bundle/ruby/3.3.0/bin/" + bundlerName(), "#!/usr/bin/env ruby\n");
        assertEquals(List.of(vendored.toString()),
                RubyInterpreter.bundler(bundleTree, RubyTestContexts.MISSING_RUBY, ""),
                "the bundle tree the run's own bundle install created is the version to run");

        Path pathDir = root.resolve("path");
        Path onPath = RubyTestContexts.write(pathDir, bundlerName(), "#!/usr/bin/env ruby\n");
        assertTrue(onPath.toFile().setExecutable(true));
        assertEquals(List.of(onPath.toAbsolutePath().normalize().toString()),
                RubyInterpreter.bundler(root.resolve("elsewhere"), RubyTestContexts.MISSING_RUBY,
                        pathDir.toString()),
                "a Bundler anywhere on PATH is the last answer before the interpreter's own");
    }

    @Test
    @DisplayName("RSpec is resolved from bin/rspec, then bundle exec, and is empty when there is none")
    void rspecResolution(@TempDir Path root) throws IOException {
        Path bare = root.resolve("bare");
        Files.createDirectories(bare);
        assertTrue(RubyInterpreter.rspec(bare, RubyTestContexts.MISSING_RUBY, "").isEmpty(),
                "no Gemfile and no RSpec found: there is nothing to run, and the provider must say so "
                        + "rather than run a command that cannot work");

        Path manifested = root.resolve("with-gemfile");
        RubyTestContexts.write(manifested, "Gemfile", "gem \"rspec\", \"~> 3.13\"\n");
        assertEquals(List.of(RubyTestContexts.MISSING_RUBY, "-S", "bundle", "exec", "rspec"),
                RubyInterpreter.rspec(manifested, RubyTestContexts.MISSING_RUBY, ""),
                "a Gemfile always makes bundle exec rspec available, through the interpreter's own Bundler");

        Path binstubProject = root.resolve("binstub");
        Path binstub = RubyTestContexts.write(binstubProject, "bin/" + rspecName(),
                "#!/usr/bin/env ruby\n");
        assertEquals(List.of(binstub.toString()),
                RubyInterpreter.rspec(binstubProject, RubyTestContexts.MISSING_RUBY, ""),
                "the binstub runs the project's own locked RSpec version");

        Path bundleTree = root.resolve("bundle-tree");
        Path vendored = RubyTestContexts.write(bundleTree,
                "vendor/bundle/ruby/3.3.0/bin/" + rspecName(), "#!/usr/bin/env ruby\n");
        assertEquals(List.of(vendored.toString()),
                RubyInterpreter.rspec(bundleTree, RubyTestContexts.MISSING_RUBY, ""),
                "an already-built tree whose dependencies arrived some other way is still runnable");
    }

    @Test
    @DisplayName("the test command names the JSON report and the detected test roots")
    void theTestCommandNamesTheReportAndTheRoots(@TempDir Path root, @TempDir Path run) throws IOException {
        RubyTestContexts.write(root, "Gemfile", "gem \"rspec\", \"~> 3.13\"\n");
        RubyTestContexts.write(root, "lib/ledger.rb", "class Ledger\nend\n");
        RubyTestContexts.write(root, "spec/ledger_spec.rb", "RSpec.describe('Ledger') {}\n");
        Path binstub = RubyTestContexts.write(root, "bin/" + rspecName(), "#!/usr/bin/env ruby\n");
        VerificationContext context = RubyTestContexts.context(
                RubyTestContexts.withAttributes(root,
                        Map.of(RubyInterpreter.ATTR_RUBY, RubyTestContexts.MISSING_RUBY)),
                run);

        List<String> command = new RubyTestProvider().testCommand(context);
        String rendered = String.join(" ", command);

        assertEquals(binstub.toString(), command.getFirst(), rendered);
        assertTrue(rendered.contains("--format json"), rendered);
        assertTrue(command.contains(RubyTestProvider.reportPath(context).toString()),
                "the report must be the run's own artefact, not a file the project is meant to keep: "
                        + rendered);
        assertTrue(command.contains(root.resolve("spec").toString()),
                "with no .rspec file the detected test root is the only statement about where tests live: "
                        + rendered);
    }

    @Test
    @DisplayName("a project with its own .rspec is not overruled by the detected test roots")
    void theProjectConfigurationIsNotOverruled(@TempDir Path root, @TempDir Path run) throws IOException {
        RubyTestContexts.write(root, ".rspec", "--require spec_helper\n");
        RubyTestContexts.write(root, "Gemfile", "gem \"rspec\", \"~> 3.13\"\n");
        RubyTestContexts.write(root, "spec/ledger_spec.rb", "RSpec.describe('Ledger') {}\n");
        RubyTestContexts.write(root, "bin/" + rspecName(), "#!/usr/bin/env ruby\n");
        VerificationContext context = RubyTestContexts.context(
                RubyTestContexts.withAttributes(root,
                        Map.of(RubyInterpreter.ATTR_RUBY, RubyTestContexts.MISSING_RUBY)),
                run);

        List<String> command = new RubyTestProvider().testCommand(context);

        assertFalse(command.contains(root.resolve("spec").toString()),
                "the project's .rspec may set the default path and the file pattern, so passing a root as "
                        + "well would let the command line silently overrule it: " + command);
        assertTrue(command.contains(RubyTestProvider.reportPath(context).toString()), command.toString());
    }

    @Test
    @DisplayName("the replay command pins one example with RSpec's own --example option")
    void theReplayCommandSelectsOneExample(@TempDir Path root, @TempDir Path run) throws IOException {
        RubyTestContexts.write(root, "Gemfile", "gem \"rspec\", \"~> 3.13\"\n");
        RubyTestContexts.write(root, "spec/ledger_spec.rb", "RSpec.describe('Ledger') {}\n");
        RubyTestContexts.write(root, "bin/" + rspecName(), "#!/usr/bin/env ruby\n");
        VerificationContext context = RubyTestContexts.context(
                RubyTestContexts.withAttributes(root,
                        Map.of(RubyInterpreter.ATTR_RUBY, RubyTestContexts.MISSING_RUBY)),
                run);
        TestCaseResult failing = new TestCaseResult("WithdrawalLedger",
                "rejects a negative amount", TestOutcome.FAILED, Duration.ZERO,
                "RSpec::Expectations::ExpectationNotMetError", "boom", "trace");

        List<String> replay = new RubyTestProvider().replayCommand(context, failing);

        assertTrue(replay.contains("--example"), replay.toString());
        assertTrue(replay.contains("rejects a negative amount"),
                "the command in a counterexample must run that one example: " + replay);
        assertFalse(replay.contains("--format"),
                "a replay is for a human to paste, not another machine-readable report: " + replay);
    }

    @Test
    @DisplayName("the dependency inventory reads the lock file directly, so there is no command to record")
    void theDependencyAnalyzerRunsNoCommand(@TempDir Path root, @TempDir Path run) throws IOException {
        RubyTestContexts.write(root, "Gemfile", "gem \"rspec\", \"~> 3.13\"\n");
        RubyTestContexts.write(root, "Gemfile.lock", "GEM\n  specs:\n    rspec (3.13.2)\n");
        VerificationContext context = RubyTestContexts.context(root, run);
        RubyDependencyAnalyzer analyzer = new RubyDependencyAnalyzer();

        assertEquals("bundler", analyzer.id());
        assertEquals(List.of(), analyzer.dependencyCommand(context),
                "bundle lock would rewrite the file this analysis is a report about, and bundle list "
                        + "needs the gems installed");
        assertTrue(analyzer.supports(context.projectInfo()));
    }

    /** @return the interpreter's executable name on this platform, as the adapter resolves it. */
    private static String rubyName() {
        return Platform.isWindows() ? "ruby.exe" : "ruby";
    }

    /** @return Bundler's executable name on this platform. */
    private static String bundlerName() {
        return Platform.isWindows() ? "bundle.bat" : "bundle";
    }

    /** @return RSpec's executable name on this platform. */
    private static String rspecName() {
        return Platform.isWindows() ? "rspec.bat" : "rspec";
    }
}
