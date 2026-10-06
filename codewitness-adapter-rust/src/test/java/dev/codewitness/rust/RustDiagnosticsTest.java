package dev.codewitness.rust;

import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** rustc's own diagnostic format, parsed without guessing at an unlocated message. */
class RustDiagnosticsTest {

    @Test
    @DisplayName("warnings and coded errors are read with their file, line, column and code")
    void parsesDiagnostics() {
        String output = """
                warning: unused variable: `total`
                 --> src/lib.rs:10:9
                  |
               10 |     let total = 1;
                  |         ^^^^^ help: if this is intentional, prefix it with an underscore: `_total`
                  |
                  = note: `#[warn(unused_variables)]` on by default

                error[E0308]: mismatched types
                  --> src/lib.rs:17:20
                   |
                17 |     let x: i32 = "text";
                   |                    ^^^^^^ expected `i32`, found `&str`

                error: aborting due to 1 previous error
                """;

        List<CompilerDiagnostic> diagnostics = RustDiagnostics.parse(output, null);

        assertEquals(2, diagnostics.size(), diagnostics.toString());
        CompilerDiagnostic warning = diagnostics.getFirst();
        assertEquals(DiagnosticSeverity.WARNING, warning.severity());
        assertEquals("src/lib.rs", warning.file());
        assertEquals(10, warning.line());
        assertEquals(9, warning.column());
        assertEquals("unused variable: `total`", warning.message());
        CompilerDiagnostic error = diagnostics.get(1);
        assertEquals(DiagnosticSeverity.ERROR, error.severity());
        assertEquals("E0308", error.code());
        assertEquals(17, error.line());
    }

    @Test
    @DisplayName("a header with no location is dropped rather than attached to the wrong file")
    void dropsUnlocatedDiagnostics() {
        assertEquals(List.of(), RustDiagnostics.parse("error: aborting due to 1 previous error\n", null));
    }
}
