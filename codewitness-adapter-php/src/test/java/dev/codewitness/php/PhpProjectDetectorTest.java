package dev.codewitness.php;

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

/** Detection of PHP projects. */
class PhpProjectDetectorTest {

    @Test
    @DisplayName("a Composer project with a src layout and a tests directory is detected")
    void detectsComposerProject(@TempDir Path root) throws IOException {
        PhpTestContexts.write(root, "composer.json", """
                {
                    "name": "codewitness/php-withdrawal-ledger",
                    "require": {"php": ">=8.1"},
                    "require-dev": {"phpunit/phpunit": "^11.5"}
                }
                """);
        PhpTestContexts.write(root, "src/WithdrawalLedger.php", "<?php\n");
        PhpTestContexts.write(root, "tests/WithdrawalLedgerTest.php", "<?php\n");

        ProjectInfo info = PhpProjectDetector.inspect(ProjectRef.of(root));

        assertEquals(LanguageId.PHP, info.language());
        assertEquals(BuildSystemId.COMPOSER, info.buildSystem());
        assertEquals("php-withdrawal-ledger", info.modules().getFirst().name(),
                "composer.json's name carries a vendor prefix that is not the project");
        assertEquals(root.resolve("src"), info.sourceDirs().getFirst());
        assertEquals(root.resolve("tests"), info.testSourceDirs().getFirst());
        assertEquals("8.1", info.attributes().get(PhpProjectDetector.ATTR_PHP_VERSION));
        assertEquals("composer", info.attributes().get(PhpProjectDetector.ATTR_PACKAGE_MANAGER));
        assertEquals("phpunit", info.attributes().get(PhpProjectDetector.ATTR_TEST_FRAMEWORK));
        assertTrue(info.testFrameworks().contains("phpunit"));
        assertEquals("true", info.attributes().get(PhpProjectDetector.ATTR_HAS_TESTS));
        assertEquals("php,phtml", info.attributes().get(PhpProjectDetector.ATTR_SOURCE_EXTENSIONS));
        assertTrue(info.sourceExtensions().containsAll(Set.of("php", "phtml")));
        assertTrue(info.attributes().get(PhpProjectDetector.ATTR_DESCRIPTOR).contains("composer.json"));
        assertNull(info.attributes().get(PhpProjectDetector.ATTR_DETECTION_WARNING),
                "a manifest that parsed must not be reported as doubtful");
    }

    @Test
    @DisplayName("a Pest project is reported as Pest, not as the PHPUnit underneath it")
    void detectsPest(@TempDir Path root) throws IOException {
        PhpTestContexts.write(root, "composer.json", """
                {"name": "acme/app", "require-dev": {"pestphp/pest": "^2.0"}}
                """);
        PhpTestContexts.write(root, "tests/ExampleTest.php", "<?php\n");

        ProjectInfo info = PhpProjectDetector.inspect(ProjectRef.of(root));

        assertEquals("pest", info.attributes().get(PhpProjectDetector.ATTR_TEST_FRAMEWORK));
        assertTrue(info.testFrameworks().contains("pest"));
    }

    @Test
    @DisplayName("a tree with no composer.json has no build system and no package manager, not an invented one")
    void detectsATreeWithoutComposer(@TempDir Path root) throws IOException {
        PhpTestContexts.write(root, "phpunit.xml.dist", "<phpunit/>\n");
        PhpTestContexts.write(root, "src/Ledger.php", "<?php\n");

        ProjectInfo info = PhpProjectDetector.inspect(ProjectRef.of(root));

        assertEquals(BuildSystemId.NONE, info.buildSystem());
        assertEquals("unknown", info.attributes().get(PhpProjectDetector.ATTR_PACKAGE_MANAGER));
        assertEquals("phpunit", info.attributes().get(PhpProjectDetector.ATTR_TEST_FRAMEWORK));
        assertNull(info.attributes().get(PhpProjectDetector.ATTR_PHP_VERSION),
                "nothing declared a PHP version, so none may be reported");
    }

    @Test
    @DisplayName("a project with no descriptor at all is still claimed and says why it has no install step")
    void detectsAFlatProjectWithoutADescriptor(@TempDir Path root) throws IOException {
        PhpTestContexts.write(root, "Ledger.php", "<?php\n");
        PhpTestContexts.write(root, "LedgerTest.php", "<?php\n");

        assertTrue(PhpProjectDetector.looksLikePhpProject(root));
        ProjectInfo info = PhpProjectDetector.inspect(ProjectRef.of(root));

        assertEquals("none", info.attributes().get(PhpProjectDetector.ATTR_DESCRIPTOR));
        assertTrue(info.attributes().get(PhpProjectDetector.ATTR_DETECTION_WARNING).contains("no PHP build descriptor"),
                info.attributes().get(PhpProjectDetector.ATTR_DETECTION_WARNING));
        assertEquals(List.of(root), info.sourceDirs(), "a flat layout keeps its classes next to the root");
        assertEquals(List.of(root), info.testSourceDirs(),
                "a Test.php file at the root is the only test root such a project has");
        assertEquals("none", info.attributes().get(PhpProjectDetector.ATTR_TEST_FRAMEWORK),
                "PHP has no standard-library test framework to fall back to");
        assertTrue(info.testFrameworks().isEmpty(),
                "'none' is the absence of a framework, not a framework named none");
    }

    @Test
    @DisplayName("a composer.json that does not parse is a warning, and the project is described from its tree")
    void reportsAnUnparsableManifest(@TempDir Path root) throws IOException {
        PhpTestContexts.write(root, "composer.json", "{ this is not json");
        PhpTestContexts.write(root, "app/Ledger.php", "<?php\n");

        assertTrue(PhpProjectDetector.looksLikePhpProject(root));
        ProjectInfo info = PhpProjectDetector.inspect(ProjectRef.of(root));

        assertTrue(info.attributes().get(PhpProjectDetector.ATTR_DETECTION_WARNING).contains("could not be parsed"),
                info.attributes().get(PhpProjectDetector.ATTR_DETECTION_WARNING));
        assertEquals(root.getFileName().toString(), info.modules().getFirst().name(),
                "with no readable name the project keeps its directory name rather than an invented one");
        assertEquals(BuildSystemId.COMPOSER, info.buildSystem(),
                "the file is there, so Composer is still the build system; only its contents are unreadable");
        assertEquals(root.resolve("app"), info.sourceDirs().getFirst());
    }

    @Test
    @DisplayName("phpFiles skips the vendor tree and the caches, which are somebody else's sources")
    void phpFilesSkipsVendorAndCaches(@TempDir Path root) throws IOException {
        PhpTestContexts.write(root, "src/Ledger.php", "<?php\n");
        PhpTestContexts.write(root, "vendor/acme/ledger/src/Ledger.php", "<?php\n");
        PhpTestContexts.write(root, "node_modules/pkg/index.php", "<?php\n");
        PhpTestContexts.write(root, ".phpunit.cache/test-results.php", "<?php\n");

        List<Path> files = PhpProjectDetector.phpFiles(root);

        assertEquals(1, files.size());
        assertTrue(files.getFirst().toString().endsWith("Ledger.php"));
        assertTrue(files.getFirst().toString().contains("src"));
    }

    @Test
    @DisplayName("a workspace under .codewitness is not excluded by its own run directory")
    void excludesRelativeToTheWalkedRoot(@TempDir Path root) throws IOException {
        Path workspace = root.resolve(".codewitness/runs/CW-TEST/workspace");
        PhpTestContexts.write(workspace, "Ledger.php", "<?php\n");
        PhpTestContexts.write(workspace, "vendor/acme/src/Dep.php", "<?php\n");

        // The run workspace legitimately lives under .codewitness; an absolute exclusion check would
        // reject every file in it, leaving analysis and mutation silently with nothing to do.
        assertEquals(1, PhpProjectDetector.phpFiles(workspace).size());
        assertFalse(PhpProjectDetector.isExcludedSource(workspace, workspace.resolve("Ledger.php")));
        assertTrue(PhpProjectDetector.isExcludedSource(workspace, workspace.resolve("vendor/acme/src/Dep.php")));
    }

    @Test
    @DisplayName("a .phtml template is PHP source, because the interpreter parses it as such")
    void countsPhtmlAsSource(@TempDir Path root) throws IOException {
        PhpTestContexts.write(root, "templates/page.phtml", "<html></html>\n");

        assertTrue(PhpProjectDetector.looksLikePhpProject(root));
        assertEquals(1, PhpProjectDetector.phpFiles(root).size());
    }

    @Test
    @DisplayName("an unrelated directory is not claimed as PHP")
    void rejectsNonPhpProject(@TempDir Path root) throws IOException {
        PhpTestContexts.write(root, "README.txt", "nothing to see\n");
        PhpTestContexts.write(root, "src/main/java/example/Ledger.java", "package example;\n");

        assertFalse(PhpProjectDetector.looksLikePhpProject(root));
        assertTrue(PhpProjectDetector.phpFiles(root).isEmpty());
    }
}
