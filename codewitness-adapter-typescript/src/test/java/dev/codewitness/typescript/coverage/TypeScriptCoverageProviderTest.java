package dev.codewitness.typescript.coverage;

import dev.codewitness.core.model.CoverageReport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing of the LCOV report Node's V8 coverage writes. */
class TypeScriptCoverageProviderTest {

    @Test
    @DisplayName("LCOV line and branch records become aggregate and per-file coverage")
    void parsesLcov() {
        String lcov = """
                TN:
                SF:src/ledger.ts
                FN:1,deposit
                FNDA:2,deposit
                BRDA:1,0,0,1
                BRDA:1,1,0,2
                BRDA:2,2,0,1
                DA:1,1
                DA:2,2
                DA:3,1
                DA:4,2
                LH:4
                LF:4
                end_of_record
                """;

        CoverageReport report = TypeScriptCoverageProvider.parse(lcov, "note", List.of());

        assertTrue(report.measured());
        assertEquals(4, report.lineCovered());
        assertEquals(4, report.lineTotal());
        assertEquals(3, report.branchCovered());
        assertEquals(3, report.branchTotal());
        assertEquals(100.0, report.perClassLineCoverage().get("src/ledger.ts"), 0.001);
    }

    @Test
    @DisplayName("a record without BRDA lines falls back to its BRF/BRH summary exactly once")
    void fallsBackToBranchSummary() {
        String lcov = """
                SF:src/other.ts
                DA:1,0
                DA:2,1
                BRF:2
                BRH:1
                end_of_record
                """;

        CoverageReport report = TypeScriptCoverageProvider.parse(lcov, "note", List.of());

        assertEquals(1, report.lineCovered());
        assertEquals(2, report.lineTotal());
        assertEquals(1, report.branchCovered());
        assertEquals(2, report.branchTotal());
        assertEquals(50.0, report.perClassLineCoverage().get("src/other.ts"), 0.001);
    }

    @Test
    @DisplayName("a report with no instrumented line is 'not measured', never 0% coverage")
    void emptyReportIsNotMeasured() {
        assertFalse(TypeScriptCoverageProvider.parse("TN:\n", "note", List.of()).measured());
        assertFalse(TypeScriptCoverageProvider.parse("", "note", List.of()).measured());
    }
}
