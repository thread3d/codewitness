package dev.codewitness.cpp;

import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the compiler-diagnostic parser.
 *
 * <p>The parser is the boundary between a build that failed and a finding about the code, so what it
 * records and what it deliberately ignores are both asserted: a diagnostic with a position becomes one, a
 * link failure with none becomes one, and the configure log, the progress lines and the compiler's own
 * context notes become none.
 */
class CppDiagnosticsTest {

    @Test
    @DisplayName("a clang or gcc diagnostic on one line becomes one diagnostic with its position")
    void parsesCompilerShape(@TempDir Path project) {
        String output = """
                /src/ledger.cpp:13:33: error: expected ';' after expression
                /src/ledger.cpp:21:9: warning: unused variable 'total' [-Wunused-variable]
                /src/ledger.cpp:7:1: fatal error: 'vector' file not found
                """;

        List<CompilerDiagnostic> diagnostics = CppDiagnostics.parse(output, null);

        assertEquals(3, diagnostics.size(), diagnostics.toString());
        CompilerDiagnostic error = diagnostics.getFirst();
        assertEquals(DiagnosticSeverity.ERROR, error.severity());
        assertEquals("/src/ledger.cpp", error.file());
        assertEquals(13, error.line());
        assertEquals(33, error.column());
        assertEquals("expected ';' after expression", error.message());
        assertEquals(DiagnosticSeverity.WARNING, diagnostics.get(1).severity());
        assertEquals(DiagnosticSeverity.ERROR, diagnostics.get(2).severity());
        assertEquals("'vector' file not found", diagnostics.get(2).message());
    }

    @Test
    @DisplayName("a gcc diagnostic with no column is still a diagnostic")
    void parsesLineOnlyShape() {
        List<CompilerDiagnostic> diagnostics =
                CppDiagnostics.parse("ledger.cpp:5: error: 'ledger' was not declared\n", null);

        assertEquals(1, diagnostics.size());
        assertEquals(5, diagnostics.getFirst().line());
        assertEquals(null, diagnostics.getFirst().column());
    }

    @Test
    @DisplayName("the MSVC shape is read with and without a column, and keeps its error code")
    void parsesMsvcShape() {
        String output = """
                C:\\src\\ledger.cpp(13,33): error C2143: syntax error: missing ';' before '}'
                ledger.cpp(9): warning C4101: 'total': unreferenced local variable
                """;

        List<CompilerDiagnostic> diagnostics = CppDiagnostics.parse(output, null);

        assertEquals(2, diagnostics.size(), diagnostics.toString());
        assertEquals(13, diagnostics.getFirst().line());
        assertEquals(33, diagnostics.getFirst().column());
        assertEquals("C2143", diagnostics.getFirst().code());
        assertEquals(DiagnosticSeverity.WARNING, diagnostics.get(1).severity());
        assertEquals(9, diagnostics.get(1).line());
        assertEquals(null, diagnostics.get(1).column());
        assertEquals("C4101", diagnostics.get(1).code());
    }

    @Test
    @DisplayName("an absolute path under the project is reported relative to it")
    void relativizesPaths(@TempDir Path project) {
        String output = project.resolve("src/ledger.cpp") + ":3:1: error: bad\n";

        List<CompilerDiagnostic> diagnostics = CppDiagnostics.parse(output, project.toAbsolutePath().normalize());

        assertEquals("src/ledger.cpp", diagnostics.getFirst().file());
    }

    @Test
    @DisplayName("the noise around a diagnostic is not a diagnostic")
    void ignoresNoise() {
        String output = """
                -- Configuring done (0.3s)
                -- Generating done (0.0s)
                [1/4] Building CXX object CMakeFiles/ledger.dir/src/ledger.cpp.o
                In file included from /src/ledger.cpp:3:
                /src/ledger.h:2:10: note: expanded from macro 'LEDGER'
                make[2]: *** [CMakeFiles/ledger.dir/build.make:76: CMakeFiles/ledger.dir/src/ledger.cpp.o] Error 1
                make[1]: *** [CMakeFiles/Makefile2:83: CMakeFiles/ledger.dir/all] Error 2
                ninja: build stopped: subcommand failed.
                """;

        assertEquals(List.of(), CppDiagnostics.parse(output, null));
    }

    @Test
    @DisplayName("a diagnostic with no message is not a diagnostic a reader can act on")
    void ignoresEmptyMessages() {
        assertEquals(List.of(), CppDiagnostics.parse("/src/ledger.cpp:1:1: error:\n", null));
    }

    @Test
    @DisplayName("a link failure names no source position and is still a build error")
    void parsesLinkerFailures() {
        String output = """
                [2/3] Linking CXX executable withdrawal_ledger_test
                Undefined symbols for architecture x86_64:
                  "ledger::WithdrawalLedger::totalWithinLast24Hours(long long) const", referenced from:
                      _main in withdrawal_ledger_test.cpp.o
                ld: symbol(s) not found for architecture x86_64
                """;

        List<CompilerDiagnostic> diagnostics = CppDiagnostics.linkerErrors(output);

        assertTrue(diagnostics.size() >= 1, diagnostics.toString());
        assertEquals(DiagnosticSeverity.ERROR, diagnostics.getFirst().severity());
        assertEquals(null, diagnostics.getFirst().file());
        assertTrue(diagnostics.getFirst().message().contains("Undefined symbols"), diagnostics.getFirst().message());
    }

    @Test
    @DisplayName("a clean transcript has no diagnostic at all")
    void handlesEmptyOutput() {
        assertEquals(List.of(), CppDiagnostics.parse(null, null));
        assertEquals(List.of(), CppDiagnostics.parse("", null));
        assertEquals(List.of(), CppDiagnostics.linkerErrors(""));
    }
}
