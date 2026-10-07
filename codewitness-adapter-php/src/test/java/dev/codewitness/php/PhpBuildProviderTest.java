package dev.codewitness.php;

import dev.codewitness.core.model.BuildResult;
import dev.codewitness.core.model.CompilerDiagnostic;
import dev.codewitness.core.model.DiagnosticSeverity;
import dev.codewitness.core.spi.VerificationContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing of the syntax checker's structured diagnostics, and the build results around them. */
class PhpBuildProviderTest {

    @Test
    @DisplayName("CWDIAG lines become compiler diagnostics with a project-relative path and no column")
    void parsesDiagnostics() {
        Path root = Path.of("/project");
        String output = """
                CWDIAG|ERROR|/project/src/WithdrawalLedger.php|12|syntax error, unexpected token ";"
                CWDIAG|WARNING|/project/other.php|0|the file could not be read
                CWSYNTAX|files=2|errors=1
                """;

        List<CompilerDiagnostic> diagnostics = PhpBuildProvider.parseDiagnostics(output, root);

        assertEquals(2, diagnostics.size());
        CompilerDiagnostic first = diagnostics.getFirst();
        assertEquals(DiagnosticSeverity.ERROR, first.severity());
        assertEquals("src/WithdrawalLedger.php", first.file());
        assertEquals(12, first.line());
        assertNull(first.column(), "the interpreter's parse error reports a line, not a column");
        assertEquals("syntax error, unexpected token \";\"", first.message());
        assertEquals(DiagnosticSeverity.WARNING, diagnostics.get(1).severity());
        assertEquals("other.php", diagnostics.get(1).file());
    }

    @Test
    @DisplayName("a truncated diagnostic line is dropped rather than given an invented location")
    void ignoresIncompleteAndUnrelatedOutput() {
        assertEquals(List.of(), PhpBuildProvider.parseDiagnostics(
                "CWDIAG|ERROR|/project/src/Broken.php|12\n", Path.of("/project")));
        assertEquals(List.of(), PhpBuildProvider.parseDiagnostics(
                "Traceback (most recent call last):\n", Path.of("/project")));
        assertEquals(List.of(), PhpBuildProvider.parseDiagnostics(null, Path.of("/project")));
    }

    @Test
    @DisplayName("a failed interpreter probe makes the build not attempted, so it can never be a code defect")
    void theProbeFailureIsAnInfrastructureProblem(@TempDir Path root, @TempDir Path run) throws IOException {
        PhpTestContexts.write(root, "src/Ledger.php", "<?php\n");
        VerificationContext context = PhpTestContexts.context(
                PhpTestContexts.withAttributes(root, Map.of(
                        PhpInterpreter.ATTR_PROBE_FAILURE, "not started: IOException: no such file",
                        PhpInterpreter.ATTR_PHP, PhpTestContexts.MISSING_PHP)),
                run);

        BuildResult result = new PhpBuildProvider().build(context);

        assertFalse(result.attempted(), "nothing was built, so nothing may be reported as built");
        assertFalse(result.success());
        assertTrue(result.failureReason().contains("interpreter probe failed"), result.failureReason());
    }

    @Test
    @DisplayName("a project with no PHP source outside its tests has no build to attempt")
    void noSourcesMeansNothingToBuild(@TempDir Path root, @TempDir Path run) throws IOException {
        PhpTestContexts.write(root, "composer.json", "{\"name\": \"acme/app\"}\n");
        VerificationContext context = PhpTestContexts.context(
                PhpTestContexts.withAttributes(root, Map.of(PhpInterpreter.ATTR_PHP, PhpTestContexts.MISSING_PHP)),
                run);

        BuildResult result = new PhpBuildProvider().build(context);

        assertFalse(result.attempted());
        assertTrue(result.failureReason().contains("no PHP source file"), result.failureReason());
    }

    @Test
    @DisplayName("an interpreter that cannot be started is reported as not attempted, never as a passed build")
    void aMissingInterpreterIsNotAPassedBuild(@TempDir Path root, @TempDir Path run) throws IOException {
        PhpTestContexts.write(root, "src/Ledger.php", "<?php\n");
        VerificationContext context = PhpTestContexts.context(
                PhpTestContexts.withAttributes(root, Map.of(PhpInterpreter.ATTR_PHP, PhpTestContexts.MISSING_PHP)),
                run);

        BuildResult result = new PhpBuildProvider().build(context);

        assertFalse(result.attempted(), "no interpreter ran, so no build outcome was observed");
        assertTrue(result.failureReason() != null && !result.failureReason().isBlank());
    }
}
