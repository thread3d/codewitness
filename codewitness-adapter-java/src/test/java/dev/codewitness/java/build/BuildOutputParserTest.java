package dev.codewitness.java.build;

import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ModuleInfo;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Compiler diagnostics are read from the tool's own output, so their shapes are pinned here. */
class BuildOutputParserTest {

    @TempDir
    Path projectRoot;

    @Test
    void parsesMavenDiagnosticsAndMapsSeverity() {
        String output = """
                [INFO] Scanning for projects...
                [ERROR] /work/src/main/java/com/example/Foo.java:[12,34] cannot find symbol
                [WARNING] /work/src/main/java/com/example/Foo.java:[9,5] deprecated API
                [INFO] /work/src/main/java/com/example/Foo.java:[3,1] note about something
                [ERROR] Failed to execute goal
                """;
        Path relative = projectRoot.relativize(projectRoot.resolve("src/main/java/com/example/Foo.java"))
                .normalize();
        Path absolute = projectRoot.resolve(relative);
        String absoluteOutput = output.replace("/work", projectRoot.toString());

        List<CompilerDiagnostic> diagnostics = BuildOutputParser.parse(absoluteOutput, projectRoot);

        assertEquals(3, diagnostics.size(), diagnostics.toString());
        CompilerDiagnostic error = diagnostics.get(0);
        assertEquals(DiagnosticSeverity.ERROR, error.severity());
        assertEquals("src/main/java/com/example/Foo.java", error.file());
        assertEquals(12, error.line());
        assertEquals(34, error.column());
        assertEquals("cannot find symbol", error.message());
        assertEquals(DiagnosticSeverity.WARNING, diagnostics.get(1).severity());
        assertEquals(9, diagnostics.get(1).line());
        assertEquals(DiagnosticSeverity.NOTE, diagnostics.get(2).severity());
        assertEquals(absolute, error.resolve(projectRoot));
    }

    @Test
    void parsesJavacDiagnostics() {
        String output = projectRoot + "/src/main/java/Foo.java:12: error: ';' expected\n"
                + projectRoot + "/src/main/java/Foo.java:7: warning: unchecked cast\n"
                + projectRoot + "/src/main/java/Foo.java:2: note: some detail\n";

        List<CompilerDiagnostic> diagnostics = BuildOutputParser.parse(output, projectRoot);

        assertEquals(3, diagnostics.size(), diagnostics.toString());
        CompilerDiagnostic error = diagnostics.get(0);
        assertEquals(DiagnosticSeverity.ERROR, error.severity());
        assertEquals("src/main/java/Foo.java", error.file());
        assertEquals(12, error.line());
        assertNull(error.column(), "javac output carries no column on this shape");
        assertEquals("';' expected", error.message());
        assertEquals(DiagnosticSeverity.WARNING, diagnostics.get(1).severity());
        assertEquals(DiagnosticSeverity.NOTE, diagnostics.get(2).severity());
    }

    @Test
    void parsesGradleDiagnostics() {
        String output = "e: file://" + projectRoot + "/src/main/java/Foo.java:12:34 unresolved reference: x\n"
                + "w: file://" + projectRoot + "/src/main/java/Foo.java:3:1 unused variable\n"
                + "i: file://" + projectRoot + "/src/main/java/Foo.java:1:1 informational\n";

        List<CompilerDiagnostic> diagnostics = BuildOutputParser.parse(output, projectRoot);

        assertEquals(3, diagnostics.size(), diagnostics.toString());
        CompilerDiagnostic error = diagnostics.get(0);
        assertEquals(DiagnosticSeverity.ERROR, error.severity());
        assertEquals("src/main/java/Foo.java", error.file());
        assertEquals(12, error.line());
        assertEquals(34, error.column());
        assertEquals("unresolved reference: x", error.message());
        assertEquals(DiagnosticSeverity.WARNING, diagnostics.get(1).severity());
        assertEquals(DiagnosticSeverity.NOTE, diagnostics.get(2).severity());
    }

    @Test
    void keepsFilesOutsideTheProjectRootAsTheyWereReported() {
        List<CompilerDiagnostic> diagnostics = BuildOutputParser.parse(
                "/elsewhere/Foo.java:4: error: bad", projectRoot);

        assertEquals(1, diagnostics.size());
        assertEquals("/elsewhere/Foo.java", diagnostics.get(0).file());
    }

    @Test
    void removesDuplicateDiagnostics() {
        String line = projectRoot + "/Foo.java:12: error: same problem";
        List<CompilerDiagnostic> diagnostics = BuildOutputParser.parse(
                line + "\n" + line + "\n[ERROR] " + projectRoot + "/Foo.java:[12,4] same problem", projectRoot);

        assertEquals(1, diagnostics.size(),
                "the same file, line and message is one diagnostic: " + diagnostics);
    }

    @Test
    void ignoresNoiseAndNullOutput() {
        assertTrue(BuildOutputParser.parse(null, projectRoot).isEmpty());
        assertTrue(BuildOutputParser.parse("", projectRoot).isEmpty());
        assertTrue(BuildOutputParser.parse("""
                BUILD SUCCESS
                [INFO] Compiling 3 source files
                tests run: 5, failures: 0
                """, projectRoot).isEmpty());
    }

    @Test
    void selectsABuildSystemForKnownProjects() {
        assertInstanceOf(MavenBuildSystem.class, BuildOutputParser.forProject(project(BuildSystemId.MAVEN)));
        assertInstanceOf(GradleBuildSystem.class, BuildOutputParser.forProject(project(BuildSystemId.GRADLE)));
        assertNull(BuildOutputParser.forProject(project(BuildSystemId.NONE)));
    }

    private ProjectInfo project(BuildSystemId buildSystem) {
        return ProjectInfo.builder(ProjectRef.of(projectRoot))
                .language(LanguageId.JAVA)
                .buildSystem(buildSystem)
                .addModule(new ModuleInfo("sample", projectRoot, "jar", List.of(), List.of(), "17"))
                .build();
    }
}
