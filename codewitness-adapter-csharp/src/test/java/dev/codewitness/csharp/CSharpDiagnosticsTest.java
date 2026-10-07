package dev.codewitness.csharp;

import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the MSBuild and Roslyn diagnostic parser. */
class CSharpDiagnosticsTest {

    @Test
    @DisplayName("a positioned error carries its file, line, column, code and message")
    void parsesErrors(@TempDir Path project) {
        List<CompilerDiagnostic> diagnostics = CSharpDiagnostics.parse("""
                  Determining projects to restore...
                  /tmp/sample/src/Ledger/Ledger.cs(13,33): error CS1002: ; expected [/tmp/sample/src/Ledger/Ledger.csproj]
                  /tmp/sample/src/Ledger/Ledger.cs(13,37): error CS1001: Identifier expected [/tmp/sample/src/Ledger/Ledger.csproj]

                Build FAILED.

                    0 Warning(s)
                    2 Error(s)
                """, Path.of("/tmp/sample"));

        assertEquals(2, diagnostics.size());
        CompilerDiagnostic first = diagnostics.getFirst();
        assertEquals(DiagnosticSeverity.ERROR, first.severity());
        assertEquals("src/Ledger/Ledger.cs", first.file(), "a diagnostic inside the project is relative to it");
        assertEquals(13, first.line());
        assertEquals(33, first.column());
        assertEquals("; expected", first.message(), "the trailing [project] is not part of the message");
        assertEquals("CS1002", first.code());
    }

    @Test
    @DisplayName("a warning without a project suffix is parsed, and its severity is not an error")
    void parsesWarnings(@TempDir Path project) {
        List<CompilerDiagnostic> diagnostics = CSharpDiagnostics.parse(
                "src/Ledger/Ledger.cs(21,9): warning CS0219: The variable 'total' is assigned but its value is "
                        + "never used", project);

        assertEquals(1, diagnostics.size());
        assertEquals(DiagnosticSeverity.WARNING, diagnostics.getFirst().severity());
        assertEquals("CS0219", diagnostics.getFirst().code());
        assertEquals("The variable 'total' is assigned but its value is never used",
                diagnostics.getFirst().message());
    }

    @Test
    @DisplayName("a diagnostic outside the project keeps its absolute path rather than being dropped")
    void keepsPathsOutsideTheProject(@TempDir Path project) {
        List<CompilerDiagnostic> diagnostics = CSharpDiagnostics.parse(
                "/elsewhere/Shared.cs(4,2): error CS0103: The name 'x' does not exist in the current context",
                project);

        assertEquals(1, diagnostics.size());
        assertEquals("/elsewhere/Shared.cs", diagnostics.getFirst().file());
    }

    @Test
    @DisplayName("restore noise, audit warnings and the build summary are not diagnostics")
    void ignoresNoise(@TempDir Path project) {
        List<CompilerDiagnostic> diagnostics = CSharpDiagnostics.parse("""
                  Determining projects to restore...
                  Restored /tmp/sample/Ledger.csproj (in 73 ms).
                  /tmp/sample/Ledger.Tests/Ledger.Tests.csproj : warning NU1900: Error occurred while getting package vulnerability data
                  Failed!  - Failed:     1, Passed:     1, Skipped:     0, Total:     2, Duration: 8 ms
                  MSBUILD : error MSB1003: Specify a project or solution file.
                """, project);

        assertTrue(diagnostics.isEmpty(),
                "a line without a position is not a diagnostic this adapter can attribute to code");
    }

    @Test
    @DisplayName("a position with no message is skipped rather than reported as an empty finding")
    void skipsEmptyMessages(@TempDir Path project) {
        assertTrue(CSharpDiagnostics.parse("src/Ledger/Ledger.cs(1,1): error CS0000: ", project).isEmpty());
        assertTrue(CSharpDiagnostics.parse(null, project).isEmpty());
        assertTrue(CSharpDiagnostics.parse("", project).isEmpty());
    }
}
