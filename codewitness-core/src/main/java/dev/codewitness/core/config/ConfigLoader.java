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
import java.util.Set;

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

    /** Keys understood at the root of {@code codewitness.yaml}. */
    private static final Set<String> ROOT_KEYS = Set.of("project", "language", "verification", "tests",
            "mutation", "property_testing", "static_analysis", "security", "llm", "report", "sandbox",
            "database", "scenarios", "findings");

    /** Keys understood inside each top-level section. */
    private static final Map<String, Set<String>> SECTION_KEYS = Map.ofEntries(
            Map.entry("project", Set.of("name")),
            Map.entry("language", Set.of("adapter")),
            Map.entry("verification", Set.of("timeout", "max_duration", "max_test_generation_iterations",
                    "max_generated_tests", "max_mutation_time", "fail_fast_on_counterexample")),
            Map.entry("tests", Set.of("enabled")),
            Map.entry("mutation", Set.of("enabled", "tool", "timeout", "target_classes")),
            Map.entry("property_testing", Set.of("enabled", "max_scenarios", "concurrent")),
            Map.entry("static_analysis", Set.of("enabled", "analyzers", "lint_all")),
            Map.entry("security", Set.of("enabled", "vulnerabilities")),
            Map.entry("llm", Set.of("enabled", "provider", "model", "base_url", "api_key_env", "max_calls",
                    "temperature", "timeout")),
            Map.entry("report", Set.of("html", "json", "output_directory", "keep_generated_tests", "sarif",
                    "keep_runs")),
            Map.entry("sandbox", Set.of("mode", "image", "allow_network", "container_workdir")),
            Map.entry("database", Set.of("enabled", "engine", "schema", "seed", "url", "timeout")),
            Map.entry("findings", Set.of("suppress", "baseline")));

    /** Keys understood inside one entry of the {@code findings.suppress} list. */
    private static final Set<String> SUPPRESSION_KEYS = Set.of("rule", "path", "reason");

    /** Keys understood inside {@code security.vulnerabilities}. */
    private static final Set<String> VULNERABILITY_KEYS = Set.of("enabled", "source", "endpoint", "timeout",
            "max_dependencies", "fail_on");

    /** Keys understood inside one entry of the {@code scenarios} list. */
    private static final Set<String> SCENARIO_KEYS = Set.of("id", "requirement", "description", "mode",
            "setup", "steps", "observation", "invariant", "cases", "seed", "timeout_seconds", "command",
            "expect");

    /** Keys understood inside {@code expect} of a {@code process} scenario. */
    private static final Set<String> EXPECT_KEYS = Set.of("exit_code", "stdout_contains", "stderr_contains");

    /** Keys understood inside one entry of a scenario's {@code steps} list. */
    private static final Set<String> STEP_KEYS = Set.of("call", "inputs", "description");

    /** Keys understood inside one declared scenario input. */
    private static final Set<String> INPUT_KEYS = Set.of("name", "type", "domain", "values", "range");

    /** Keys understood inside an input's {@code range}. */
    private static final Set<String> RANGE_KEYS = Set.of("min", "max", "scale");

    /**
     * Rejects configuration keys that CodeWitness does not understand.
     *
     * <p>The loader used to ignore them. That is the wrong default for a verification tool: a typo
     * such as {@code securty:} or {@code vulnerability.enabled:} silently leaves the conservative
     * default in place, so the run looks like it did something the user did not ask for and the report
     * is quietly weaker than the configuration suggests. A misspelled key is now a hard error that
     * names the key and lists what is accepted here.
     */
    private static void rejectUnknownKeys(Map<String, Object> map, String path, Set<String> known) {
        if (map == null || map.isEmpty()) {
            return;
        }
        List<String> unknown = map.keySet().stream()
                .filter(key -> !known.contains(key))
                .sorted()
                .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("unknown configuration key(s) at " + path + ": "
                    + String.join(", ", unknown) + ". Keys understood here: "
                    + known.stream().sorted().collect(java.util.stream.Collectors.joining(", "))
                    + ". CodeWitness refuses to ignore configuration it does not understand, because "
                    + "ignoring a key silently keeps the default and would weaken verification.");
        }
    }

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
        rejectUnknownKeys(map, "the configuration root", ROOT_KEYS);

        VerificationConfig defaults = VerificationConfig.defaults();

        Map<String, Object> projectMap = mapOf(map, "project");
        Map<String, Object> languageMap = mapOf(map, "language");
        rejectUnknownKeys(projectMap, "project", SECTION_KEYS.get("project"));
        rejectUnknownKeys(languageMap, "language", SECTION_KEYS.get("language"));

        VerificationConfig.Project project = new VerificationConfig.Project(
                string(projectMap, "name", defaults.project().name()));

        VerificationConfig.Language language = new VerificationConfig.Language(
                string(languageMap, "adapter", defaults.language().adapter()));

        Map<String, Object> verificationMap = mapOf(map, "verification");
        Map<String, Object> testsMap = mapOf(map, "tests");
        Map<String, Object> mutationMap = mapOf(map, "mutation");
        Map<String, Object> propertyMap = mapOf(map, "property_testing");
        Map<String, Object> staticMap = mapOf(map, "static_analysis");
        Map<String, Object> securityMap = mapOf(map, "security");
        Map<String, Object> llmMap = mapOf(map, "llm");
        Map<String, Object> reportMap = mapOf(map, "report");
        Map<String, Object> sandboxMap = mapOf(map, "sandbox");
        Map<String, Object> databaseMap = mapOf(map, "database");
        Map<String, Object> findingsMap = mapOf(map, "findings");
        for (Map.Entry<String, Set<String>> section : SECTION_KEYS.entrySet()) {
            if (section.getKey().equals("project") || section.getKey().equals("language")) {
                continue;
            }
            rejectUnknownKeys(mapOf(map, section.getKey()), section.getKey(), section.getValue());
        }
        rejectUnknownKeys(mapOf(securityMap, "vulnerabilities"), "security.vulnerabilities",
                VULNERABILITY_KEYS);
        VerificationConfig.Findings findings = new VerificationConfig.Findings(
                parseSuppressions(findingsMap.get("suppress")),
                path(findingsMap, "baseline"));

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
                bool(reportMap, "keep_generated_tests", false),
                bool(reportMap, "sarif", defaults.report().sarif()),
                integer(reportMap, "keep_runs", defaults.report().keepRuns()));

        VerificationConfig.Sandbox sandbox = new VerificationConfig.Sandbox(
                string(sandboxMap, "mode", defaults.sandbox().mode()),
                string(sandboxMap, "image", defaults.sandbox().image()),
                bool(sandboxMap, "allow_network", false),
                string(sandboxMap, "container_workdir", defaults.sandbox().containerWorkdir()));

        VerificationConfig.Database database = new VerificationConfig.Database(
                bool(databaseMap, "enabled", defaults.database().enabled()),
                string(databaseMap, "engine", defaults.database().engine()),
                path(databaseMap, "schema"),
                path(databaseMap, "seed"),
                string(databaseMap, "url", null),
                DurationParser.parse(string(databaseMap, "timeout", null), defaults.database().timeout()));

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
                llm, report, sandbox, scenarios, database, findings);
        return new Loaded(config, source, warnings);
    }

    /**
     * Parses {@code findings.suppress}.
     *
     * <p>Every entry needs a rule id, because a suppression without one would either suppress nothing or
     * suppress everything. The reason is optional to write but strongly recommended, and is carried into the
     * report: a suppression nobody can explain is indistinguishable from a mistake months later.
     *
     * @param value the raw {@code suppress} value, may be {@code null}
     * @return parsed entries, never {@code null}
     * @throws IllegalArgumentException when the value is not a list of mappings, an entry has no rule, or an
     *     entry has a key that is not understood
     */
    @SuppressWarnings("unchecked")
    private static List<VerificationConfig.Findings.Suppression> parseSuppressions(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("findings.suppress must be a list of mappings with a rule, an "
                    + "optional path and an optional reason");
        }
        List<VerificationConfig.Findings.Suppression> suppressions = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("findings.suppress entries must be mappings, found: " + entry);
            }
            Map<String, Object> map = (Map<String, Object>) raw;
            rejectUnknownKeys(map, "findings.suppress", SUPPRESSION_KEYS);
            String rule = string(map, "rule", null);
            if (rule == null || rule.isBlank()) {
                throw new IllegalArgumentException("findings.suppress entry has no rule: " + map
                        + ". A suppression without a rule id would silently cover every finding.");
            }
            suppressions.add(new VerificationConfig.Findings.Suppression(rule,
                    string(map, "path", null), string(map, "reason", null)));
        }
        return suppressions;
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
            rejectUnknownKeys(map, "scenario " + string(map, "id", "(without an id)"), SCENARIO_KEYS);
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
                    rejectUnknownKeys(stepMap, "a step of scenario " + id, STEP_KEYS);
                    steps.add(new Scenario.Step(
                            string(stepMap, "call", ""),
                            parseInputs(stepMap.get("inputs"), "scenario " + id),
                            string(stepMap, "description", null)));
                }
            }
            Scenario.Mode mode = parseMode(string(map, "mode", "single"));
            String observation = string(map, "observation", null);
            String invariant = string(map, "invariant", null);
            if (mode != Scenario.Mode.PROCESS && (observation == null || invariant == null)) {
                // Not fatal - the scenario still executes its steps - but it asserts nothing, so it can
                // never discharge an obligation. Saying so here is the difference between a user who
                // knows their scenario proves nothing and one who reads VERIFIED and believes it.
                warnings.add("scenario " + id + " declares "
                        + (observation == null ? "no observation" : "an observation but no invariant")
                        + ", so the generated test runs the steps but asserts nothing. It cannot make "
                        + requirement + " VERIFIED; declare both `observation` and `invariant`.");
            }
            List<String> command = parseCommand(map.get("command"), id);
            Scenario.ProcessExpectation expect = parseExpectation(map.get("expect"), id);
            if (mode == Scenario.Mode.PROCESS && (observation != null || invariant != null)) {
                // The two vocabularies are not interchangeable: `observation` and `invariant` are expressions in the
                // project's language, evaluated inside a generated test, and there is no generated test here.
                // Accepting them and ignoring them would leave a scenario asserting nothing while reading as though
                // it asserted something.
                throw new IllegalArgumentException("scenario " + id + " has mode process and also declares "
                        + (observation != null ? "observation" : "invariant") + ". A process scenario asserts with "
                        + "`expect`, not with `observation`/`invariant`: those are evaluated inside a generated test, "
                        + "and a process scenario generates none.");
            }
            if (mode != Scenario.Mode.PROCESS && expect != null) {
                throw new IllegalArgumentException("scenario " + id + " declares an `expect` block but its mode is "
                        + mode.name().toLowerCase(java.util.Locale.ROOT) + "; `expect` is only read in mode process.");
            }
            Scenario scenario = new Scenario(
                    id,
                    requirement,
                    string(map, "description", null),
                    mode,
                    string(map, "setup", null),
                    steps,
                    observation,
                    invariant,
                    integer(map, "cases", 100),
                    longValue(map, "seed", 42L),
                    integer(map, "timeout_seconds", 120),
                    command,
                    expect);
            scenarios.add(scenario);
        }
        return scenarios;
    }

    /**
     * Parses a process scenario's {@code command}.
     *
     * <p>A list of arguments is required rather than a shell string: no shell means no quoting rules to get wrong,
     * no word splitting to be surprised by, and no place for a value to turn into a second command. A single string
     * is also accepted when it is one argument with no spaces, so a trivial {@code command: ./tool} reads naturally.
     *
     * @param value raw value, may be {@code null}
     * @param id    scenario id, for the error message
     * @return argv, empty when absent
     */
    private static List<String> parseCommand(Object value, String id) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<String> argv = new ArrayList<>();
            for (Object element : list) {
                if (element == null || String.valueOf(element).isBlank()) {
                    throw new IllegalArgumentException("scenario " + id + " has an empty entry in its command");
                }
                argv.add(String.valueOf(element));
            }
            return argv;
        }
        String single = String.valueOf(value).trim();
        return single.isEmpty() ? List.of() : List.of(single);
    }

    /**
     * Parses a process scenario's {@code expect} block.
     *
     * @param value raw value, may be {@code null}
     * @param id    scenario id, for the error message
     * @return the expectation, or {@code null} when absent
     */
    @SuppressWarnings("unchecked")
    private static Scenario.ProcessExpectation parseExpectation(Object value, String id) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("scenario " + id + ": `expect` must be a mapping with exit_code, "
                    + "stdout_contains and/or stderr_contains");
        }
        Map<String, Object> map = (Map<String, Object>) raw;
        rejectUnknownKeys(map, "expect of scenario " + id, EXPECT_KEYS);
        Integer exitCode = map.get("exit_code") == null ? null : integer(map, "exit_code", 0);
        Scenario.ProcessExpectation expectation = new Scenario.ProcessExpectation(exitCode,
                stringList(map, "stdout_contains"), stringList(map, "stderr_contains"));
        if (!expectation.hasAssertions()) {
            throw new IllegalArgumentException("scenario " + id + ": `expect` states nothing to assert, so the "
                    + "command would run and prove nothing");
        }
        return expectation;
    }

    @SuppressWarnings("unchecked")
    private static List<Scenario.Input> parseInputs(Object value, String context) {
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
            rejectUnknownKeys(map, "an input of " + context, INPUT_KEYS);
            if (name == null || type == null) {
                continue;
            }
            String domainName = string(map, "domain", null);
            Scenario.Domain domain = Scenario.Domain.parse(domainName, Scenario.Domain.fromType(type));
            Map<String, Object> range = mapOf(map, "range");
            rejectUnknownKeys(range, "the range of input \"" + name + "\" in " + context, RANGE_KEYS);
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
            throw new IllegalArgumentException("unknown scenario mode \"" + value.trim()
                    + "\"; known modes are single, sequence, concurrent and process", e);
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
