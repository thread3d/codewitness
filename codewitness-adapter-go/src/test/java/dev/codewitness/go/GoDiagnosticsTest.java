package dev.codewitness.go;

import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Go toolchain has no machine readable build report, so the parser reads its own diagnostic
 * format. These tests pin the shape that matters: a located diagnostic is recorded, and everything
 * else in the output is ignored rather than attached to the wrong file.
 */
class GoDiagnosticsTest {

    private static final String OUTPUT = """
            # codewitness.local/ledger
            ./ledger.go:42:9: undefined: balance
            ./ledger_test.go:17:20: too many arguments in call to New
            # codewitness.local/ledger/sub
            sub/util.go:3:1: syntax error: unexpected EOF
            go: downloading example.com/dep v1.2.3
            ledger.go:50:2: declared and not used: recorded
            """;

    @Test
    @DisplayName("located diagnostics are parsed with their file, position and message")
    void parsesDiagnostics() {
        List<CompilerDiagnostic> diagnostics =
                GoDiagnostics.parse(OUTPUT, Path.of("/tmp/workspace"), DiagnosticSeverity.ERROR);

        assertEquals(4, diagnostics.size());
        CompilerDiagnostic first = diagnostics.getFirst();
        assertEquals(DiagnosticSeverity.ERROR, first.severity());
        assertEquals("ledger.go", first.file());
        assertEquals(42, first.line());
        assertEquals(9, first.column());
        assertEquals("undefined: balance", first.message());
        assertEquals("ledger_test.go", diagnostics.get(1).file());
        assertEquals("sub/util.go", diagnostics.get(2).file());
        assertEquals("ledger.go", diagnostics.get(3).file());
    }

    @Test
    @DisplayName("package headers and go: progress lines are not diagnostics")
    void ignoresNonDiagnosticLines() {
        List<CompilerDiagnostic> diagnostics =
                GoDiagnostics.parse("# pkg\ngo: downloading x v1\nok  \tpkg\t0.1s\n", Path.of("."),
                        DiagnosticSeverity.WARNING);

        assertTrue(diagnostics.isEmpty());
    }

    @Test
    @DisplayName("the severity comes from the command that produced the output")
    void severityComesFromTheCommand() {
        List<CompilerDiagnostic> diagnostics =
                GoDiagnostics.parse("ledger.go:1:1: something to check\n", Path.of("."), DiagnosticSeverity.WARNING);

        assertEquals(DiagnosticSeverity.WARNING, diagnostics.getFirst().severity());
    }

    @Test
    @DisplayName("an absolute diagnostic path inside the project is made relative")
    void relativizesAbsolutePaths() {
        List<CompilerDiagnostic> diagnostics = GoDiagnostics.parse(
                "/tmp/workspace/ledger.go:2:3: undefined: x\n", Path.of("/tmp/workspace"), DiagnosticSeverity.ERROR);

        assertEquals("ledger.go", diagnostics.getFirst().file());
    }

    @Test
    @DisplayName("a vet driver label is stripped from the file path")
    void stripsVetDriverPrefix() {
        List<CompilerDiagnostic> diagnostics = GoDiagnostics.parse(
                "vet: ./ledger.go:3:4: something to check\n", Path.of("."), DiagnosticSeverity.WARNING);

        assertEquals("ledger.go", diagnostics.getFirst().file());
        assertEquals(3, diagnostics.getFirst().line());
    }
}
