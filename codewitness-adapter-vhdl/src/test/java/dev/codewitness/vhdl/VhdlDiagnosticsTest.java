package dev.codewitness.vhdl;

import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Parsing GHDL's console diagnostics. */
class VhdlDiagnosticsTest {

    private static final String OUTPUT = """
            rtl/ledger.vhd:12:17:error: no function declarations for operator "+"
              new_balance <= balance - amount + ;
                                ^
            rtl/ledger.vhd:20:5:warning: universal integer bound must be numeric literal
            ghdl:error: elaboration of design unit "ledger" failed
            simulation finished @0ms
            """;

    @Test
    @DisplayName("file-anchored errors and warnings, and a location-less tool error, are all read")
    void parsesAllShapes(@TempDir Path project) {
        List<CompilerDiagnostic> diagnostics =
                VhdlDiagnostics.parse(OUTPUT, project, DiagnosticSeverity.ERROR);
        assertEquals(3, diagnostics.size(), diagnostics.toString());

        CompilerDiagnostic error = diagnostics.get(0);
        assertEquals(DiagnosticSeverity.ERROR, error.severity());
        assertEquals("rtl/ledger.vhd", error.file());
        assertEquals(12, error.line());
        assertEquals("no function declarations for operator \"+\"", error.message());

        CompilerDiagnostic warning = diagnostics.get(1);
        assertEquals(DiagnosticSeverity.WARNING, warning.severity());
        assertEquals(20, warning.line());

        CompilerDiagnostic tool = diagnostics.get(2);
        assertEquals(DiagnosticSeverity.ERROR, tool.severity());
        assertNull(tool.file());
        assertEquals("elaboration of design unit \"ledger\" failed", tool.message());
    }

    @Test
    @DisplayName("a report line from a testbench is not mistaken for a diagnostic")
    void ignoresSimulatorChatter(@TempDir Path project) {
        String output = "ledger_tb.vhd:31:7:@1ns:(report note): PASS: fine\n"
                + "ghdl:info: simulation stopped by --stop-time @100ns\n";
        assertEquals(0, VhdlDiagnostics.parse(output, project, DiagnosticSeverity.ERROR).size());
    }

    @Test
    @DisplayName("an absolute path inside the project is reported relative to it")
    void relativizesAbsolutePath(@TempDir Path project) {
        String output = project.resolve("rtl/ledger.vhd") + ":3:1:error: bad";
        List<CompilerDiagnostic> diagnostics =
                VhdlDiagnostics.parse(output, project, DiagnosticSeverity.ERROR);
        assertEquals(1, diagnostics.size());
        assertEquals("rtl/ledger.vhd", diagnostics.getFirst().file());
    }
}
