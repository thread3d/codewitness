package dev.codewitness.javascript;

import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing of the runtime syntax checker's diagnostic lines. */
class JavaScriptDiagnosticsTest {

    @Test
    @DisplayName("the runtime syntax checker's CWDIAG lines become diagnostics")
    void parsesHelperOutput() {
        String output = """
                CWDIAG|ERROR|/p/src/broken.js|0|0|Unexpected token
                CWDIAG|WARNING|/p/src/odd.mjs|12|4|Import is never used
                CWSYNTAX|checked 3 file(s), 1 failed, 1 skipped
                """;

        List<CompilerDiagnostic> diagnostics = JavaScriptDiagnostics.parseHelper(output, Path.of("/p"));

        assertEquals(2, diagnostics.size());
        CompilerDiagnostic first = diagnostics.getFirst();
        assertEquals(DiagnosticSeverity.ERROR, first.severity());
        assertEquals("src/broken.js", first.file());
        assertNull(first.line(), "a zero line means the checker did not report one");
        assertEquals("Unexpected token", first.message());
        CompilerDiagnostic second = diagnostics.get(1);
        assertEquals(DiagnosticSeverity.WARNING, second.severity());
        assertEquals("src/odd.mjs", second.file());
        assertEquals(12, second.line());
        assertEquals(4, second.column());
        assertEquals("Import is never used", second.message());
        assertEquals("checked 3 file(s), 1 failed, 1 skipped", JavaScriptDiagnostics.syntaxSummary(output));
    }

    @Test
    @DisplayName("an unrecognised line is ignored rather than attached to the wrong file")
    void ignoresUnrecognisedLines() {
        assertTrue(JavaScriptDiagnostics.parseHelper("noise\nCWDIAG|ERROR|/p/src/x.js", Path.of("/p")).isEmpty());
        assertTrue(JavaScriptDiagnostics.parseHelper(null, Path.of("/p")).isEmpty());
        assertNull(JavaScriptDiagnostics.syntaxSummary("no summary here"));
        assertNull(JavaScriptDiagnostics.syntaxSummary(null));
    }

    @Test
    @DisplayName("an absolute diagnostic path inside the project is relativised")
    void relativisesAbsolutePaths() {
        assertEquals("src/ledger.js",
                JavaScriptDiagnostics.relativize(Path.of("/p"), "/p/src/ledger.js"));
        assertEquals("/elsewhere/ledger.js",
                JavaScriptDiagnostics.relativize(Path.of("/p"), "/elsewhere/ledger.js"));
        assertEquals("src/ledger.js",
                JavaScriptDiagnostics.relativize(null, " src/ledger.js "),
                "without a project root the reported text is preserved, trimmed");
    }
}
