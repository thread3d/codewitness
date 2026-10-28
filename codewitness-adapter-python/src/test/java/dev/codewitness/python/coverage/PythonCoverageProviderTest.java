package dev.codewitness.python.coverage;

import dev.codewitness.core.exec.ExecutionSandbox;
import dev.codewitness.core.exec.IsolationLevel;
import dev.codewitness.core.exec.ProcessRunner;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ModuleInfo;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.python.PythonInterpreter;
import dev.codewitness.python.PythonTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing of coverage.py's JSON report, and the two ways the phase declines to measure. */
class PythonCoverageProviderTest {

    @Test
    @DisplayName("totals and per-file coverage are read from coverage.py's JSON report")
    void parsesCoverageJson(@TempDir Path root) throws IOException {
        Path report = root.resolve("coverage.json");
        java.nio.file.Files.writeString(report, """
                {
                  "meta": {"version": "7.6.0"},
                  "files": {
                    "src/ledger.py": {
                      "summary": {"covered_lines": 8, "num_statements": 10, "percent_covered": 80.0,
                                  "covered_branches": 3, "num_branches": 4},
                      "functions": {"withdraw": {"summary": {"percent_covered": 100.0}}},
                      "classes": {"WithdrawalLedger": {"summary": {"percent_covered": 80.0}}}
                    },
                    "src/fee.py": {
                      "summary": {"covered_lines": 2, "num_statements": 4, "percent_covered": 50.0}
                    }
                  },
                  "totals": {"covered_lines": 10, "num_statements": 14, "covered_branches": 3,
                             "num_branches": 4, "percent_covered": 71.4}
                }
                """);

        CoverageReport coverage = PythonCoverageProvider.parse(report, "test");

        assertTrue(coverage.measured());
        assertEquals(10, coverage.lineCovered());
        assertEquals(14, coverage.lineTotal());
        assertEquals(3, coverage.branchCovered());
        assertEquals(4, coverage.branchTotal());
        assertEquals(80.0, coverage.perClassLineCoverage().get("src/ledger.py"), 0.001);
        assertEquals(50.0, coverage.perClassLineCoverage().get("src/fee.py"), 0.001);
        assertTrue(coverage.lineCoveragePercent() > 71 && coverage.lineCoveragePercent() < 72);
    }

    @Test
    @DisplayName("a malformed report is not measured, never a zero-percent pass")
    void malformedReportIsNotMeasured() {
        CoverageReport coverage = PythonCoverageProvider.parse(Path.of("/does/not/exist.json"), "test");
        assertTrue(!coverage.measured());
    }

    @Test
    @DisplayName("an interpreter that cannot import coverage is not measured, and nothing is run")
    void absentDriverIsNotMeasuredAndRunsNothing(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("project");
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("tests"));
        ProjectInfo project = ProjectInfo.builder(ProjectRef.of(root))
                .language(LanguageId.PYTHON)
                // What the adapter records when the probe found no coverage.py. The absent default is the
                // point: a project that never recorded the probe must not be measured by accident either.
                .attribute(PythonInterpreter.ATTR_HAS_COVERAGE, "false")
                .addModule(new ModuleInfo("sample", root, "wheel",
                        List.of(root.resolve("src")), List.of(root.resolve("tests")), null))
                .build();

        CoverageReport coverage = new PythonCoverageProvider().measure(
                PythonTestContexts.context(project, tmp.resolve("run"), List.of(), unusableSandbox()));

        assertFalse(coverage.measured(), "an importable driver is a precondition, not an assumption: "
                + coverage.describe());
        assertEquals("coverage.py", coverage.tool());
        assertTrue(coverage.note().contains("cannot import coverage"), coverage.note());
        assertEquals(0, coverage.lineTotal(), "an unmeasured report must carry no statements to be covered");
        assertEquals(0, coverage.lineCovered(), "an unmeasured report must not invent covered lines");
    }

    /** A sandbox that fails the test if the provider starts anything: absence costs no process run. */
    private static ExecutionSandbox unusableSandbox() {
        return new ExecutionSandbox() {
            @Override
            public String id() {
                return "unusable-double";
            }

            @Override
            public IsolationLevel isolationLevel() {
                return IsolationLevel.NONE;
            }

            @Override
            public String description() {
                return "test double: starting a process is the failure the test is looking for";
            }

            @Override
            public boolean permits(Path path) {
                return true;
            }

            @Override
            public ProcessRunner runner() {
                return spec -> {
                    throw new AssertionError("coverage must not run anything when the driver is absent: "
                            + spec.command());
                };
            }
        };
    }
}
