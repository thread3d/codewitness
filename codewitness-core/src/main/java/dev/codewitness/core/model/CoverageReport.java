package dev.codewitness.core.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Coverage information produced by a coverage provider such as JaCoCo.
 *
 * @param measured               whether coverage was actually measured
 * @param tool                   identifier of the coverage tool
 * @param note                   explanation when coverage is unavailable
 * @param lineCovered           covered lines (aggregate)
 * @param lineTotal             total instrumented lines (aggregate)
 * @param branchCovered         covered branches (aggregate)
 * @param branchTotal           total branches (aggregate)
 * @param perClassLineCoverage  class name to line coverage percentage
 * @param reports               raw coverage report files
 */
public record CoverageReport(
        boolean measured,
        String tool,
        String note,
        int lineCovered,
        int lineTotal,
        int branchCovered,
        int branchTotal,
        Map<String, Double> perClassLineCoverage,
        List<Path> reports) {

    public CoverageReport {
        perClassLineCoverage = Map.copyOf(perClassLineCoverage == null ? Map.of() : perClassLineCoverage);
        reports = List.copyOf(reports == null ? List.of() : reports);
    }

    public static CoverageReport notMeasured(String tool, String note) {
        return new CoverageReport(false, tool, note, 0, 0, 0, 0, Map.of(), List.of());
    }

    public double lineCoveragePercent() {
        return lineTotal == 0 ? 0.0 : (100.0 * lineCovered / lineTotal);
    }

    public double branchCoveragePercent() {
        return branchTotal == 0 ? 0.0 : (100.0 * branchCovered / branchTotal);
    }

    public String describe() {
        if (!measured) {
            return "coverage not measured (" + note + ")";
        }
        return String.format(java.util.Locale.ROOT, "line %.1f%% (%d/%d), branch %.1f%% (%d/%d)",
                lineCoveragePercent(), lineCovered, lineTotal,
                branchCoveragePercent(), branchCovered, branchTotal);
    }
}
