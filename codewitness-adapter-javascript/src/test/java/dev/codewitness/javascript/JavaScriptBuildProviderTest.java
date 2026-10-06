package dev.codewitness.javascript;

import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The roots the syntax build hands to the checker.
 *
 * <p>These tests do not run Node: they pin the pure root selection, which is where a project without a
 * {@code src/} directory used to have its tests walked twice and reported twice.
 */
class JavaScriptBuildProviderTest {

    @Test
    @DisplayName("a test directory inside the source root is not checked twice")
    void doesNotCheckATestDirectoryTwice(@TempDir Path root) throws IOException {
        JavaScriptTestContexts.write(root, "index.js", "export const value = 1;\n");
        JavaScriptTestContexts.write(root, "tests/index.test.js", "// test\n");

        ProjectInfo info = JavaScriptProjectDetector.inspect(ProjectRef.of(root));

        // Without src/ the source root is the project root, which already contains tests/, so tests/
        // must not be passed as a second root.
        assertEquals(List.of(root), JavaScriptBuildProvider.checkRoots(info));
    }

    @Test
    @DisplayName("a separate src and tests layout passes both roots to the checker")
    void passesSeparateRoots(@TempDir Path root) throws IOException {
        JavaScriptTestContexts.write(root, "src/index.js", "export const value = 1;\n");
        JavaScriptTestContexts.write(root, "tests/index.test.js", "// test\n");

        ProjectInfo info = JavaScriptProjectDetector.inspect(ProjectRef.of(root));

        assertEquals(List.of(root.resolve("src"), root.resolve("tests")),
                JavaScriptBuildProvider.checkRoots(info));
    }

    @Test
    @DisplayName("a project with no root at all reports nothing to check")
    void reportsNoRootsWhenThereIsNothingToInspect(@TempDir Path root) {
        ProjectInfo empty = ProjectInfo.builder(ProjectRef.of(root)).build();
        assertEquals(List.of(), JavaScriptBuildProvider.checkRoots(empty));
    }
}
