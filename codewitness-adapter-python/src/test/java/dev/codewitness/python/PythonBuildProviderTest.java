package dev.codewitness.python;

import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Parsing of the syntax checker's structured diagnostics. */
class PythonBuildProviderTest {

    @Test
    @DisplayName("CWDIAG lines become compiler diagnostics with a project-relative path")
    void parsesDiagnostics() {
        Path root = Path.of("/project");
        String output = """
                CWDIAG|ERROR|/project/src/ledger.py|3|7|invalid syntax
                CWDIAG|ERROR|/project/other.py|10|0|unexpected indent
                CWSYNTAX|checked=2|errors=2
                """;

        List<CompilerDiagnostic> diagnostics = PythonBuildProvider.parseDiagnostics(output, root);

        assertEquals(2, diagnostics.size());
        CompilerDiagnostic first = diagnostics.getFirst();
        assertEquals(DiagnosticSeverity.ERROR, first.severity());
        assertEquals("src/ledger.py", first.file());
        assertEquals(3, first.line());
        assertEquals(7, first.column());
        assertEquals("invalid syntax", first.message());
        assertEquals("other.py", diagnostics.get(1).file());
    }

    @Test
    @DisplayName("unrelated output produces no diagnostics rather than a guessed error")
    void ignoresUnrelatedOutput() {
        assertEquals(List.of(), PythonBuildProvider.parseDiagnostics("Traceback (most recent call last):\n", Path.of("/p")));
        assertEquals(List.of(), PythonBuildProvider.parseDiagnostics(null, Path.of("/p")));
    }
}
