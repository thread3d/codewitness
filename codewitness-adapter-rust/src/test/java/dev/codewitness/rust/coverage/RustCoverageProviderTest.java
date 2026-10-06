package dev.codewitness.rust.coverage;

import dev.codewitness.core.model.CoverageReport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** cargo-llvm-cov's JSON export carries the aggregate totals the report needs. */
class RustCoverageProviderTest {

    @Test
    @DisplayName("line and branch totals are read from the JSON export")
    void parsesTotals(@TempDir Path directory) throws IOException {
        Path report = directory.resolve("coverage.json");
        Files.writeString(report, """
                {
                  "data": [
                    {
                      "files": [],
                      "totals": {
                        "lines": { "count": 100, "covered": 80, "percent": 80.0 },
                        "branches": { "count": 10, "covered": 5, "percent": 50.0 },
                        "functions": { "count": 4, "covered": 3, "percent": 75.0 }
                      }
                    }
                  ],
                  "type": "llvm.coverage.json.export",
                  "version": "2.0.1"
                }
                """, StandardCharsets.UTF_8);

        CoverageReport coverage = RustCoverageProvider.parse(report, "test");

        assertTrue(coverage.measured());
        assertEquals("cargo-llvm-cov", coverage.tool());
        assertEquals(80, coverage.lineCovered());
        assertEquals(100, coverage.lineTotal());
        assertEquals(5, coverage.branchCovered());
        assertEquals(10, coverage.branchTotal());
        assertEquals(80.0, coverage.lineCoveragePercent(), 0.001);
    }

    @Test
    @DisplayName("a report with no line totals is reported as not measured")
    void emptyTotalsAreNotMeasured(@TempDir Path directory) throws IOException {
        Path report = directory.resolve("coverage.json");
        Files.writeString(report, "{\"data\":[{\"totals\":{\"lines\":{\"count\":0,\"covered\":0}}}]}",
                StandardCharsets.UTF_8);

        CoverageReport coverage = RustCoverageProvider.parse(report, "test");

        assertFalse(coverage.measured());
        assertTrue(coverage.note().contains("no line totals"));
    }
}
