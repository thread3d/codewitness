package dev.codewitness.core.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of dependency and composition analysis.
 *
 * @param analysed             whether the analysis actually ran
 * @param tool                 identifier of the analysis tool
 * @param note                 explanation when the analysis is unavailable
 * @param totalDependencies    number of distinct resolved dependencies discovered
 * @param directDependencies   resolved dependency coordinates, flattened across modules
 * @param dependenciesByModule which module resolves which coordinates; the key is the module name as
 *                             the build tool reports it. Needed to attribute an advisory to the code
 *                             it actually ships with: a dependency of {@code module-b} says nothing
 *                             about a requirement whose code lives in {@code module-a}.
 * @param vulnerabilities      known vulnerabilities found by an advisory scan
 * @param advisoryScan         which advisory source, if any, the vulnerabilities were looked for in
 */
public record DependencyReport(
        boolean analysed,
        String tool,
        String note,
        int totalDependencies,
        List<String> directDependencies,
        Map<String, List<String>> dependenciesByModule,
        List<DependencyVulnerability> vulnerabilities,
        AdvisoryScan advisoryScan) {

    /** For callers that consult no advisory source and have no per-module breakdown. */
    public DependencyReport(
            boolean analysed,
            String tool,
            String note,
            int totalDependencies,
            List<String> directDependencies,
            List<DependencyVulnerability> vulnerabilities) {
        this(analysed, tool, note, totalDependencies, directDependencies, Map.of(), vulnerabilities,
                AdvisoryScan.notPerformed());
    }

    /** For callers that know the advisory outcome but have no per-module breakdown. */
    public DependencyReport(
            boolean analysed,
            String tool,
            String note,
            int totalDependencies,
            List<String> directDependencies,
            List<DependencyVulnerability> vulnerabilities,
            AdvisoryScan advisoryScan) {
        this(analysed, tool, note, totalDependencies, directDependencies, Map.of(), vulnerabilities, advisoryScan);
    }

    public DependencyReport {
        directDependencies = List.copyOf(directDependencies == null ? List.of() : directDependencies);
        vulnerabilities = List.copyOf(vulnerabilities == null ? List.of() : vulnerabilities);
        advisoryScan = advisoryScan == null ? AdvisoryScan.notPerformed() : advisoryScan;
        Map<String, List<String>> modules = new LinkedHashMap<>();
        if (dependenciesByModule != null) {
            dependenciesByModule.forEach((module, coordinates) ->
                    modules.put(module, List.copyOf(coordinates == null ? List.of() : coordinates)));
        }
        dependenciesByModule = Map.copyOf(modules);
    }

    public static DependencyReport notAnalysed(String tool, String note) {
        return new DependencyReport(false, tool, note, 0, List.of(), Map.of(), List.of(), AdvisoryScan.notPerformed(
                "the dependency inventory was not produced, so no advisory source was consulted"));
    }

    /** @return a copy of this report carrying the outcome of an advisory scan. */
    public DependencyReport withAdvisoryScan(AdvisoryScan scan, List<DependencyVulnerability> found) {
        return new DependencyReport(analysed, tool, note, totalDependencies, directDependencies,
                dependenciesByModule, found, scan);
    }

    /** @return the modules whose resolved dependencies include the given coordinate. */
    public List<String> modulesDependingOn(String coordinate) {
        List<String> modules = new java.util.ArrayList<>();
        dependenciesByModule.forEach((module, coordinates) -> {
            if (coordinates.contains(coordinate)) {
                modules.add(module);
            }
        });
        return List.copyOf(modules);
    }

    public Map<Severity, Long> vulnerabilitiesBySeverity() {
        return vulnerabilities.stream().collect(java.util.stream.Collectors.groupingBy(
                DependencyVulnerability::severity, () -> new java.util.EnumMap<>(Severity.class), java.util.stream.Collectors.counting()));
    }
}
