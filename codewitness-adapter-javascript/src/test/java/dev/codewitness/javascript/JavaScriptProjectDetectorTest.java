package dev.codewitness.javascript;

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

/** Detection of Node/JavaScript projects and their layout. */
class JavaScriptProjectDetectorTest {

    @Test
    @DisplayName("a JavaScript project is detected with its package name and directories")
    void detectsJavaScriptProject(@TempDir Path root) throws IOException {
        JavaScriptTestContexts.write(root, "package.json", """
                {
                  "name": "sample-app",
                  "type": "module",
                  "packageManager": "pnpm@9.1.0",
                  "dependencies": {"left-pad": "1.0.0"},
                  "devDependencies": {"eslint": "9.0.0"}
                }
                """);
        JavaScriptTestContexts.write(root, "src/ledger.js", "export const value = 1;\n");
        JavaScriptTestContexts.write(root, "tests/ledger.test.js", "// test\n");

        ProjectInfo info = JavaScriptProjectDetector.inspect(ProjectRef.of(root));

        assertEquals(LanguageId.JAVASCRIPT, info.language());
        assertEquals(BuildSystemId.NODE, info.buildSystem());
        assertEquals("sample-app", info.attributes().get(JavaScriptProjectDetector.ATTR_PACKAGE_NAME));
        assertEquals("pnpm", info.attributes().get(JavaScriptProjectDetector.ATTR_PACKAGE_MANAGER));
        assertEquals("module", info.attributes().get(JavaScriptProjectDetector.ATTR_MODULE_TYPE));
        assertEquals("cjs,js,mjs", info.attributes().get(JavaScriptProjectDetector.ATTR_SOURCE_EXTENSIONS));
        assertEquals(1, info.sourceDirs().size());
        assertEquals(1, info.testSourceDirs().size());
        assertTrue(info.sourceExtensions().contains("js"));
        assertTrue(info.sourceExtensions().contains("mjs"));
        assertTrue(info.sourceExtensions().contains("cjs"));
        assertTrue(info.testFrameworks().contains("node:test"));
        assertTrue(JavaScriptProjectDetector.looksLikeJavaScriptProject(root));
    }

    @Test
    @DisplayName("a TypeScript project is not claimed: the TypeScript adapter can type check it")
    void rejectsTypeScriptProject(@TempDir Path root) throws IOException {
        JavaScriptTestContexts.write(root, "package.json", "{\"name\": \"typed\"}\n");
        JavaScriptTestContexts.write(root, "tsconfig.json", "{\"compilerOptions\": {}}\n");
        JavaScriptTestContexts.write(root, "src/index.js", "export const value = 1;\n");

        assertFalse(JavaScriptProjectDetector.looksLikeJavaScriptProject(root));
    }

    @Test
    @DisplayName("a lone TypeScript source is enough to leave the project to the TypeScript adapter")
    void rejectsTypeScriptSources(@TempDir Path root) throws IOException {
        JavaScriptTestContexts.write(root, "package.json", "{\"name\": \"typed\"}\n");
        JavaScriptTestContexts.write(root, "src/index.js", "export const value = 1;\n");
        JavaScriptTestContexts.write(root, "src/types.ts", "export type T = number;\n");

        assertFalse(JavaScriptProjectDetector.looksLikeJavaScriptProject(root));
    }

    @Test
    @DisplayName("a bare JavaScript directory with no manifest and no test is described but not claimed")
    void describesBareJavaScriptSources(@TempDir Path root) throws IOException {
        JavaScriptTestContexts.write(root, "src/main.js", "export const value = 1;\n");

        ProjectInfo info = JavaScriptProjectDetector.inspect(ProjectRef.of(root));

        assertEquals(LanguageId.JAVASCRIPT, info.language());
        assertEquals(BuildSystemId.NONE, info.buildSystem());
        assertEquals("none", info.attributes().get(JavaScriptProjectDetector.ATTR_DESCRIPTOR));
        assertTrue(info.attributes().containsKey(JavaScriptProjectDetector.ATTR_DETECTION_WARNING));
        assertFalse(JavaScriptProjectDetector.looksLikeJavaScriptProject(root),
                "with no manifest and no test there is nothing to build or run");
    }

    @Test
    @DisplayName("a bare JavaScript directory with a Node test file is claimed without a manifest")
    void detectsBareJavaScriptTests(@TempDir Path root) throws IOException {
        JavaScriptTestContexts.write(root, "tests/main.test.js", "// test\n");

        assertTrue(JavaScriptProjectDetector.looksLikeJavaScriptProject(root));
        ProjectInfo info = JavaScriptProjectDetector.inspect(ProjectRef.of(root));
        assertEquals(LanguageId.JAVASCRIPT, info.language());
        assertEquals(1, info.testSourceDirs().size());
    }

    @Test
    @DisplayName("a declared third-party test runner is reported, not silently used")
    void reportsThirdPartyTestRunner(@TempDir Path root) throws IOException {
        JavaScriptTestContexts.write(root, "package.json", """
                {"name": "app", "devDependencies": {"vitest": "2.0.0"}}
                """);
        JavaScriptTestContexts.write(root, "src/main.js", "export const value = 1;\n");
        JavaScriptTestContexts.write(root, "tests/main.test.js", "// test\n");

        ProjectInfo info = JavaScriptProjectDetector.inspect(ProjectRef.of(root));

        assertEquals("vitest,node:test", info.attributes().get(JavaScriptProjectDetector.ATTR_DETECTED_TEST_FRAMEWORKS));
        assertTrue(info.attributes().get(JavaScriptProjectDetector.ATTR_DETECTION_WARNING).contains("vitest"));
    }

    @Test
    @DisplayName("build output and dependencies are never treated as source")
    void excludesBuildOutputAndDependencies(@TempDir Path root) throws IOException {
        JavaScriptTestContexts.write(root, "src/ledger.js", "export const value = 1;\n");
        JavaScriptTestContexts.write(root, "dist/ledger.js", "export const value = 1;\n");
        JavaScriptTestContexts.write(root, "node_modules/dep/index.js", "module.exports = 1;\n");

        assertEquals(1, JavaScriptProjectDetector.javascriptFiles(root).size());
    }

    @Test
    @DisplayName("a workspace under .codewitness is not excluded by its own run directory")
    void excludesRelativeToTheWalkedRoot(@TempDir Path root) throws IOException {
        Path workspace = root.resolve(".codewitness/runs/CW-TEST/workspace");
        JavaScriptTestContexts.write(workspace, "src/ledger.js", "export const value = 1;\n");
        JavaScriptTestContexts.write(workspace, "tests/ledger.test.js", "// test\n");
        JavaScriptTestContexts.write(workspace, "node_modules/dep/index.js", "module.exports = 1;\n");

        // The run workspace legitimately lives under .codewitness; an absolute exclusion check would
        // reject every file in it and silently find no sources.
        assertEquals(1, JavaScriptProjectDetector.javascriptFiles(workspace.resolve("src")).size());
        assertEquals(1, JavaScriptProjectDetector.testFiles(workspace.resolve("tests")).size());
        assertEquals(2, JavaScriptProjectDetector.javascriptFiles(workspace).size(),
                "both the source and the test file count as JavaScript, but node_modules does not");
    }

    @Test
    @DisplayName("test files are recognised by Node's test and spec naming convention")
    void recognisesTestFileNames() {
        assertTrue(JavaScriptProjectDetector.isTestFileName("ledger.test.js"));
        assertTrue(JavaScriptProjectDetector.isTestFileName("ledger.spec.mjs"));
        assertTrue(JavaScriptProjectDetector.isTestFileName("ledger.test.cjs"));
        assertFalse(JavaScriptProjectDetector.isTestFileName("ledger.test.jsx"),
                ".jsx is a transformed extension the runner cannot execute unchanged");
        assertFalse(JavaScriptProjectDetector.isTestFileName("ledger.js"));
        assertFalse(JavaScriptProjectDetector.isTestFileName("contest.js"));
    }
}
