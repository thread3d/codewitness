package dev.codewitness.ruby.dependency;

import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.PackageCoordinate;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.ruby.RubyTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Inventory of a Bundler project's resolved dependencies.
 *
 * <p>The lock file's shape is its indentation, so the interesting assertions are about what the indentation
 * means: a gem at four spaces is a package the project ships, a requirement at six belongs to the gem above
 * it and is not a package, and a name {@code DEPENDENCIES} asks for that no {@code specs} block resolved is
 * reported without a version rather than guessed at. An advisory is a statement about a version that would
 * actually be installed, so an empty version is what keeps the inventory honest.
 */
class RubyDependencyAnalyzerTest {

    @Test
    @DisplayName("a GEM specs block becomes one coordinate per gem, and deeper lines are not gems")
    void parsesTheSpecsBlocks(@TempDir Path root) {
        String lock = """
                GEM
                  remote: https://rubygems.org/
                  specs:
                    diff-lcs (1.6.2)
                    rspec (3.13.2)
                      rspec-core (~> 3.13.0)
                      rspec-expectations (~> 3.13.0)
                      rspec-sneaky (~> 9.9)
                    rspec-core (3.13.6)
                      rspec-support (~> 3.13.0)
                    nokogiri (1.16.0-arm64-darwin)
                    ffi (1.16.3)

                PATH
                  remote: .
                  specs:
                    ledger (0.1.0)

                GIT
                  remote: https://github.com/acme/ledger.git
                  revision: 0123456789abcdef
                  specs:
                    ledger-git (2.0.0)

                PLATFORMS
                  ruby

                DEPENDENCIES
                  ffi
                  ledger!
                  rspec (~> 3.13)
                  acme-not-resolved (~> 1.0)

                CHECKSUMS
                  diff-lcs (1.6.2) sha256=9ae0d2cba7d4df3075fe8cd8602a8604993efc0dfa934cff568969efb1909962

                BUNDLED WITH
                   4.0.20
                """;

        List<PackageCoordinate> coordinates = RubyDependencyAnalyzer.fromLock(lock);

        assertEquals(8, coordinates.size(), coordinates.toString());
        assertTrue(coordinates.contains(PackageCoordinate.rubygem("diff-lcs", "1.6.2")),
                coordinates.toString());
        assertTrue(coordinates.contains(PackageCoordinate.rubygem("rspec", "3.13.2")),
                coordinates.toString());
        assertTrue(coordinates.contains(PackageCoordinate.rubygem("rspec-core", "3.13.6")),
                "a gem that is both a requirement and its own specs entry is one package, not two: "
                        + coordinates);
        assertFalse(coordinates.stream().anyMatch(coordinate -> "rspec-sneaky".equals(coordinate.name())),
                "a requirement indented below the gem above it is that gem's dependency, not this "
                        + "project's package: " + coordinates);
        assertTrue(coordinates.contains(PackageCoordinate.rubygem("nokogiri", "1.16.0")),
                "a platform-suffixed version is the release Bundler resolved, with the build platform "
                        + "dropped: " + coordinates);
        assertTrue(coordinates.contains(PackageCoordinate.rubygem("ledger", "0.1.0")),
                "a gem resolved from a local path is still a gem of the project: " + coordinates);
        assertTrue(coordinates.contains(PackageCoordinate.rubygem("ledger-git", "2.0.0")),
                "and so is one resolved from a git revision: " + coordinates);
        assertTrue(coordinates.stream().allMatch(coordinate -> PackageCoordinate.RUBYGEMS.equals(
                coordinate.ecosystem())), "a bare gem name is only queryable in its own ecosystem: "
                + coordinates);
    }

    @Test
    @DisplayName("a DEPENDENCIES entry that no specs block resolved becomes an unresolved coordinate")
    void namesWithoutAResolvedSpecAreUnresolved() {
        String lock = """
                GEM
                  remote: https://rubygems.org/
                  specs:
                    rspec (3.13.2)

                DEPENDENCIES
                  rspec (~> 3.13)
                  acme-missing (~> 1.0)
                """;

        List<PackageCoordinate> coordinates = RubyDependencyAnalyzer.fromLock(lock);

        assertEquals(2, coordinates.size(), coordinates.toString());
        PackageCoordinate unresolved = coordinates.getLast();
        assertEquals("acme-missing", unresolved.name());
        assertEquals("", unresolved.version(),
                "no version was resolved, so none may be invented: an empty version is what stops the "
                        + "advisory scan from pretending it was checked");
        assertFalse(unresolved.resolved());
        assertEquals("acme-missing", unresolved.display(),
                "with no version there is nothing to put after an @");
    }

    @Test
    @DisplayName("the resolved graph is inventoried, transitive gems included, and the note names Bundler")
    void inventoriesTheLockFile(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        RubyTestContexts.write(root, "Gemfile", "gem \"rspec\", \"~> 3.13\"\n");
        RubyTestContexts.write(root, "lib/withdrawal_ledger.rb", "class WithdrawalLedger\nend\n");
        RubyTestContexts.write(root, "Gemfile.lock", """
                GEM
                  remote: https://rubygems.org/
                  specs:
                    diff-lcs (1.6.2)
                    rspec (3.13.2)
                      rspec-core (~> 3.13.0)
                      rspec-expectations (~> 3.13.0)
                      rspec-mocks (~> 3.13.0)
                    rspec-core (3.13.6)
                      rspec-support (~> 3.13.0)
                    rspec-expectations (3.13.5)
                      diff-lcs (>= 1.2.0, < 2.0)
                      rspec-support (~> 3.13.0)
                    rspec-mocks (3.13.8)
                      diff-lcs (>= 1.2.0, < 2.0)
                      rspec-support (~> 3.13.0)
                    rspec-support (3.13.7)

                PLATFORMS
                  ruby

                DEPENDENCIES
                  rspec (~> 3.13)

                BUNDLED WITH
                   4.0.20
                """);
        VerificationContext context = RubyTestContexts.context(root, runDirectory);

        DependencyReport report = new RubyDependencyAnalyzer().analyseDependencies(context);

        assertTrue(report.analysed(), report.note());
        assertEquals("bundler-lock", report.tool());
        assertEquals(6, report.totalDependencies(),
                "the lock resolves the transitive graph too: " + report.directDependencies());
        assertTrue(report.directDependencies().contains("rspec@3.13.2"),
                report.directDependencies().toString());
        assertTrue(report.directDependencies().contains("rspec-support@3.13.7"),
                "a gem nothing declared directly is still a package this project installs: "
                        + report.directDependencies());
        assertTrue(report.note().contains("Gemfile.lock"),
                "the note must say which file the inventory came from: " + report.note());
        assertTrue(report.note().contains("Bundler"),
                "and that it is what Bundler resolved: " + report.note());
        assertTrue(report.note().contains("inventory only"),
                "the report must admit it is an inventory only: " + report.note());
        assertTrue(report.note().contains("known advisory"), report.note());
        assertTrue(report.coordinates().stream().allMatch(coordinate -> PackageCoordinate.RUBYGEMS.equals(
                coordinate.ecosystem())), report.coordinates().toString());
        assertEquals(6L, report.coordinates().stream().filter(PackageCoordinate::resolved).count(),
                "every gem the lock resolved carries a version an advisory service can match on: "
                        + report.coordinates());
        assertEquals(1, report.dependenciesByModule().size(), report.dependenciesByModule().toString());
        assertEquals(report.directDependencies(),
                report.dependenciesByModule().values().iterator().next(),
                "the module the report attributes the dependencies to carries every one of them: "
                        + report.dependenciesByModule());
    }

    @Test
    @DisplayName("a dependency with no resolved version is inventoried and the note says how many")
    void unresolvedGemsAreCountedInTheNote(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        RubyTestContexts.write(root, "Gemfile", "gem \"acme-missing\"\n");
        RubyTestContexts.write(root, "Gemfile.lock", """
                GEM
                  remote: https://rubygems.org/
                  specs:
                    rspec (3.13.2)

                DEPENDENCIES
                  rspec (~> 3.13)
                  acme-missing (~> 1.0)
                """);
        VerificationContext context = RubyTestContexts.context(root, runDirectory);

        DependencyReport report = new RubyDependencyAnalyzer().analyseDependencies(context);

        assertTrue(report.analysed());
        assertEquals(2, report.totalDependencies(), report.directDependencies().toString());
        assertTrue(report.directDependencies().contains("acme-missing"),
                "an unresolved dependency is still part of the inventory: " + report.directDependencies());
        assertEquals(1, report.coordinates().stream().filter(coordinate -> !coordinate.resolved()).count(),
                report.coordinates().toString());
        assertTrue(report.note().contains("name no resolved version"),
                "the note must say that some of the graph cannot be asked about: " + report.note());
    }

    @Test
    @DisplayName("a missing lock file is reported as not analysed, never as an empty inventory")
    void aMissingLockFileIsNotAnalysed(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        RubyTestContexts.write(root, "Gemfile", "gem \"rspec\", \"~> 3.13\"\n");
        RubyTestContexts.write(root, "lib/ledger.rb", "class Ledger\nend\n");
        VerificationContext context = RubyTestContexts.context(root, runDirectory);

        DependencyReport report = new RubyDependencyAnalyzer().analyseDependencies(context);

        assertFalse(report.analysed(),
                "a Gemfile names requirements, not the versions that were installed, so there is no "
                        + "resolved graph to ask an advisory service about");
        assertTrue(report.note().contains("no Gemfile.lock"), report.note());
        assertTrue(report.note().contains("Bundler"), report.note());
        assertTrue(report.coordinates().isEmpty(), report.coordinates().toString());
    }

    @Test
    @DisplayName("a lock file that is empty or resolves no gem is not analysed either")
    void anUnusableLockFileIsNotAnalysed(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        Path empty = root.resolve("empty");
        RubyTestContexts.write(empty, "Gemfile", "gem \"rspec\"\n");
        RubyTestContexts.write(empty, "Gemfile.lock", "");

        Path nothing = root.resolve("nothing");
        RubyTestContexts.write(nothing, "Gemfile", "gem \"rspec\"\n");
        RubyTestContexts.write(nothing, "Gemfile.lock", "GEM\n  remote: https://rubygems.org/\n");

        Path absent = root.resolve("absent");
        RubyTestContexts.write(absent, "lib/ledger.rb", "class Ledger\nend\n");

        DependencyReport unreadable = new RubyDependencyAnalyzer()
                .analyseDependencies(RubyTestContexts.context(empty, runDirectory));
        DependencyReport resolvedNothing = new RubyDependencyAnalyzer()
                .analyseDependencies(RubyTestContexts.context(nothing, runDirectory));
        DependencyReport noLock = new RubyDependencyAnalyzer()
                .analyseDependencies(RubyTestContexts.context(absent, runDirectory));

        assertFalse(unreadable.analysed(), "an empty lock is not an inventory with no entries");
        assertTrue(unreadable.note().contains("could not be read"), unreadable.note());
        assertFalse(resolvedNothing.analysed());
        assertTrue(resolvedNothing.note().contains("resolved no gem"), resolvedNothing.note());
        assertFalse(noLock.analysed());
        assertTrue(noLock.note().contains("no Gemfile.lock"), noLock.note());
    }

    @Test
    @DisplayName("the analyzer runs no command and claims Bundler projects and nothing else")
    void runsNothingAndSupportsOnlyBundlerProjects(@TempDir Path root, @TempDir Path runDirectory)
            throws IOException {
        RubyTestContexts.write(root, "Gemfile", "gem \"rspec\", \"~> 3.13\"\n");
        RubyTestContexts.write(root, "Gemfile.lock", "GEM\n  specs:\n    rspec (3.13.2)\n");
        VerificationContext context = RubyTestContexts.context(root, runDirectory);
        RubyDependencyAnalyzer analyzer = new RubyDependencyAnalyzer();

        assertEquals("bundler", analyzer.id());
        assertEquals(List.of(), analyzer.dependencyCommand(context),
                "the lock file is read directly, so there is no command to record");
        assertTrue(analyzer.supports(context.projectInfo()));

        Path loose = root.resolve("loose");
        RubyTestContexts.write(loose, "ledger.rb", "class Ledger\nend\n");
        assertFalse(analyzer.supports(RubyTestContexts.projectInfo(loose)),
                "a tree with neither a Gemfile nor a Gemfile.lock declares no dependencies to inventory");

        ProjectInfo javaProject = ProjectInfo.builder(context.projectInfo().project())
                .language(LanguageId.JAVA)
                .buildSystem(context.projectInfo().buildSystem())
                .build();
        assertFalse(analyzer.supports(javaProject),
                "the Java adapter has its own dependency analyzer; two would both run");
    }
}
