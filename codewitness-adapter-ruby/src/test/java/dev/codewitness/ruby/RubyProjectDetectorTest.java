package dev.codewitness.ruby;

import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Detection of Ruby projects.
 *
 * <p>The Ruby detector differs from the PHP one in the two places Ruby's toolchain does: it has no
 * machine-readable manifest to parse ({@code Gemfile} and {@code *.gemspec} are Ruby source), so the facts
 * a report carries are read as text with line patterns and are never guessed; and Bundler is the only
 * package manager, so a tree without a {@code Gemfile} has no package manager at all rather than an
 * invented default.
 */
class RubyProjectDetectorTest {

    @Test
    @DisplayName("a Gemfile project with a lib/spec layout is detected, with its version and runner")
    void detectsGemfileProject(@TempDir Path root) throws IOException {
        RubyTestContexts.write(root, "Gemfile", """
                source "https://rubygems.org"

                ruby "3.3.6"

                gem "rspec", "~> 3.13"
                """);
        RubyTestContexts.write(root, "lib/withdrawal_ledger.rb", "class WithdrawalLedger\nend\n");
        RubyTestContexts.write(root, "spec/withdrawal_ledger_spec.rb", "RSpec.describe('Ledger') {}\n");

        ProjectInfo info = RubyProjectDetector.inspect(ProjectRef.of(root));

        assertTrue(RubyProjectDetector.looksLikeRubyProject(root),
                "a Gemfile is a Ruby build descriptor, so the adapter claims the tree");
        assertEquals(LanguageId.RUBY, info.language());
        assertEquals(BuildSystemId.BUNDLER, info.buildSystem());
        assertEquals(root.getFileName().toString(), info.modules().getFirst().name(),
                "nothing declared a gem name, so the project keeps its directory name rather than an "
                        + "invented one");
        assertEquals(root.resolve("lib"), info.sourceDirs().getFirst());
        assertEquals(root.resolve("spec"), info.testSourceDirs().getFirst());
        assertEquals("3.3.6", info.attributes().get(RubyProjectDetector.ATTR_RUBY_VERSION),
                "the Gemfile's ruby line is the request Bundler itself acts on");
        assertEquals("bundler", info.attributes().get(RubyProjectDetector.ATTR_PACKAGE_MANAGER));
        assertEquals("rspec", info.attributes().get(RubyProjectDetector.ATTR_TEST_FRAMEWORK));
        assertTrue(info.testFrameworks().contains("rspec"));
        assertEquals("true", info.attributes().get(RubyProjectDetector.ATTR_HAS_TESTS));
        assertEquals("rb,rake,gemspec", info.attributes().get(RubyProjectDetector.ATTR_SOURCE_EXTENSIONS));
        assertTrue(info.sourceExtensions().containsAll(Set.of("rb", "rake", "gemspec")));
        assertTrue(info.attributes().get(RubyProjectDetector.ATTR_DESCRIPTOR).contains("Gemfile"));
        assertNull(info.attributes().get(RubyProjectDetector.ATTR_DETECTION_WARNING),
                "a descriptor that was read must not be reported as doubtful");
    }

    @Test
    @DisplayName("a bare .rb file with no descriptor is claimed, and the project says what it cannot do")
    void detectsAFlatProjectWithoutADescriptor(@TempDir Path root) throws IOException {
        RubyTestContexts.write(root, "Ledger.rb", "class Ledger\nend\n");

        assertTrue(RubyProjectDetector.looksLikeRubyProject(root));
        ProjectInfo info = RubyProjectDetector.inspect(ProjectRef.of(root));

        assertEquals(BuildSystemId.NONE, info.buildSystem(),
                "without a Gemfile there is no Bundler project, however Ruby the sources are");
        assertEquals("unknown", info.attributes().get(RubyProjectDetector.ATTR_PACKAGE_MANAGER),
                "Bundler is the only package manager Ruby has, so a tree without one must not name a "
                        + "default");
        assertEquals("none", info.attributes().get(RubyProjectDetector.ATTR_DESCRIPTOR));
        assertTrue(info.attributes().get(RubyProjectDetector.ATTR_DETECTION_WARNING)
                        .contains("no Ruby build descriptor"),
                info.attributes().get(RubyProjectDetector.ATTR_DETECTION_WARNING));
        assertEquals(List.of(root), info.sourceDirs(),
                "a gem that predates the conventional layout keeps its sources at the root");
        assertEquals(List.of(), info.testSourceDirs(),
                "Ruby has no convention of a test file beside its source; spec/ and test/ are the only "
                        + "test roots this detector names");
        assertEquals("false", info.attributes().get(RubyProjectDetector.ATTR_HAS_TESTS));
        assertEquals("none", info.attributes().get(RubyProjectDetector.ATTR_TEST_FRAMEWORK),
                "no framework was declared, and minitest is not assumed just because Ruby ships it");
        assertTrue(info.testFrameworks().isEmpty(),
                "'none' is the absence of a framework, not a framework named none");
        assertNull(info.attributes().get(RubyProjectDetector.ATTR_RUBY_VERSION),
                "nothing declared a Ruby version, so none may be reported");
    }

    @Test
    @DisplayName("a tree with neither a descriptor nor Ruby source is not claimed")
    void rejectsNonRubyProject(@TempDir Path root) throws IOException {
        RubyTestContexts.write(root, "README.txt", "nothing to see\n");
        RubyTestContexts.write(root, "src/main/java/example/Ledger.java", "package example;\n");

        assertFalse(RubyProjectDetector.looksLikeRubyProject(root));
        assertTrue(RubyProjectDetector.rubyFiles(root).isEmpty(),
                "a Java source file is the Java adapter's to read: .java is not one of rb/rake/gemspec");
    }

    @Test
    @DisplayName("a *.gemspec names the module and is the last descriptor to state a Ruby version")
    void aGemspecNamesTheModule(@TempDir Path root) throws IOException {
        RubyTestContexts.write(root, "withdrawal_ledger.gemspec", """
                Gem::Specification.new do |spec|
                  spec.name = "withdrawal_ledger"
                  spec.required_ruby_version = ">= 3.1"
                  spec.summary = "a ledger"
                end
                """);
        RubyTestContexts.write(root, "lib/withdrawal_ledger.rb", "class WithdrawalLedger\nend\n");

        ProjectInfo info = RubyProjectDetector.inspect(ProjectRef.of(root));

        assertEquals("withdrawal_ledger", info.modules().getFirst().name(),
                "the gemspec's own name is what a human and a report mean by 'the project'");
        assertEquals("3.1", info.attributes().get(RubyProjectDetector.ATTR_RUBY_VERSION),
                "required_ruby_version is a constraint the gem tolerates, but it is the only answer a "
                        + "gemspec can give");
        assertTrue(info.attributes().get(RubyProjectDetector.ATTR_DESCRIPTOR)
                        .contains("withdrawal_ledger.gemspec"),
                info.attributes().get(RubyProjectDetector.ATTR_DESCRIPTOR));
        assertEquals("ledger", RubyProjectDetector.moduleName("acme/ledger"),
                "a namespaced name keeps its bare form, so a directory never becomes a module name");
    }

    @Test
    @DisplayName(".ruby-version pins a version, but the Gemfile's ruby line outranks it")
    void theRubyVersionHintPrefersTheGemfile(@TempDir Path root) throws IOException {
        Path pinned = root.resolve("pinned");
        RubyTestContexts.write(pinned, ".ruby-version", "ruby-3.2.2\n");
        RubyTestContexts.write(pinned, "lib/ledger.rb", "class Ledger\nend\n");

        Path asked = root.resolve("asked");
        RubyTestContexts.write(asked, "Gemfile", "ruby '3.3.0'\n");
        RubyTestContexts.write(asked, ".ruby-version", "3.2.2\n");
        RubyTestContexts.write(asked, "lib/ledger.rb", "class Ledger\nend\n");

        assertEquals("3.2.2", RubyProjectDetector.inspect(ProjectRef.of(pinned))
                        .attributes().get(RubyProjectDetector.ATTR_RUBY_VERSION),
                "the implementation prefix is part of the file's syntax, not of the version");
        assertEquals("3.3.0", RubyProjectDetector.inspect(ProjectRef.of(asked))
                        .attributes().get(RubyProjectDetector.ATTR_RUBY_VERSION),
                "the Gemfile's line is the request Bundler acts on, so it outranks the version manager's "
                        + "file");
    }

    @Test
    @DisplayName("spec/ selects RSpec and test/ selects minitest, and RSpec wins when both are present")
    void theTestDirectoriesSelectTheirFrameworks(@TempDir Path root) throws IOException {
        Path specProject = root.resolve("spec-project");
        RubyTestContexts.write(specProject, "Gemfile", "gem \"rack\"\n");
        RubyTestContexts.write(specProject, "spec/ledger_spec.rb", "RSpec.describe('Ledger') {}\n");

        Path testProject = root.resolve("test-project");
        RubyTestContexts.write(testProject, "Gemfile", "gem \"rack\"\n");
        RubyTestContexts.write(testProject, "test/test_ledger.rb", "require 'minitest/autorun'\n");

        Path both = root.resolve("both");
        RubyTestContexts.write(both, "Gemfile", "# rspec and minitest are both here\n");
        RubyTestContexts.write(both, "spec/ledger_spec.rb", "RSpec.describe('Ledger') {}\n");
        RubyTestContexts.write(both, "test/test_ledger.rb", "require 'minitest/autorun'\n");

        Path rakeOnly = root.resolve("rake-only");
        RubyTestContexts.write(rakeOnly, "Rakefile", "task :default\n");
        RubyTestContexts.write(rakeOnly, "lib/ledger.rb", "class Ledger\nend\n");

        assertEquals("rspec", framework(specProject));
        assertEquals("minitest", framework(testProject));
        assertEquals("rspec", framework(both),
                "a project that keeps both is asked for its RSpec report: the coverage result set this "
                        + "adapter reads is built around it");
        assertEquals("minitest", framework(rakeOnly),
                "a Rakefile is where a minitest suite is normally wired up, so it declares one");
        assertTrue(RubyProjectDetector.inspect(ProjectRef.of(rakeOnly))
                        .testSourceDirs().isEmpty(),
                "a Rakefile declares a runner, not a test directory");
    }

    @Test
    @DisplayName("source roots prefer lib, then app, then src, and fall back to the root")
    void sourceRootsPreferLib(@TempDir Path root) throws IOException {
        Path conventional = root.resolve("conventional");
        RubyTestContexts.write(conventional, "lib/ledger.rb", "class Ledger\nend\n");
        RubyTestContexts.write(conventional, "app/ledger.rb", "class Ledger\nend\n");
        RubyTestContexts.write(conventional, "src/ledger.rb", "class Ledger\nend\n");

        Path railsish = root.resolve("railsish");
        RubyTestContexts.write(railsish, "app/ledger.rb", "class Ledger\nend\n");
        RubyTestContexts.write(railsish, "src/ledger.rb", "class Ledger\nend\n");

        Path flat = root.resolve("flat");
        RubyTestContexts.write(flat, "ledger.rb", "class Ledger\nend\n");

        assertEquals(List.of(conventional.resolve("lib")),
                RubyProjectDetector.inspect(ProjectRef.of(conventional)).sourceDirs());
        assertEquals(List.of(railsish.resolve("app")),
                RubyProjectDetector.inspect(ProjectRef.of(railsish)).sourceDirs());
        assertEquals(List.of(flat),
                RubyProjectDetector.inspect(ProjectRef.of(flat)).sourceDirs(),
                "with none of the conventional directories the sources are next to the descriptor");
    }

    @Test
    @DisplayName("rubyFiles skips the bundle tree, the tool output and the caches")
    void rubyFilesSkipsBundlerAndToolOutput(@TempDir Path root) throws IOException {
        RubyTestContexts.write(root, "lib/ledger.rb", "class Ledger\nend\n");
        RubyTestContexts.write(root, "vendor/bundle/ruby/3.3.0/gems/rspec/lib/rspec.rb", "# gem\n");
        RubyTestContexts.write(root, ".bundle/config.rb", "# bundler config\n");
        RubyTestContexts.write(root, "coverage/resultset.rb", "# simplecov output\n");
        RubyTestContexts.write(root, "tmp/scratch.rb", "# scratch\n");
        RubyTestContexts.write(root, "log/run.rb", "# log\n");
        RubyTestContexts.write(root, "node_modules/pkg/index.rb", "# node\n");

        List<Path> files = RubyProjectDetector.rubyFiles(root);

        assertEquals(1, files.size(), files.toString());
        assertTrue(files.getFirst().toString().endsWith("lib/ledger.rb"), files.getFirst().toString());
        for (String skipped : List.of("vendor", ".bundle", "coverage", "tmp", "log", "node_modules")) {
            assertTrue(RubyProjectDetector.isExcludedSource(root,
                            root.resolve(skipped).resolve("skipped.rb")),
                    skipped + " is a tool's own tree, not project source");
        }
        assertFalse(RubyProjectDetector.isExcludedSource(root, root.resolve("lib/ledger.rb")),
                "the project's own library source is exactly what must be analysed");
    }

    @Test
    @DisplayName("a workspace under .codewitness is not excluded by its own run directory")
    void excludesRelativeToTheWalkedRoot(@TempDir Path root) throws IOException {
        Path workspace = root.resolve(".codewitness/runs/CW-TEST/workspace");
        RubyTestContexts.write(workspace, "ledger.rb", "class Ledger\nend\n");
        RubyTestContexts.write(workspace, "vendor/bundle/ruby/3.3.0/gems/rspec/lib/rspec.rb", "# gem\n");

        // The run workspace legitimately lives under .codewitness; an absolute exclusion check would
        // reject every file in it, leaving analysis and mutation silently with nothing to do.
        assertEquals(1, RubyProjectDetector.rubyFiles(workspace).size());
        assertFalse(RubyProjectDetector.isExcludedSource(workspace, workspace.resolve("ledger.rb")));
        assertTrue(RubyProjectDetector.isExcludedSource(workspace,
                workspace.resolve("vendor/bundle/ruby/3.3.0/gems/rspec/lib/rspec.rb")));
    }

    @Test
    @DisplayName("a .rake task and a .gemspec are Ruby source, because the interpreter parses them as such")
    void countsRakeAndGemspecAsSource(@TempDir Path root) throws IOException {
        RubyTestContexts.write(root, "lib/ledger.rb", "class Ledger\nend\n");
        RubyTestContexts.write(root, "tasks/report.rake", "task :report\n");
        RubyTestContexts.write(root, "ledger.gemspec",
                "Gem::Specification.new do |spec|\n  spec.name = \"ledger\"\nend\n");

        assertEquals(3, RubyProjectDetector.rubyFiles(root).size(),
                RubyProjectDetector.rubyFiles(root).toString());
    }

    /** Reads the declared framework of a project directory, for the framework-selection cases. */
    private static String framework(Path root) {
        return RubyProjectDetector.inspect(ProjectRef.of(root))
                .attributes().get(RubyProjectDetector.ATTR_TEST_FRAMEWORK);
    }
}
