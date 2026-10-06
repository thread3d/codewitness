package dev.codewitness.go;

import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Detection of Go modules and their package layout. */
class GoProjectDetectorTest {

    @Test
    @DisplayName("a Go module is detected with its module path, language version and package tree")
    void detectsGoModule(@TempDir Path root) throws IOException {
        GoTestContexts.write(root, "go.mod", """
                module codewitness.local/ledger

                go 1.21

                require example.com/dep v1.2.3
                """);
        GoTestContexts.write(root, "ledger.go", "package ledger\n\nfunc Value() int64 { return 1 }\n");
        GoTestContexts.write(root, "ledger_test.go", "package ledger\n\nimport \"testing\"\n\nfunc TestX(t *testing.T) {}\n");
        GoTestContexts.write(root, "sub/util.go", "package sub\n\nfunc Util() {}\n");

        ProjectInfo info = GoProjectDetector.inspect(ProjectRef.of(root));

        assertEquals(LanguageId.GO, info.language());
        assertEquals(BuildSystemId.GO, info.buildSystem());
        assertEquals("codewitness.local/ledger", info.attributes().get(GoProjectDetector.ATTR_MODULE));
        assertEquals("1.21", info.attributes().get(GoProjectDetector.ATTR_GO_VERSION));
        assertEquals("ledger", info.attributes().get(GoProjectDetector.ATTR_PACKAGE));
        assertTrue(info.sourceExtensions().contains("go"));
        assertTrue(info.testFrameworks().contains("testing"));
        assertTrue(info.hasTests());
        assertEquals(1, info.sourceDirs().size());
        assertEquals(1, info.testSourceDirs().size());
    }

    @Test
    @DisplayName("Go sources without a manifest are detected but cannot be built")
    void detectsBareGoSourcesWithAWarning(@TempDir Path root) throws IOException {
        GoTestContexts.write(root, "main.go", "package main\n\nfunc main() {}\n");

        ProjectInfo info = GoProjectDetector.inspect(ProjectRef.of(root));

        assertEquals(LanguageId.GO, info.language());
        assertEquals(BuildSystemId.NONE, info.buildSystem());
        assertEquals("none", info.attributes().get(GoProjectDetector.ATTR_DESCRIPTOR));
        assertTrue(info.attributes().containsKey(GoProjectDetector.ATTR_DETECTION_WARNING));
        assertTrue(GoProjectDetector.looksLikeGoProject(root));
    }

    @Test
    @DisplayName("a directory with neither manifest nor Go sources is not a Go project")
    void rejectsNonGoProject(@TempDir Path root) throws IOException {
        GoTestContexts.write(root, "README.md", "nothing to see\n");

        assertFalse(GoProjectDetector.looksLikeGoProject(root));
    }

    @Test
    @DisplayName("the module path is read from go.mod")
    void readsModulePath(@TempDir Path root) throws IOException {
        GoTestContexts.write(root, "go.mod", "module example.com/acme/thing\n\ngo 1.22\n");

        assertEquals("example.com/acme/thing", GoProjectDetector.detectModule(root));
        assertEquals("1.22", GoProjectDetector.detectGoVersion(root));
    }

    @Test
    @DisplayName("vendored code and testdata are never treated as source")
    void excludesVendorAndTestdata(@TempDir Path root) throws IOException {
        GoTestContexts.write(root, "ledger.go", "package ledger\n");
        GoTestContexts.write(root, "vendor/example.com/dep/dep.go", "package dep\n");
        GoTestContexts.write(root, "testdata/golden.go", "package testdata\n");

        assertEquals(1, GoProjectDetector.goFiles(root).size());
    }

    @Test
    @DisplayName("the package clause is read from the package directory")
    void readsPackageClause(@TempDir Path root) throws IOException {
        GoTestContexts.write(root, "ledger.go", "package ledger\n");

        assertEquals("ledger", GoProjectDetector.packageName(root));
    }
}
