package dev.codewitness.typescript;

import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Parsing of tsc output and of the runtime syntax checker's diagnostic lines. */
class TypeScriptDiagnosticsTest {

    @Test
    @DisplayName("tsc's located and global diagnostics are read with file, line and code")
    void parsesTscOutput() {
        String output = """
                src/ledger.ts(17,5): error TS2322: Type 'string' is not assignable to type 'number'.
                src/ledger.ts(42,9): warning TS6133: 'total' is declared but its value is never read.
                error TS5058: The specified path does not exist: 'missing.ts'.
                """;

        List<CompilerDiagnostic> diagnostics = TypeScriptDiagnostics.parseTsc(output, Path.of("/p"));

        assertEquals(3, diagnostics.size());
        CompilerDiagnostic first = diagnostics.getFirst();
        assertEquals(DiagnosticSeverity.ERROR, first.severity());
        assertEquals("src/ledger.ts", first.file());
        assertEquals(17, first.line());
        assertEquals(5, first.column());
        assertEquals("TS2322", first.code());
        assertEquals(DiagnosticSeverity.WARNING, diagnostics.get(1).severity());
        assertNull(diagnostics.get(2).file(), "a global error has no file and must not be attached to one");
    }

    @Test
    @DisplayName("the runtime syntax checker's CWDIAG lines become diagnostics")
    void parsesHelperOutput() {
        String output = """
                CWDIAG|ERROR|/p/src/broken.ts|0|0|Expression expected
                CWSYNTAX|checked 3 file(s), 1 failed, 1 skipped
                """;

        List<CompilerDiagnostic> diagnostics = TypeScriptDiagnostics.parseHelper(output, Path.of("/p"));

        assertEquals(1, diagnostics.size());
        assertEquals(DiagnosticSeverity.ERROR, diagnostics.getFirst().severity());
        assertEquals("src/broken.ts", diagnostics.getFirst().file());
        assertNull(diagnostics.getFirst().line(), "a zero line means the checker did not report one");
        assertEquals("Expression expected", diagnostics.getFirst().message());
        assertEquals("checked 3 file(s), 1 failed, 1 skipped", TypeScriptDiagnostics.syntaxSummary(output));
    }

    @Test
    @DisplayName("an absolute diagnostic path inside the project is relativised")
    void relativisesAbsolutePaths() {
        assertEquals("src/ledger.ts",
                TypeScriptDiagnostics.relativize(Path.of("/p"), "/p/src/ledger.ts"));
        assertEquals("/elsewhere/ledger.ts",
                TypeScriptDiagnostics.relativize(Path.of("/p"), "/elsewhere/ledger.ts"));
    }
}
