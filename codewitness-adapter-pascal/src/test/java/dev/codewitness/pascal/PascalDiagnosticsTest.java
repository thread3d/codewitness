package dev.codewitness.pascal;

import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Free Pascal's diagnostics are read by their location and their label, and the banners are ignored. */
class PascalDiagnosticsTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("error, warning and note lines become located diagnostics with the right severity")
    void readsTheThreeLabels() {
        String output = "Free Pascal Compiler version 3.2.2 [2021/05/16] for x86_64\n"
                + "ledger.pas(42,7) Error: Identifier not found \"Withdraw\"\n"
                + "ledger.pas(50,3) Warning: Variable \"balance\" does not seem to be initialized\n"
                + "ledger.pas(60,3) Note: Local variable \"x\" not used\n";

        List<CompilerDiagnostic> diagnostics =
                PascalDiagnostics.parse(output, root, DiagnosticSeverity.ERROR);

        assertEquals(3, diagnostics.size());
        assertEquals(DiagnosticSeverity.ERROR, diagnostics.get(0).severity());
        assertEquals("ledger.pas", diagnostics.get(0).file());
        assertEquals(42, diagnostics.get(0).line());
        assertEquals(7, diagnostics.get(0).column());
        assertEquals("Identifier not found \"Withdraw\"", diagnostics.get(0).message());
        assertEquals(DiagnosticSeverity.WARNING, diagnostics.get(1).severity());
        assertEquals(DiagnosticSeverity.NOTE, diagnostics.get(2).severity());
    }

    @Test
    @DisplayName("a fatal syntax error is an error, and its line is kept")
    void fatalIsAnError() {
        String output = "bad.pas(5,1) Fatal: Syntax error, \";\" expected but \"BEGIN\" found\n";

        List<CompilerDiagnostic> diagnostics =
                PascalDiagnostics.parse(output, root, DiagnosticSeverity.ERROR);

        assertEquals(1, diagnostics.size());
        assertEquals(DiagnosticSeverity.ERROR, diagnostics.get(0).severity());
        assertEquals(5, diagnostics.get(0).line());
        assertTrue(diagnostics.get(0).message().contains("Syntax error"));
    }

    @Test
    @DisplayName("the compiler's closing banners and the linker's own chatter yield no diagnostics")
    void ignoresUnlocatedBanners() {
        String output = "Fatal: Compilation aborted\n"
                + "Error: /usr/local/bin/ppcx64 returned an error exitcode\n"
                + "ld: warning: -multiply_defined is obsolete\n"
                + "Assembling hello\n"
                + "Linking hello\n";

        assertTrue(PascalDiagnostics.parse(output, root, DiagnosticSeverity.ERROR).isEmpty());
    }

    @Test
    @DisplayName("an absolute path inside the project is reported relative to it")
    void relativizesProjectPaths() {
        String output = root.resolve("src/ledger.pas") + "(3,1) Error: something\n";

        List<CompilerDiagnostic> diagnostics =
                PascalDiagnostics.parse(output, root, DiagnosticSeverity.ERROR);

        assertEquals("src/ledger.pas", diagnostics.getFirst().file());
    }
}
