package dev.codewitness.python.coverage;

import dev.codewitness.core.model.CoverageReport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing of coverage.py's JSON report. */
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
}
