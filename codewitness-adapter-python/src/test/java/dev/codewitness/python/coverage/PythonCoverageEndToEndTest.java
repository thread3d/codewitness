package dev.codewitness.python.coverage;

import dev.codewitness.core.exec.LocalProcessSandbox;
import dev.codewitness.core.io.WorkspaceManager;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.python.PythonInterpreter;
import dev.codewitness.python.PythonLanguageAdapter;
import dev.codewitness.python.PythonTestContexts;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures a real Python project through coverage.py, end to end.
 *
 * <p>Every other test of this provider substitutes its inputs: the parser is handed a JSON file and the
 * killed-run path is handed a sandbox double, so neither of them runs {@code coverage run}. That left the
 * branch that actually measures — the one every user of a Python project depends on — with no test that
 * could fail, which is the defect this test closes. It runs the fixture's real suite under coverage.py and
 * reads the numbers back through the provider.
 *
 * <p>It skips, rather than fails, when the interpreter cannot import coverage.py: coverage.py is not part
 * of the Python distribution and CodeWitness installs nothing, so its absence is a normal condition here
 * exactly as it is in a verification run. CI installs and asserts it, so the skip cannot hide a regression
 * there — the same arrangement the PHP adapter's Xdebug driver has.
 */
@Tag("e2e")
class PythonCoverageEndToEndTest {

    @Test
    @DisplayName("the fixture's suite is measured through coverage.py when the interpreter can import it")
    void measuresTheFixtureThroughCoveragePy(@TempDir Path runDirectory) throws IOException {
        Path fixture = fixture();
        Assumptions.assumeTrue(fixture != null,
                "fixtures/python-withdrawal-ledger is not present in this checkout");

        // A copy rather than the checkout: coverage.py writes a data file into the directory it runs in,
        // and an end-to-end test that left .coverage in a fixture would dirty the repository it tests.
        Path project = WorkspaceManager.prepare(ProjectRef.of(fixture), runDirectory, WorkspaceManager.Mode.COPY)
                .root();
        VerificationContext inspection = PythonTestContexts.context(project, runDirectory);
        ProjectInfo info = new PythonLanguageAdapter().inspect(ProjectRef.of(project), inspection);
        Assumptions.assumeTrue(Boolean.parseBoolean(
                        info.attributes().getOrDefault(PythonInterpreter.ATTR_HAS_COVERAGE, "false")),
                "the project interpreter cannot import coverage.py, so there is nothing to measure: install "
                        + "coverage to exercise this test (CI installs it); probe was " + info.attributes());

        CoverageReport coverage = new PythonCoverageProvider().measure(PythonTestContexts.context(
                info, runDirectory, List.of(), new LocalProcessSandbox(List.of(project, runDirectory))));

        assertTrue(coverage.measured(), "coverage.py is importable, so the phase must measure rather than "
                + "decline: " + coverage.describe());
        assertEquals("coverage.py", coverage.tool(), coverage.describe());
        assertTrue(coverage.lineTotal() > 0,
                "the fixture has statements, so a measurement needs a total: " + coverage.describe());
        assertTrue(coverage.lineCovered() > 0,
                "the fixture's suite executes code, so a measurement needs covered lines: " + coverage.describe());
        assertTrue(coverage.lineCovered() <= coverage.lineTotal(), coverage.describe());
        assertFalse(coverage.perClassLineCoverage().isEmpty(),
                "a measurement needs the per-file view the report renders: " + coverage.describe());
        assertTrue(coverage.perClassLineCoverage().keySet().stream().allMatch(name -> name.endsWith(".py")),
                "every per-file entry must be a Python source file: " + coverage.perClassLineCoverage().keySet());
        assertTrue(coverage.reports().stream().allMatch(Files::isRegularFile),
                "the measurement must leave the report it was read from: " + coverage.reports());
        assertFalse(coverage.reports().isEmpty(), "a measurement names the report it was read from");
    }

    /** @return the fixture directory, found by walking up to the reactor root, or {@code null}. */
    private static Path fixture() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("fixtures").resolve("python-withdrawal-ledger");
            if (Files.isDirectory(candidate) && Files.isRegularFile(current.resolve("pom.xml"))) {
                return candidate;
            }
            current = current.getParent();
        }
        return null;
    }
}
