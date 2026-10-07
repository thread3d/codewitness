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
 * @param directDependencies   how the inventory reports its dependencies, flattened across modules
 * @param dependenciesByModule which module resolves which coordinates; the key is the module name as
 *                             the build tool reports it. Needed to attribute an advisory to the code
 *                             it actually ships with: a dependency of {@code module-b} says nothing
 *                             about a requirement whose code lives in {@code module-a}.
 * @param coordinates          the same dependencies as typed advisory coordinates, carrying the
 *                             ecosystem and the resolved version. This is what an advisory scan can
 *                             actually query; {@code directDependencies} is only how it is displayed.
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
        List<PackageCoordinate> coordinates,
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
        this(analysed, tool, note, totalDependencies, directDependencies, Map.of(), List.of(), vulnerabilities,
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
        this(analysed, tool, note, totalDependencies, directDependencies, Map.of(), List.of(), vulnerabilities,
                advisoryScan);
    }

    /** For callers that inventory typed coordinates and have no per-module breakdown. */
    public DependencyReport(
            boolean analysed,
            String tool,
            String note,
            int totalDependencies,
            List<String> directDependencies,
            List<PackageCoordinate> coordinates,
            List<DependencyVulnerability> vulnerabilities,
            AdvisoryScan advisoryScan) {
        this(analysed, tool, note, totalDependencies, directDependencies, Map.of(), coordinates, vulnerabilities,
                advisoryScan);
    }

    public DependencyReport {
        directDependencies = List.copyOf(directDependencies == null ? List.of() : directDependencies);
        vulnerabilities = List.copyOf(vulnerabilities == null ? List.of() : vulnerabilities);
        advisoryScan = advisoryScan == null ? AdvisoryScan.notPerformed() : advisoryScan;
        Map<String, List<String>> modules = new LinkedHashMap<>();
        if (dependenciesByModule != null) {
            dependenciesByModule.forEach((module, entries) ->
                    modules.put(module, List.copyOf(entries == null ? List.of() : entries)));
        }
        dependenciesByModule = Map.copyOf(modules);
        // An inventory that predates typed coordinates - or one restored from a report written before
        // they existed - carries only Maven-shaped strings. Parsing those back apart is exactly what the
        // advisory scan used to do, so deriving them here keeps such an inventory queryable rather than
        // silently degrading it to "nothing could be asked".
        coordinates = coordinates == null || coordinates.isEmpty()
                ? PackageCoordinate.parseMaven(directDependencies)
                : List.copyOf(coordinates);
        totalDependencies = Math.max(0, totalDependencies);
    }

    /**
     * Builds the inventory a dependency analyzer produces: resolved coordinates, how they are
     * displayed, and which module resolves them.
     *
     * <p>{@code totalDependencies} is the number of distinct coordinates rather than the number of
     * names a manifest declares, because a lock file resolves the transitive graph as well. That is
     * also the set the advisory scan is asked about, so the two cannot drift apart.
     *
     * @param tool        analyzer identifier, for example {@code package-lock}
     * @param note        what the inventory covers, stated in the report's own vocabulary
     * @param coordinates every resolved dependency the inventory found
     * @param byModule    which module resolves which dependency, keyed by module name
     * @return the report
     */
    public static DependencyReport inventory(
            String tool, String note, List<PackageCoordinate> coordinates, Map<String, List<String>> byModule) {
        List<PackageCoordinate> distinct = PackageCoordinate.distinct(coordinates);
        List<String> display = distinct.stream().map(PackageCoordinate::display).toList();
        Map<String, List<String>> modules = new LinkedHashMap<>();
        if (byModule != null) {
            byModule.forEach((module, entries) ->
                    modules.put(module, List.copyOf(entries == null ? List.of() : entries)));
        }
        return new DependencyReport(true, tool, note, distinct.size(), display, modules, distinct,
                List.of(), AdvisoryScan.notPerformed());
    }

    public static DependencyReport notAnalysed(String tool, String note) {
        return new DependencyReport(false, tool, note, 0, List.of(), Map.of(), List.of(), List.of(),
                AdvisoryScan.notPerformed(
                        "the dependency inventory was not produced, so no advisory source was consulted"));
    }

    /** @return a copy of this report carrying the outcome of an advisory scan. */
    public DependencyReport withAdvisoryScan(AdvisoryScan scan, List<DependencyVulnerability> found) {
        return new DependencyReport(analysed, tool, note, totalDependencies, directDependencies,
                dependenciesByModule, coordinates, found, scan);
    }

    /**
     * @return the key under which one language's module records its resolved dependencies.
     *
     * <p>The language is part of the key because a polyglot tree can name a Java module and a Node
     * package directory the same thing. Without the prefix, a dependency resolved by one language's
     * module would be attributed to a requirement living in the other's, and an advisory would be charged
     * to code that does not ship it - a wrong answer of exactly the kind this report exists to avoid.
     * When the language is not known the bare module name is used, and matching falls back to comparing
     * names without a prefix.
     *
     * @param language   the language the module belongs to
     * @param moduleName the module name the adapter reported
     */
    public static String moduleKey(LanguageId language, String moduleName) {
        if (language == null || language.equals(LanguageId.POLYGLOT) || language.equals(LanguageId.UNKNOWN)) {
            return moduleName;
        }
        return language.id() + ":" + moduleName;
    }

    /**
     * @return the module's own name, with the language prefix {@link #moduleKey} added removed again.
     *
     * <p>Used where the key has to be shown to a reader, so that a counterexample says
     * {@code module-a} rather than the internal {@code java:module-a}.
     */
    public static String moduleName(LanguageId language, String key) {
        if (key == null || language == null) {
            return key;
        }
        String prefix = language.id() + ":";
        return key.startsWith(prefix) ? key.substring(prefix.length()) : key;
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
