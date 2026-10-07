package dev.codewitness.php.dependency;

import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.PackageCoordinate;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.php.PhpTestContexts;

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
 * Inventory of a Composer project's declared and resolved dependencies.
 *
 * <p>The interesting assertions are the negative ones: a platform requirement is not a package, and a
 * version constraint that admits more than one version must be reported without a version rather than
 * guessed at, because an advisory is a statement about a version that would actually be installed.
 */
class PhpDependencyAnalyzerTest {

    @Test
    @DisplayName("require and require-dev are inventoried, and platform entries are not packages")
    void inventoriesTheManifest(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        PhpTestContexts.write(root, "composer.json", """
                {
                  "name": "acme/ledger",
                  "require": {
                    "php": ">=8.1",
                    "ext-json": "*",
                    "lib-curl": "*",
                    "monolog/monolog": "^3.0",
                    "guzzlehttp/guzzle": "7.8.1"
                  },
                  "require-dev": {
                    "phpunit/phpunit": "10.5.0",
                    "vimeo/psalm": "dev-master"
                  }
                }
                """);
        VerificationContext context = PhpTestContexts.context(root, runDirectory);

        DependencyReport report = new PhpDependencyAnalyzer().analyseDependencies(context);

        assertTrue(report.analysed(), report.note());
        assertEquals("composer-manifest", report.tool());
        assertEquals(4, report.totalDependencies(),
                "php, ext-* and lib-* are capabilities, not packages: " + report.directDependencies());
        assertTrue(report.directDependencies().contains("guzzlehttp/guzzle@7.8.1"),
                "an exact constraint names the version to ask about: " + report.directDependencies());
        assertTrue(report.directDependencies().contains("phpunit/phpunit@10.5.0"),
                report.directDependencies().toString());
        assertTrue(report.directDependencies().contains("monolog/monolog"),
                "a caret range is not a version: " + report.directDependencies());
        assertTrue(report.directDependencies().contains("vimeo/psalm"),
                "a dev branch is not a version either: " + report.directDependencies());
        assertFalse(report.directDependencies().contains("php"),
                "the interpreter requirement must not be inventoried as a package: " + report.directDependencies());
        assertFalse(report.directDependencies().stream()
                        .anyMatch(name -> name.startsWith("ext-") || name.startsWith("lib-")),
                "an extension or library requirement is not a package either: " + report.directDependencies());
        assertTrue(report.coordinates().stream().allMatch(c -> "Packagist".equals(c.ecosystem())),
                report.coordinates().toString());
        assertEquals(2, report.coordinates().stream().filter(PackageCoordinate::resolved).count(),
                "only the two exact constraints are queryable: " + report.coordinates());
        assertTrue(report.note().contains("composer.json"),
                "the note must say which file the inventory came from: " + report.note());
        assertTrue(report.note().contains("known advisory"),
                "the report must admit it is an inventory only: " + report.note());
        assertEquals(List.of("monolog/monolog", "guzzlehttp/guzzle@7.8.1", "phpunit/phpunit@10.5.0",
                        "vimeo/psalm"),
                report.dependenciesByModule().get("ledger"),
                "the module the report attributes the dependencies to is composer.json's own name, and the "
                        + "order is the order the manifest declares them in: " + report.dependenciesByModule());
    }

    @Test
    @DisplayName("only a constraint naming exactly one version is treated as resolved")
    void onlyExactConstraintsAreResolved() {
        assertEquals("1.2.3", PhpDependencyAnalyzer.exactVersion("1.2.3"));
        assertEquals("1.2.3", PhpDependencyAnalyzer.exactVersion("v1.2.3"));
        assertEquals("1.2.3", PhpDependencyAnalyzer.exactVersion("=1.2.3"));
        assertEquals("1.2", PhpDependencyAnalyzer.exactVersion(" 1.2 "),
                "Composer reads 1.2 as exactly 1.2.0");
        for (String range : List.of("^1.2", "~1.2", "*", "1.*", "dev-main", "1.0.x-dev", ">=1.0 <2.0",
                "^1.2 || ^2.0", "@dev", "1.2.3 as 2.0.0")) {
            assertEquals("", PhpDependencyAnalyzer.exactVersion(range),
                    "'" + range + "' admits more than one version, so no advisory service can be asked about it");
        }
        assertEquals("", PhpDependencyAnalyzer.exactVersion(null));
    }

    @Test
    @DisplayName("composer.lock is preferred and carries the version each package resolved to")
    void readsResolvedVersionsFromTheLockFile(@TempDir Path root, @TempDir Path runDirectory)
            throws IOException {
        PhpTestContexts.write(root, "composer.json", """
                {
                  "name": "acme/ledger",
                  "require": {"monolog/monolog": "^3.0"}
                }
                """);
        PhpTestContexts.write(root, "composer.lock", """
                {
                  "packages": [
                    {"name": "monolog/monolog", "version": "3.5.0"},
                    {"name": "psr/log", "version": "v3.0.0"}
                  ],
                  "packages-dev": [
                    {"name": "phpunit/phpunit", "version": "10.5.0"},
                    {"name": "acme/dev-helper", "version": "dev-main"}
                  ]
                }
                """);
        VerificationContext context = PhpTestContexts.context(root, runDirectory);

        DependencyReport report = new PhpDependencyAnalyzer().analyseDependencies(context);

        assertEquals("composer-lock", report.tool());
        assertEquals(4, report.totalDependencies(), "the lock resolves the transitive graph too: "
                + report.directDependencies());
        assertTrue(report.directDependencies().contains("monolog/monolog@3.5.0"),
                report.directDependencies().toString());
        assertTrue(report.directDependencies().contains("psr/log@3.0.0"),
                "a v-prefixed version is the same version: " + report.directDependencies());
        assertTrue(report.directDependencies().contains("phpunit/phpunit@10.5.0"),
                "packages-dev is part of the project's dependency graph: " + report.directDependencies());
        assertTrue(report.directDependencies().contains("acme/dev-helper"),
                "a branch recorded in the lock is still not a version: " + report.directDependencies());
        assertTrue(report.note().contains("composer.lock"),
                "the note must say which file the inventory came from: " + report.note());
    }

    @Test
    @DisplayName("a lock file that resolves nothing falls back to the manifest and says so")
    void emptyLockFallsBackToTheManifest(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        PhpTestContexts.write(root, "composer.json", """
                {"name": "acme/ledger", "require": {"monolog/monolog": "^3.0"}}
                """);
        PhpTestContexts.write(root, "composer.lock", "{\"packages\": [], \"packages-dev\": []}\n");
        VerificationContext context = PhpTestContexts.context(root, runDirectory);

        DependencyReport report = new PhpDependencyAnalyzer().analyseDependencies(context);

        assertEquals("composer-manifest", report.tool());
        assertTrue(report.directDependencies().contains("monolog/monolog"), report.directDependencies().toString());
        assertTrue(report.note().contains("composer.lock resolved no package"), report.note());
    }

    @Test
    @DisplayName("a lock file that cannot be read is not analysed rather than silently falling back")
    void unreadableLockIsNotAnalysed(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        PhpTestContexts.write(root, "composer.json", """
                {"require": {"monolog/monolog": "^3.0"}}
                """);
        PhpTestContexts.write(root, "composer.lock", "{\"packages\": [\n");
        VerificationContext context = PhpTestContexts.context(root, runDirectory);

        DependencyReport report = new PhpDependencyAnalyzer().analyseDependencies(context);

        assertFalse(report.analysed(),
                "the lock is the only source of resolved versions, so losing it is not an empty inventory");
        assertTrue(report.note().contains("composer.lock"), report.note());
        assertTrue(report.coordinates().isEmpty(), report.coordinates().toString());
    }

    @Test
    @DisplayName("a manifest that cannot be read is not analysed, never an empty inventory")
    void unreadableManifestIsNotAnalysed(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        PhpTestContexts.write(root, "composer.json", "{ this is not json\n");
        VerificationContext context = PhpTestContexts.context(root, runDirectory);

        DependencyReport report = new PhpDependencyAnalyzer().analyseDependencies(context);

        assertFalse(report.analysed());
        assertTrue(report.note().contains("could not be parsed"), report.note());
    }

    @Test
    @DisplayName("a project with no composer.json is reported as not analysed")
    void withoutAManifestIsNotAnalysed(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        PhpTestContexts.write(root, "ledger.php", "<?php\n$balance = 100;\n");
        VerificationContext context = PhpTestContexts.context(root, runDirectory);

        DependencyReport report = new PhpDependencyAnalyzer().analyseDependencies(context);

        assertFalse(report.analysed());
        assertTrue(report.note().contains("no composer.json"), report.note());
    }

    @Test
    @DisplayName("the analyzer runs no command and claims PHP and nothing else")
    void runsNothingAndSupportsOnlyPhp(@TempDir Path root, @TempDir Path runDirectory) throws IOException {
        PhpTestContexts.write(root, "composer.json", """
                {"require": {"monolog/monolog": "3.5.0"}}
                """);
        VerificationContext context = PhpTestContexts.context(root, runDirectory);
        PhpDependencyAnalyzer analyzer = new PhpDependencyAnalyzer();

        assertEquals("php-dependencies", analyzer.id());
        assertEquals(List.of(), analyzer.dependencyCommand(context),
                "the manifests are read directly, so there is no command to record");
        assertTrue(analyzer.supports(context.projectInfo()));

        ProjectInfo javaProject = ProjectInfo.builder(context.projectInfo().project())
                .language(LanguageId.JAVA)
                .build();
        assertFalse(analyzer.supports(javaProject),
                "the Java adapter has its own dependency analyzer; two would both run");
    }
}
