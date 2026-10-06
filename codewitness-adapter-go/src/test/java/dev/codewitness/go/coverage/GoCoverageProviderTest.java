package dev.codewitness.go.coverage;

import dev.codewitness.core.model.CoverageReport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Go's coverage profile carries statement blocks, which is what the report aggregates. */
class GoCoverageProviderTest {

    @Test
    @DisplayName("statement totals and per-file percentages are read from the profile blocks")
    void parsesStatementBlocks(@TempDir Path directory) throws IOException {
        Path report = directory.resolve("coverage.out");
        Files.writeString(report, """
                mode: set
                example.com/sample/ledger/ledger.go:10.2,12.5 3 1
                example.com/sample/ledger/ledger.go:14.2,16.5 2 0
                example.com/sample/format/money.go:5.1,6.2 4 1
                """, StandardCharsets.UTF_8);

        CoverageReport coverage = GoCoverageProvider.parse(report, "test");

        assertTrue(coverage.measured());
        assertEquals("go-cover", coverage.tool());
        // All three statements of a covered block count as covered; the uncovered block does not.
        assertEquals(7, coverage.lineCovered());
        assertEquals(9, coverage.lineTotal());
        // A statement block is not a branch, so branch totals are reported as zero rather than guessed.
        assertEquals(0, coverage.branchCovered());
        assertEquals(0, coverage.branchTotal());
        assertEquals(60.0, coverage.perClassLineCoverage().get("example.com/sample/ledger/ledger.go"), 0.001);
        assertEquals(100.0, coverage.perClassLineCoverage().get("example.com/sample/format/money.go"), 0.001);
        assertEquals(List.of(report), coverage.reports());
    }

    @Test
    @DisplayName("a profile with a header but no statement blocks is reported as not measured")
    void headerOnlyIsNotMeasured(@TempDir Path directory) throws IOException {
        Path report = directory.resolve("coverage.out");
        Files.writeString(report, "mode: atomic\n", StandardCharsets.UTF_8);

        CoverageReport coverage = GoCoverageProvider.parse(report, "test");

        assertFalse(coverage.measured());
        assertTrue(coverage.note().contains("no statements"), coverage.note());
    }

    @Test
    @DisplayName("a missing profile is reported as not measured instead of as zero coverage")
    void missingReportIsNotMeasured(@TempDir Path directory) {
        CoverageReport coverage = GoCoverageProvider.parse(directory.resolve("absent.out"), "test");

        assertFalse(coverage.measured());
        assertTrue(coverage.note().contains("cannot read"), coverage.note());
    }
}
