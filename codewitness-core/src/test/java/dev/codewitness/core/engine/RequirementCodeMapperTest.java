package dev.codewitness.core.engine;

import dev.codewitness.core.model.ModuleInfo;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.RequirementSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mapping from requirements to code is a fact about the source tree - an id appearing in a file,
 * or an explicit declaration - never a guess. Anything unmapped stays unmapped and is reported.
 */
class RequirementCodeMapperTest {

    private static ProjectInfo project(Path root) {
        return ProjectInfo.builder(ProjectRef.of(root))
                .addModule(new ModuleInfo("root", root, "jar",
                        List.of(root.resolve("src/main/java")),
                        List.of(root.resolve("src/test/java")), null))
                .build();
    }

    private static RequirementSet requirements(Requirement... requirements) {
        return new RequirementSet(List.of(requirements));
    }

    private static Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("a test class whose source mentions R-001 maps to it")
    void testClassMentioningRequirementMapsToIt(@TempDir Path root) throws IOException {
        write(root.resolve("src/test/java/com/example/FooTest.java"),
                "package com.example;\n// Verifies R-001\nclass FooTest {}\n");

        RequirementCodeMapper mapper = RequirementCodeMapper.build(project(root),
                requirements(Requirement.builder("R-001", "text").build()));

        assertEquals(List.of("com.example.FooTest"), mapper.testClassesFor("R-001"));
        assertEquals(Optional.of("R-001"), mapper.requirementForTestClass("com.example.FooTest"));
        assertTrue(mapper.hasAnyMapping());
    }

    @Test
    @DisplayName("a test class may be looked up by simple name too")
    void testClassLookupAcceptsSimpleName(@TempDir Path root) throws IOException {
        write(root.resolve("src/test/java/com/example/FooTest.java"), "// R-001\nclass FooTest {}");

        RequirementCodeMapper mapper = RequirementCodeMapper.build(project(root),
                requirements(Requirement.builder("R-001", "text").build()));

        assertEquals(Optional.of("R-001"), mapper.requirementForTestClass("FooTest"));
    }

    @Test
    @DisplayName("the mention match ignores case")
    void mentionMatchIsCaseInsensitive(@TempDir Path root) throws IOException {
        write(root.resolve("src/test/java/com/example/FooTest.java"), "// covers r-001\nclass FooTest {}");

        RequirementCodeMapper mapper = RequirementCodeMapper.build(project(root),
                requirements(Requirement.builder("R-001", "text").build()));

        assertEquals(List.of("com.example.FooTest"), mapper.testClassesFor("R-001"));
    }

    @Test
    @DisplayName("explicit tests: and paths: attributes map without scanning")
    void explicitDeclarationsMap(@TempDir Path root) {
        Requirement declared = Requirement.builder("R-002", "text")
                .attribute("tests", "com.example.DeclaredTest")
                .attribute("paths", "src/main/java/com/example/Declared.java")
                .build();

        RequirementCodeMapper mapper = RequirementCodeMapper.build(project(root), requirements(declared));

        assertEquals(List.of("com.example.DeclaredTest"), mapper.testClassesFor("R-002"));
        assertEquals(Optional.of("R-002"), mapper.requirementForTestClass("com.example.DeclaredTest"));
        assertTrue(mapper.requirementsForFile("src/main/java/com/example/Declared.java").contains("R-002"));
    }

    @Test
    @DisplayName("a mutation class name such as com.example.Foo maps to src/main/java/com/example/Foo.java")
    void mutationClassNameMapsToSourcePath(@TempDir Path root) {
        // MutationStrategy turns a mutant's class name into a slash-separated path and asks which
        // requirement covers that file. The suffix match against the declared source path is the link.
        Requirement declared = Requirement.builder("R-001", "text")
                .attribute("paths", "src/main/java/com/example/Foo.java")
                .build();

        RequirementCodeMapper mapper = RequirementCodeMapper.build(project(root), requirements(declared));

        assertTrue(mapper.requirementsForFile("com/example/Foo.java").contains("R-001"),
                "a mutation in com.example.Foo belongs to the requirement covering Foo.java");
    }

    @Test
    @DisplayName("a source file that mentions the id is mapped by scanning")
    void scannedSourceFileMapsToRequirement(@TempDir Path root) throws IOException {
        write(root.resolve("src/main/java/com/example/Bar.java"),
                "package com.example;\n// implements R-003\nclass Bar {}\n");

        RequirementCodeMapper mapper = RequirementCodeMapper.build(project(root),
                requirements(Requirement.builder("R-003", "text").build()));

        assertTrue(mapper.requirementsForFile("com/example/Bar.java").contains("R-003"));
        assertTrue(mapper.requirementsForFile("src/main/java/com/example/Bar.java").contains("R-003"));
    }

    @Test
    @DisplayName("a test class that mentions no requirement is listed as unmapped")
    void unmappedTestFilesAreListed(@TempDir Path root) throws IOException {
        write(root.resolve("src/test/java/com/example/MappedTest.java"), "// R-001\nclass MappedTest {}");
        write(root.resolve("src/test/java/com/example/UnmappedTest.java"), "class UnmappedTest {}");

        RequirementCodeMapper mapper = RequirementCodeMapper.build(project(root),
                requirements(Requirement.builder("R-001", "text").build()));

        assertEquals(List.of("UnmappedTest.java"), mapper.unmappedTestFiles());
    }

    @Test
    @DisplayName("requirementsForFile tolerates null and unknown paths")
    void requirementsForFileToleratesEdges(@TempDir Path root) {
        Requirement declared = Requirement.builder("R-002", "text")
                .attribute("paths", "src/main/java/com/example/Declared.java")
                .build();
        RequirementCodeMapper mapper = RequirementCodeMapper.build(project(root), requirements(declared));

        assertTrue(mapper.requirementsForFile(null).isEmpty());
        assertTrue(mapper.requirementsForFile("com/example/Unrelated.java").isEmpty());
    }

    @Test
    @DisplayName("testClassesFor an unknown requirement is empty")
    void unknownRequirementHasNoTestClasses(@TempDir Path root) throws IOException {
        write(root.resolve("src/test/java/com/example/FooTest.java"), "// R-001\nclass FooTest {}");
        RequirementCodeMapper mapper = RequirementCodeMapper.build(project(root),
                requirements(Requirement.builder("R-001", "text").build()));

        assertEquals(List.of(), mapper.testClassesFor("R-999"));
    }

    @Test
    @DisplayName("requirementForTestClass returns empty for null and unknown classes")
    void requirementForUnknownClassIsEmpty(@TempDir Path root) throws IOException {
        write(root.resolve("src/test/java/com/example/FooTest.java"), "// R-001\nclass FooTest {}");
        RequirementCodeMapper mapper = RequirementCodeMapper.build(project(root),
                requirements(Requirement.builder("R-001", "text").build()));

        assertEquals(Optional.empty(), mapper.requirementForTestClass(null));
        assertEquals(Optional.empty(), mapper.requirementForTestClass("com.example.Nothing"));
    }

    @Test
    @DisplayName("an empty project produces no mapping at all")
    void emptyProjectHasNoMapping(@TempDir Path root) {
        RequirementCodeMapper mapper = RequirementCodeMapper.build(project(root),
                requirements(Requirement.builder("R-001", "text").build()));

        assertFalse(mapper.hasAnyMapping());
        assertEquals(List.of(), mapper.testClassesFor("R-001"));
        assertEquals(List.of(), mapper.unmappedTestFiles());
    }

    @Test
    @DisplayName("a requirement with several explicit tests maps to all of them")
    void severalExplicitTestsMapSeparately(@TempDir Path root) {
        Requirement declared = Requirement.builder("R-004", "text")
                .attribute("tests", "com.example.OneTest, com.example.TwoTest")
                .build();

        RequirementCodeMapper mapper = RequirementCodeMapper.build(project(root), requirements(declared));

        assertEquals(List.of("com.example.OneTest", "com.example.TwoTest"), mapper.testClassesFor("R-004"));
        assertEquals(Optional.of("R-004"), mapper.requirementForTestClass("com.example.OneTest"));
        assertEquals(Optional.of("R-004"), mapper.requirementForTestClass("com.example.TwoTest"));
    }

    @Test
    @DisplayName("a file mentioning two requirements maps to both")
    void oneFileMayMapToSeveralRequirements(@TempDir Path root) throws IOException {
        write(root.resolve("src/test/java/com/example/BothTest.java"), "// R-001 and R-002\nclass BothTest {}");

        RequirementCodeMapper mapper = RequirementCodeMapper.build(project(root),
                requirements(Requirement.builder("R-001", "text").build(),
                        Requirement.builder("R-002", "text").build()));

        assertTrue(mapper.testClassesFor("R-001").contains("com.example.BothTest"));
        assertTrue(mapper.testClassesFor("R-002").contains("com.example.BothTest"));
    }

    @Test
    @DisplayName("a TypeScript test file maps to the suite name Node reports for it")
    void typeScriptTestFileMapsToSuiteName(@TempDir Path root) throws IOException {
        ProjectInfo typeScriptProject = ProjectInfo.builder(ProjectRef.of(root))
                .sourceExtensions(java.util.Set.of("ts", "tsx", "mts", "cts"))
                .addModule(new ModuleInfo("app", root, "module",
                        List.of(root.resolve("src")), List.of(root.resolve("tests")), null))
                .build();
        write(root.resolve("tests/ledger.test.ts"), "// covers R-001\ntest('x', () => {});\n");

        RequirementCodeMapper mapper = RequirementCodeMapper.build(typeScriptProject,
                requirements(Requirement.builder("R-001", "text").build()));

        // Node reports the suite as the file stem without its extension or its .test marker, so the
        // two rules have to agree for an executed test to be attributed to its requirement.
        assertEquals(List.of("ledger"), mapper.testClassesFor("R-001"));
        assertEquals(Optional.of("R-001"), mapper.requirementForTestClass("ledger"));
    }

    @Test
    @DisplayName("a JavaScript test file maps to the suite name Node reports for it")
    void javaScriptTestFileMapsToSuiteName(@TempDir Path root) throws IOException {
        ProjectInfo javaScriptProject = ProjectInfo.builder(ProjectRef.of(root))
                .sourceExtensions(java.util.Set.of("js", "mjs", "cjs"))
                .addModule(new ModuleInfo("app", root, "module",
                        List.of(root.resolve("src")), List.of(root.resolve("tests")), null))
                .build();
        // The .test.mjs name is what the JavaScript adapter's generated tests and hand-written ES
        // module tests use; Node reports the suite as the stem without extension or marker.
        write(root.resolve("tests/ledger.test.mjs"), "// covers R-001\ntest('x', () => {});\n");

        RequirementCodeMapper mapper = RequirementCodeMapper.build(javaScriptProject,
                requirements(Requirement.builder("R-001", "text").build()));

        assertEquals(List.of("ledger"), mapper.testClassesFor("R-001"));
        assertEquals(Optional.of("R-001"), mapper.requirementForTestClass("ledger"));
    }
}
