package dev.codewitness.core.model;

import java.util.List;
import java.util.Map;

/**
 * Result of dependency and composition analysis.
 *
 * @param analysed           whether the analysis actually ran
 * @param tool               identifier of the analysis tool
 * @param note               explanation when the analysis is unavailable
 * @param totalDependencies  number of declared runtime dependencies discovered
 * @param directDependencies direct dependency coordinates
 * @param vulnerabilities    known vulnerabilities
 */
public record DependencyReport(
        boolean analysed,
        String tool,
        String note,
        int totalDependencies,
        List<String> directDependencies,
        List<DependencyVulnerability> vulnerabilities) {

    public DependencyReport {
        directDependencies = List.copyOf(directDependencies == null ? List.of() : directDependencies);
        vulnerabilities = List.copyOf(vulnerabilities == null ? List.of() : vulnerabilities);
    }

    public static DependencyReport notAnalysed(String tool, String note) {
        return new DependencyReport(false, tool, note, 0, List.of(), List.of());
    }

    public Map<Severity, Long> vulnerabilitiesBySeverity() {
        return vulnerabilities.stream().collect(java.util.stream.Collectors.groupingBy(
                DependencyVulnerability::severity, () -> new java.util.EnumMap<>(Severity.class), java.util.stream.Collectors.counting()));
    }
}
