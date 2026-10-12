package dev.codewitness.verilog;

import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Icarus Verilog diagnostic shapes, including the bare parse error. */
class VerilogDiagnosticsTest {

    @Test
    @DisplayName("error, warning and bare syntax error lines are all read")
    void readsIcarusShapes() {
        String output = """
                ledger.v:12: error: Unable to bind wire/reg/memory `balance' in `ledger'
                ledger.v:20: warning: implicit truncation of a 32-bit expression
                ledger_tb.v:8: syntax error
                ledger.v:30: Include file missing.vh not found
                Elaboration failed
                """;
        List<CompilerDiagnostic> diagnostics =
                VerilogDiagnostics.parse(output, Path.of("/project"), DiagnosticSeverity.ERROR);

        assertEquals(4, diagnostics.size());
        CompilerDiagnostic error = diagnostics.get(0);
        assertEquals(DiagnosticSeverity.ERROR, error.severity());
        assertEquals("ledger.v", error.file());
        assertEquals(12, error.line());
        assertEquals("Unable to bind wire/reg/memory `balance' in `ledger'", error.message());

        assertEquals(DiagnosticSeverity.WARNING, diagnostics.get(1).severity());
        assertEquals(20, diagnostics.get(1).line());

        // The bare parse error is the one Icarus prints with no message after the label.
        assertEquals(DiagnosticSeverity.ERROR, diagnostics.get(2).severity());
        assertEquals(8, diagnostics.get(2).line());
        assertEquals("syntax error", diagnostics.get(2).message());

        assertEquals(30, diagnostics.get(3).line());
        assertTrue(diagnostics.get(3).message().contains("Include file"), diagnostics.get(3).message());
    }

    @Test
    @DisplayName("a column, when a tool prints one, is kept")
    void keepsColumn() {
        List<CompilerDiagnostic> diagnostics = VerilogDiagnostics.parse(
                "ledger.v:5:9: error: bad select\n", null, DiagnosticSeverity.ERROR);
        assertEquals(1, diagnostics.size());
        assertEquals(9, diagnostics.get(0).column());
    }

    @Test
    @DisplayName("banners and display output are not diagnostics")
    void ignoresNonDiagnostics() {
        String output = """
                Icarus Verilog version 13.0 (stable) (v13_0)
                PASS: the ledger is correct
                $finish called at 10 (1s)
                """;
        assertTrue(VerilogDiagnostics.parse(output, null, DiagnosticSeverity.ERROR).isEmpty());
    }
}
