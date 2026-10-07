package dev.codewitness.core.config;

import dev.codewitness.core.model.Severity;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads {@code codewitness.yaml}.
 *
 * <p>The loader is deliberately forgiving: unknown keys are ignored, missing keys fall back to
 * documented defaults, and a malformed file produces a hard error rather than a silently empty
 * configuration. Configuration mistakes must never quietly weaken verification.
 */
public final class ConfigLoader {

    private ConfigLoader() {
    }

    public static final String DEFAULT_FILE_NAME = "codewitness.yaml";

    /** Loads configuration from an explicit file, or returns defaults when the file does not exist. */
    public static Loaded load(Path configFile) {
        if (configFile == null || !Files.isRegularFile(configFile)) {
            return new Loaded(VerificationConfig.defaults(), null, List.of());
        }
        try {
            String content = Files.readString(configFile, StandardCharsets.UTF_8);
            return parse(content, configFile);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read configuration file " + configFile, e);
        }
    }

    /** Loads {@code codewitness.yaml} from the project root when present. */
    public static Loaded loadFromProject(Path projectRoot) {
        return load(projectRoot.resolve(DEFAULT_FILE_NAME));
    }

    @SuppressWarnings("unchecked")
    public static Loaded parse(String yamlContent, Path source) {
        Object root;
        try {
            root = new Yaml().load(yamlContent == null ? "" : yamlContent);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid YAML in " + source + ": " + e.getMessage(), e);
        }
        if (root == null) {
            return new Loaded(VerificationConfig.defaults(), source, List.of());
        }
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("configuration root must be a mapping in " + source);
        }
        Map<String, Object> map = (Map<String, Object>) root;
        List<String> warnings = new ArrayList<>();

        VerificationConfig defaults = VerificationConfig.defaults();

        VerificationConfig.Project project = new VerificationConfig.Project(
                string(mapOf(map, "project"), "name", defaults.project().name()));

        VerificationConfig.Language language = new VerificationConfig.Language(
                string(mapOf(map, "language"), "adapter", defaults.language().adapter()));

        Map<String, Object> verificationMap = mapOf(map, "verification");
        Map<String, Object> testsMap = mapOf(map, "tests");
        Map<String, Object> mutationMap = mapOf(map, "mutation");
        Map<String, Object> propertyMap = mapOf(map, "property_testing");
        Map<String, Object> staticMap = mapOf(map, "static_analysis");
        Map<String, Object> securityMap = mapOf(map, "security");
        Map<String, Object> llmMap = mapOf(map, "llm");
        Map<String, Object> reportMap = mapOf(map, "report");
        Map<String, Object> sandboxMap = mapOf(map, "sandbox");

        VerificationConfig.Limits limits = new VerificationConfig.Limits(
                DurationParser.parse(string(verificationMap, "timeout", null), defaults.limits().timeout()),
                DurationParser.parse(string(verificationMap, "max_duration", null), defaults.limits().maxDuration()),
                integer(verificationMap, "max_test_generation_iterations",
                        defaults.limits().maxTestGenerationIterations()),
                integer(verificationMap, "max_generated_tests", defaults.limits().maxGeneratedTests()),
                DurationParser.parse(string(verificationMap, "max_mutation_time", null),
                        defaults.limits().maxMutationTime()),
                bool(verificationMap, "fail_fast_on_counterexample", defaults.limits().failFastOnCounterexample()));

        VerificationConfig.Mutation mutation = new VerificationConfig.Mutation(
                bool(mutationMap, "enabled", defaults.mutation().enabled()),
                string(mutationMap, "tool", defaults.mutation().tool()),
                DurationParser.parse(string(mutationMap, "timeout", null), defaults.mutation().timeout()),
                stringList(mutationMap, "target_classes"));

        VerificationConfig.PropertyTesting propertyTesting = new VerificationConfig.PropertyTesting(
                bool(propertyMap, "enabled", defaults.propertyTesting().enabled()),
                integer(propertyMap, "max_scenarios", defaults.propertyTesting().maxScenarios()),
                bool(propertyMap, "concurrent", defaults.propertyTesting().concurrentScenarios()));

        VerificationConfig.StaticAnalysis staticAnalysis = new VerificationConfig.StaticAnalysis(
                bool(staticMap, "enabled", defaults.staticAnalysis().enabled()),
                stringList(staticMap, "analyzers").isEmpty()
                        ? defaults.staticAnalysis().analyzers() : stringList(staticMap, "analyzers"),
                bool(staticMap, "lint_all", defaults.staticAnalysis().lintAll()));

        VerificationConfig.Llm llm = new VerificationConfig.Llm(
                bool(llmMap, "enabled", false),
                string(llmMap, "provider", "none"),
                string(llmMap, "model", null),
                string(llmMap, "base_url", null),
                string(llmMap, "api_key_env", "CODEWITNESS_LLM_API_KEY"),
                integer(llmMap, "max_calls", 0),
                decimal(llmMap, "temperature", 0.2),
                DurationParser.parse(string(llmMap, "timeout", null), Duration.ofSeconds(60)));

        VerificationConfig.Report report = new VerificationConfig.Report(
                bool(reportMap, "html", defaults.report().html()),
                bool(reportMap, "json", defaults.report().json()),
                path(reportMap, "output_directory"),
                bool(reportMap, "keep_generated_tests", false));

        VerificationConfig.Sandbox sandbox = new VerificationConfig.Sandbox(
                string(sandboxMap, "mode", defaults.sandbox().mode()),
                string(sandboxMap, "image", defaults.sandbox().image()),
                bool(sandboxMap, "allow_network", false),
                string(sandboxMap, "container_workdir", defaults.sandbox().containerWorkdir()));

        List<Scenario> scenarios = parseScenarios(map.get("scenarios"), warnings);

        Map<String, Object> vulnerabilitiesMap = mapOf(securityMap, "vulnerabilities");
        VerificationConfig.VulnerabilityScan vulnerabilityScan = new VerificationConfig.VulnerabilityScan(
                bool(vulnerabilitiesMap, "enabled", false),
                string(vulnerabilitiesMap, "source", "osv"),
                string(vulnerabilitiesMap, "endpoint", "https://api.osv.dev"),
                DurationParser.parse(string(vulnerabilitiesMap, "timeout", null), Duration.ofSeconds(30)),
                integer(vulnerabilitiesMap, "max_dependencies", 500),
                failOnSeverity(string(vulnerabilitiesMap, "fail_on", null)));

        VerificationConfig config = new VerificationConfig(project, language, limits,
                new VerificationConfig.Toggle(bool(testsMap, "enabled", true)), mutation, propertyTesting,
                staticAnalysis, new VerificationConfig.Security(bool(securityMap, "enabled", true), vulnerabilityScan),
                llm, report, sandbox, scenarios);
        return new Loaded(config, source, warnings);
    }

    @SuppressWarnings("unchecked")
    private static List<Scenario> parseScenarios(Object value, List<String> warnings) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Scenario> scenarios = new ArrayList<>();
        for (Object element : list) {
            if (!(element instanceof Map)) {
                warnings.add("ignoring scenario entry that is not a mapping: " + element);
                continue;
            }
            Map<String, Object> map = (Map<String, Object>) element;
            String id = string(map, "id", null);
            String requirement = string(map, "requirement", null);
            if (id == null || requirement == null) {
                warnings.add("ignoring scenario without id and requirement");
                continue;
            }
            List<Scenario.Step> steps = new ArrayList<>();
            Object stepsValue = map.get("steps");
            if (stepsValue instanceof List<?> stepList) {
                for (Object stepElement : stepList) {
                    if (!(stepElement instanceof Map)) {
                        continue;
                    }
                    Map<String, Object> stepMap = (Map<String, Object>) stepElement;
                    steps.add(new Scenario.Step(
                            string(stepMap, "call", ""),
                            parseInputs(stepMap.get("inputs")),
                            string(stepMap, "description", null)));
                }
            }
            Scenario scenario = new Scenario(
                    id,
                    requirement,
                    string(map, "description", null),
                    parseMode(string(map, "mode", "single")),
                    string(map, "setup", null),
                    steps,
                    string(map, "observation", null),
                    string(map, "invariant", null),
                    integer(map, "cases", 100),
                    longValue(map, "seed", 42L),
                    integer(map, "timeout_seconds", 120));
            scenarios.add(scenario);
        }
        return scenarios;
    }

    @SuppressWarnings("unchecked")
    private static List<Scenario.Input> parseInputs(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Scenario.Input> inputs = new ArrayList<>();
        for (Object element : list) {
            if (!(element instanceof Map)) {
                continue;
            }
            Map<String, Object> map = (Map<String, Object>) element;
            String name = string(map, "name", null);
            String type = string(map, "type", null);
            if (name == null || type == null) {
                continue;
            }
            String domainName = string(map, "domain", null);
            Scenario.Domain domain = domainName == null ? Scenario.Domain.fromType(type)
                    : Scenario.Domain.valueOf(domainName.trim().toUpperCase(java.util.Locale.ROOT));
            Map<String, Object> range = mapOf(map, "range");
            inputs.add(new Scenario.Input(
                    name,
                    type,
                    domain,
                    stringList(map, "values"),
                    string(range, "min", null),
                    string(range, "max", null),
                    integer(range, "scale", 2)));
        }
        return inputs;
    }

    private static Scenario.Mode parseMode(String value) {
        try {
            return Scenario.Mode.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException e) {
            return Scenario.Mode.SINGLE;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private static String string(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    /**
     * Parses the severity at which a dependency advisory fails a requirement.
     *
     * <p>Absent defaults to {@code HIGH}. {@code none} means advisories are reported but fail nothing.
     * An unrecognised value falls back to {@code HIGH} rather than to "never": a typo should tighten the
     * gate, not quietly remove it.
     *
     * @return the threshold, or {@code null} when advisories must never fail a requirement
     */
    private static Severity failOnSeverity(String value) {
        if (value == null || value.isBlank()) {
            return Severity.HIGH;
        }
        String normalized = value.strip().toUpperCase(java.util.Locale.ROOT);
        if (normalized.equals("NONE") || normalized.equals("OFF")) {
            return null;
        }
        try {
            // UNKNOWN is accepted here but can never match, because nothing is at least as severe as an
            // advisory that stated no severity.
            return Severity.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return Severity.HIGH;
        }
    }

    private static boolean bool(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        return fallback;
    }

    private static int integer(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    private static long longValue(Map<String, Object> map, String key, long fallback) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    private static double decimal(Map<String, Object> map, String key, double fallback) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return fallback;
    }

    private static Path path(Map<String, Object> map, String key) {
        String value = string(map, key, null);
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    private static List<String> stringList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object element : list) {
                if (element != null) {
                    result.add(String.valueOf(element));
                }
            }
            return result;
        }
        if (value instanceof String s && !s.isBlank()) {
            return List.of(s.split("\\s*,\\s*"));
        }
        return List.of();
    }

    /**
     * A loaded configuration together with its provenance.
     *
     * @param config   effective configuration
     * @param source   file it was loaded from, or {@code null} when defaults were used
     * @param warnings non-fatal problems found while parsing
     */
    public record Loaded(VerificationConfig config, Path source, List<String> warnings) {

        public boolean fromFile() {
            return source != null;
        }
    }
}
