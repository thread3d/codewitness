package dev.codewitness.kotlin;

import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Kotlin compiler writes diagnostics in its own format, and these tests pin every shape it is known to
 * produce. The formats are not invented: each one is what the tool actually prints, and the Maven one is
 * copied from a real {@code kotlin-maven-plugin} failure.
 */
class KotlinBuildOutputParserTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("the kotlin-maven-plugin format is read, path relativized and column kept")
   	void readsTheMavenPluginFormat() {
        Path root = tmp.resolve("project");
        String output = "[ERROR] file://" + root
                + "/src/main/kotlin/ledger/Broken.kt:4:23 Return type mismatch: expected 'Long', "
                + "actual 'String'.\n";

        List<CompilerDiagnostic> diagnostics = KotlinBuildOutputParser.parse(output, root);

        assertEquals(1, diagnostics.size(), diagnostics.toString());
        CompilerDiagnostic diagnostic = diagnostics.get(0);
        assertEquals(DiagnosticSeverity.ERROR, diagnostic.severity());
        assertEquals("src/main/kotlin/ledger/Broken.kt", diagnostic.file(),
                "the file must be reported relative to the project, as the report's other paths are");
        assertEquals(4, diagnostic.line());
        assertEquals(23, diagnostic.column());
        assertTrue(diagnostic.message().startsWith("Return type mismatch"), diagnostic.message());
    }

    @Test
    @DisplayName("the older bracketed position and the kotlinc plain format are both read")
    void readsTheOlderAndPlainFormats() {
        Path root = tmp.resolve("project");
        String output = """
                [ERROR] /proj/src/Ledger.kt: (12, 5): unresolved reference: balence
                [WARNING] /proj/src/Ledger.kt: (20, 9): variable is never used
                /proj/src/Other.kt:7:1: error: expecting an expression
                """;

        List<CompilerDiagnostic> diagnostics = KotlinBuildOutputParser.parse(output, root);

        assertEquals(3, diagnostics.size(), diagnostics.toString());
        assertEquals(12, diagnostics.get(0).line());
        assertEquals(5, diagnostics.get(0).column());
        assertEquals(DiagnosticSeverity.WARNING, diagnostics.get(1).severity());
        assertEquals(DiagnosticSeverity.ERROR, diagnostics.get(2).severity());
        assertTrue(diagnostics.get(2).message().contains("expecting an expression"), diagnostics.toString());
    }

    @Test
    @DisplayName("the Gradle and command line compiler format is read")
    void readsTheGradleFormat() {
        Path root = tmp.resolve("project");
        String output = """
                > Task :compileKotlin FAILED
                e: file:///proj/src/main/kotlin/Ledger.kt:9:17 unresolved reference: total
                w: file:///proj/src/main/kotlin/Ledger.kt:11:3 parameter is never used
                """;

        List<CompilerDiagnostic> diagnostics = KotlinBuildOutputParser.parse(output, root);

        assertEquals(2, diagnostics.size(), diagnostics.toString());
        assertEquals(DiagnosticSeverity.ERROR, diagnostics.get(0).severity());
        assertEquals("/proj/src/main/kotlin/Ledger.kt", diagnostics.get(0).file(),
                "a path outside the project root is kept as the tool wrote it rather than guessed at");
        assertEquals(DiagnosticSeverity.WARNING, diagnostics.get(1).severity());
    }

    @Test
    @DisplayName("the same diagnostic printed twice is reported once")
    void duplicatesAreCollapsed() {
        // Maven prints the compiler's line and then repeats it inside its own failure summary.
        String line = "[ERROR] file:///proj/src/Ledger.kt:3:1 unresolved reference: x";
        List<CompilerDiagnostic> diagnostics =
                KotlinBuildOutputParser.parse(line + "\n" + line + "\n", Path.of("/proj"));

        assertEquals(1, diagnostics.size(), diagnostics.toString());
    }

    @Test
    @DisplayName("ordinary build output is not mistaken for a diagnostic")
    void ordinaryOutputYieldsNothing() {
        String output = """
                [INFO] --- kotlin:2.2.20:compile (compile) @ probe ---
                [INFO] BUILD SUCCESS
                Downloading from central: https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.2.20/kotlin-stdlib-2.2.20.pom
                > Task :compileKotlin UP-TO-DATE
                """;

        assertTrue(KotlinBuildOutputParser.parse(output, Path.of("/proj")).isEmpty(),
                "a download line and a task banner are not compiler diagnostics");
    }

    @Test
    @DisplayName("a build result keeps the command, the exit code and the diagnostics")
    void buildResultCarriesDiagnostics() {
        var process = new dev.codewitness.core.exec.ProcessResult(
                List.of("mvn", "test-compile"), Path.of("/proj"), true, 1, "", "",
                java.time.Duration.ofSeconds(2), false, null);

        var result = KotlinBuildOutputParser.toBuildResult(process, Path.of("/proj"));

        assertTrue(result.attempted());
        assertFalse(result.success());
        assertEquals(List.of("mvn", "test-compile"), result.command());
        assertEquals(1, result.exitCode());
    }

    @Test
    @DisplayName("Maven and Gradle are the two build systems this adapter drives")
    void buildSystemsAreResolvedByBuildSystemId() {
        assertEquals("maven", buildSystemId("maven"));
        assertEquals("gradle", buildSystemId("gradle"));
        org.junit.jupiter.api.Assertions.assertNull(
                KotlinBuildOutputParser.forProject(projectWithBuildSystem("none")),
                "a project with no build system has nothing to drive");
    }

    private static String buildSystemId(String id) {
        return KotlinBuildOutputParser.forProject(projectWithBuildSystem(id)).id();
    }

    private static dev.codewitness.core.model.ProjectInfo projectWithBuildSystem(String id) {
        return dev.codewitness.core.model.ProjectInfo.builder(
                        dev.codewitness.core.model.ProjectRef.of(Path.of(".").toAbsolutePath()))
                .language(dev.codewitness.core.model.LanguageId.KOTLIN)
                .buildSystem(dev.codewitness.core.model.BuildSystemId.of(id))
                .build();
    }
}
