package dev.codewitness.typescript;

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

/** Detection of Node/TypeScript projects and their layout. */
class TypeScriptProjectDetectorTest {

    @Test
    @DisplayName("a TypeScript project is detected with its package name and directories")
    void detectsTypeScriptProject(@TempDir Path root) throws IOException {
        TypeScriptTestContexts.write(root, "package.json", """
                {
                  "name": "sample-app",
                  "type": "module",
                  "packageManager": "pnpm@9.1.0",
                  "dependencies": {"left-pad": "1.0.0"},
                  "devDependencies": {"typescript": "5.5.0"}
                }
                """);
        TypeScriptTestContexts.write(root, "tsconfig.json", "{\"compilerOptions\": {}}\n");
        TypeScriptTestContexts.write(root, "src/ledger.ts", "export const value = 1;\n");
        TypeScriptTestContexts.write(root, "tests/ledger.test.ts", "// test\n");

        ProjectInfo info = TypeScriptProjectDetector.inspect(ProjectRef.of(root));

        assertEquals(LanguageId.TYPESCRIPT, info.language());
        assertEquals(BuildSystemId.NODE, info.buildSystem());
        assertEquals("sample-app", info.attributes().get(TypeScriptProjectDetector.ATTR_PACKAGE_NAME));
        assertEquals("pnpm", info.attributes().get(TypeScriptProjectDetector.ATTR_PACKAGE_MANAGER));
        assertEquals("module", info.attributes().get(TypeScriptProjectDetector.ATTR_MODULE_TYPE));
        assertEquals(1, info.sourceDirs().size());
        assertEquals(1, info.testSourceDirs().size());
        assertTrue(info.sourceExtensions().contains("ts"));
        assertTrue(info.sourceExtensions().contains("tsx"));
        assertTrue(info.testFrameworks().contains("node:test"));
    }

    @Test
    @DisplayName("a plain JavaScript package is not claimed: CodeWitness cannot type check it")
    void rejectsJavaScriptPackage(@TempDir Path root) throws IOException {
        TypeScriptTestContexts.write(root, "package.json", "{\"name\": \"plain-js\"}\n");
        TypeScriptTestContexts.write(root, "src/index.js", "module.exports = 1;\n");

        assertFalse(TypeScriptProjectDetector.looksLikeTypeScriptProject(root));
    }

    @Test
    @DisplayName("a directory with TypeScript sources but no manifest is still detected")
    void detectsBareTypeScriptSources(@TempDir Path root) throws IOException {
        TypeScriptTestContexts.write(root, "src/main.ts", "export const value: number = 1;\n");

        ProjectInfo info = TypeScriptProjectDetector.inspect(ProjectRef.of(root));

        assertEquals(LanguageId.TYPESCRIPT, info.language());
        assertEquals(BuildSystemId.NONE, info.buildSystem());
        assertEquals("none", info.attributes().get(TypeScriptProjectDetector.ATTR_DESCRIPTOR));
        assertTrue(info.attributes().containsKey(TypeScriptProjectDetector.ATTR_DETECTION_WARNING));
        assertTrue(TypeScriptProjectDetector.looksLikeTypeScriptProject(root));
    }

    @Test
    @DisplayName("a declared third-party test runner is reported, not silently used")
    void reportsThirdPartyTestRunner(@TempDir Path root) throws IOException {
        TypeScriptTestContexts.write(root, "package.json", """
                {"name": "app", "devDependencies": {"vitest": "2.0.0"}}
                """);
        TypeScriptTestContexts.write(root, "src/main.ts", "export const value = 1;\n");
        TypeScriptTestContexts.write(root, "tests/main.test.ts", "// test\n");

        ProjectInfo info = TypeScriptProjectDetector.inspect(ProjectRef.of(root));

        assertEquals("vitest,node:test", info.attributes().get(TypeScriptProjectDetector.ATTR_DETECTED_TEST_FRAMEWORKS));
        assertTrue(info.attributes().get(TypeScriptProjectDetector.ATTR_DETECTION_WARNING).contains("vitest"));
    }

    @Test
    @DisplayName("build output and dependencies are never treated as source")
    void excludesBuildOutputAndDependencies(@TempDir Path root) throws IOException {
        TypeScriptTestContexts.write(root, "src/ledger.ts", "export const value = 1;\n");
        TypeScriptTestContexts.write(root, "dist/ledger.ts", "export const value = 1;\n");
        TypeScriptTestContexts.write(root, "node_modules/dep/index.ts", "export const dep = 1;\n");

        assertEquals(1, TypeScriptProjectDetector.typeScriptFiles(root).size());
    }

    @Test
    @DisplayName("a workspace under .codewitness is not excluded by its own run directory")
    void excludesRelativeToTheWalkedRoot(@TempDir Path root) throws IOException {
        Path workspace = root.resolve(".codewitness/runs/CW-TEST/workspace");
        TypeScriptTestContexts.write(workspace, "src/ledger.ts", "export const value = 1;\n");
        TypeScriptTestContexts.write(workspace, "tests/ledger.test.ts", "// test\n");
        TypeScriptTestContexts.write(workspace, "node_modules/dep/index.ts", "export const dep = 1;\n");

        // The run workspace legitimately lives under .codewitness; an absolute exclusion check would
        // reject every file in it and silently find no sources.
        assertEquals(1, TypeScriptProjectDetector.typeScriptFiles(workspace.resolve("src")).size());
        assertEquals(1, TypeScriptProjectDetector.testFiles(workspace.resolve("tests")).size());
        assertEquals(2, TypeScriptProjectDetector.typeScriptFiles(workspace).size(),
                "both the source and the test file count as TypeScript, but node_modules does not");
    }

    @Test
    @DisplayName("test files are recognised by Node's test and spec naming convention")
    void recognisesTestFileNames() {
        assertTrue(TypeScriptProjectDetector.isTestFileName("ledger.test.ts"));
        assertTrue(TypeScriptProjectDetector.isTestFileName("ledger.spec.mts"));
        assertTrue(TypeScriptProjectDetector.isTestFileName("ledger.test.tsx"));
        assertFalse(TypeScriptProjectDetector.isTestFileName("ledger.ts"));
        assertFalse(TypeScriptProjectDetector.isTestFileName("contest.ts"));
    }
}
