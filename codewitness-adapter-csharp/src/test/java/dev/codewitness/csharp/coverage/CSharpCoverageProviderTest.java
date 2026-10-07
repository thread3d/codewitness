package dev.codewitness.csharp.coverage;

import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.csharp.CSharpTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for reading coverlet's cobertura report. */
class CSharpCoverageProviderTest {

    private static final String COBERTURA = """
            <?xml version="1.0" encoding="utf-8"?>
            <coverage line-rate="0.8" branch-rate="0.5" version="1.9" timestamp="1"
                      lines-covered="8" lines-valid="10" branches-covered="1" branches-valid="2">
              <sources>
                <source>/</source>
              </sources>
              <packages>
                <package name="Ledger" line-rate="0.8" branch-rate="0.5" complexity="4">
                  <classes>
                    <class name="Ledger.WithdrawalLedger" filename="src/Ledger/WithdrawalLedger.cs"
                           line-rate="0.8" branch-rate="0.5" complexity="4">
                      <methods>
                        <method name="Withdraw" signature="(System.Int64)" line-rate="0.75" branch-rate="0.5">
                          <lines>
                            <line number="9" hits="2" branch="False" />
                            <line number="10" hits="0" branch="False" />
                            <line number="11" hits="2" branch="True" condition-coverage="50% (1/2)" />
                          </lines>
                        </method>
                      </methods>
                    </class>
                  </classes>
                </package>
              </packages>
            </coverage>
            """;

    @Test
    @DisplayName("coverlet's own totals are the measurement, and each class keeps its rate")
    void parsesCobertura(@TempDir Path directory) throws IOException {
        Path report = CSharpTestContexts.write(directory, "coverage.cobertura.xml", COBERTURA);

        CoverageReport coverage = CSharpCoverageProvider.parse(report, "note");

        assertTrue(coverage.measured());
        assertEquals("dotnet-coverage", coverage.tool());
        assertEquals(8, coverage.lineCovered());
        assertEquals(10, coverage.lineTotal());
        assertEquals(80.0, coverage.lineCoveragePercent());
        assertEquals(1, coverage.branchCovered());
        assertEquals(2, coverage.branchTotal());
        assertEquals(80.0, coverage.perClassLineCoverage().get("Ledger.WithdrawalLedger"));
        assertEquals(List.of(report), coverage.reports());
    }

    @Test
    @DisplayName("a report that states no totals is counted from its line elements")
    void countsLinesWhenTotalsAreAbsent(@TempDir Path directory) throws IOException {
        Path report = CSharpTestContexts.write(directory, "coverage.cobertura.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <coverage version="1.9">
                  <packages>
                    <package name="Ledger">
                      <classes>
                        <class name="Ledger.WithdrawalLedger" filename="Ledger.cs" line-rate="0.5">
                          <methods>
                            <method name="Withdraw" signature="(System.Int64)">
                              <lines>
                                <line number="9" hits="2" branch="False" />
                                <line number="10" hits="0" branch="False" />
                                <line number="11" hits="2" branch="True" condition-coverage="50% (1/2)" />
                              </lines>
                            </method>
                          </methods>
                        </class>
                      </classes>
                    </package>
                  </packages>
                </coverage>
                """);

        CoverageReport coverage = CSharpCoverageProvider.parse(report, "note");

        assertTrue(coverage.measured());
        assertEquals(1, coverage.lineCovered());
        assertEquals(2, coverage.lineTotal(), "a branch-carrying line is not counted twice");
    }

    @Test
    @DisplayName("reports are summed, and a report with no line at all is not a measurement of zero")
    void combinesAndRefusesEmptyReports(@TempDir Path directory) throws IOException {
        Path first = CSharpTestContexts.write(directory, "a.cobertura.xml", COBERTURA);
        Path second = CSharpTestContexts.write(directory, "b/coverage.cobertura.xml", COBERTURA);

        CoverageReport combined = CSharpCoverageProvider.combine(List.of(first, second), "two reports");
        assertTrue(combined.measured());
        assertEquals(16, combined.lineCovered());
        assertEquals(20, combined.lineTotal());

        Path empty = CSharpTestContexts.write(directory, "empty/coverage.cobertura.xml",
                "<coverage><packages/></coverage>");
        CoverageReport none = CSharpCoverageProvider.combine(List.of(empty), "one empty report");
        assertFalse(none.measured());
        assertTrue(none.note().contains("no instrumented line"), none.note());

        Path broken = CSharpTestContexts.write(directory, "broken/coverage.cobertura.xml", "not xml");
        assertFalse(CSharpCoverageProvider.parse(broken, "note").measured());
    }

    @Test
    @DisplayName("the reports below a results directory are found wherever the test host put them")
    void findsReports(@TempDir Path directory) throws IOException {
        CSharpTestContexts.write(directory, "guid-1/coverage.cobertura.xml", COBERTURA);
        CSharpTestContexts.write(directory, "guid-2/coverage.cobertura.xml", COBERTURA);
        CSharpTestContexts.write(directory, "results.trx", "<TestRun/>");

        assertEquals(2, CSharpCoverageProvider.coverageReports(directory).size());
        assertEquals(List.of(), CSharpCoverageProvider.coverageReports(directory.resolve("missing")));
    }
}
