package dev.codewitness.core.config;

import dev.codewitness.core.model.Severity;
import dev.codewitness.core.result.Budget;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The complete configuration of a verification run, loaded from {@code codewitness.yaml} and
 * overridden by command line flags.
 *
 * <p>Defaults are deliberately conservative: everything that executes project code is bounded by a
 * timeout, and anything that would need network access or a language model is off unless the user
 * turns it on.
 */
public record VerificationConfig(
        Project project,
        Language language,
        Limits limits,
        Toggle tests,
        Mutation mutation,
        PropertyTesting propertyTesting,
        StaticAnalysis staticAnalysis,
        Security security,
        Llm llm,
        Report report,
        Sandbox sandbox,
        List<Scenario> scenarios) {

    public VerificationConfig {
        project = project == null ? Project.defaults() : project;
        language = language == null ? Language.defaults() : language;
        limits = limits == null ? Limits.defaults() : limits;
        tests = tests == null ? Toggle.on() : tests;
        mutation = mutation == null ? Mutation.defaults() : mutation;
        propertyTesting = propertyTesting == null ? PropertyTesting.defaults() : propertyTesting;
        staticAnalysis = staticAnalysis == null ? StaticAnalysis.defaults() : staticAnalysis;
        security = security == null ? Security.defaults() : security;
        llm = llm == null ? Llm.disabled() : llm;
        report = report == null ? Report.defaults() : report;
        sandbox = sandbox == null ? Sandbox.defaults() : sandbox;
        scenarios = List.copyOf(scenarios == null ? List.of() : scenarios);
    }

    public static VerificationConfig defaults() {
        return new VerificationConfig(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** @return the budget implied by this configuration. */
    public Budget toBudget() {
        return new Budget(limits.maxDuration(), limits.maxTestGenerationIterations(),
                limits.maxGeneratedTests(), limits.maxMutationTime(), llm.maxCalls());
    }

    /** @return a stable hash of the effective configuration, recorded with every run. */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder();
        sb.append(project.name()).append('|').append(language.adapter()).append('|')
                .append(limits).append('|').append(tests.enabled()).append('|')
                .append(mutation.enabled()).append('|').append(mutation.tool()).append('|')
                .append(propertyTesting.enabled()).append('|').append(propertyTesting.maxScenarios()).append('|')
                .append(staticAnalysis.enabled()).append('|').append(staticAnalysis.analyzers()).append('|')
                .append(security.enabled()).append('|')
                .append(security.vulnerabilities().enabled()).append('|')
                .append(security.vulnerabilities().source()).append('|')
                .append(llm.enabled()).append('|').append(llm.provider()).append('|').append(llm.model()).append('|')
                .append(report).append('|').append(sandbox).append('|');
        for (Scenario scenario : scenarios) {
            sb.append(scenario.id()).append(':').append(scenario.requirement()).append(':')
                    .append(scenario.mode()).append(':').append(scenario.cases()).append(':')
                    .append(scenario.seed()).append(':').append(scenario.invariant()).append(';');
        }
        return dev.codewitness.core.util.Hashing.sha256(sb.toString());
    }

    /** Identity of the project, used when a build file does not provide one. */
    public record Project(String name) {
        public static Project defaults() {
            return new Project(null);
        }
    }

    /** Language adapter selection. */
    public record Language(String adapter) {
        public static Language defaults() {
            return new Language("auto");
        }
    }

    /** Global limits. Individual sections may narrow these; the engine never exceeds them. */
    public record Limits(
            Duration timeout,
            Duration maxDuration,
            int maxTestGenerationIterations,
            int maxGeneratedTests,
            Duration maxMutationTime,
            boolean failFastOnCounterexample) {

        public static Limits defaults() {
            return new Limits(Duration.ofMinutes(15), Duration.ofMinutes(15), 5, 500,
                    Duration.ofMinutes(10), false);
        }
    }

    /** Simple enabled/disabled switch for a verification area. */
    public record Toggle(boolean enabled) {
        public static Toggle on() {
            return new Toggle(true);
        }

        public static Toggle off() {
            return new Toggle(false);
        }
    }

    /** Mutation testing configuration. */
    public record Mutation(boolean enabled, String tool, Duration timeout, List<String> targetClasses) {
        public static Mutation defaults() {
            return new Mutation(true, "pitest", Duration.ofMinutes(10), List.of());
        }
    }

    /** Generated test and property search configuration. */
    public record PropertyTesting(boolean enabled, int maxScenarios, boolean concurrentScenarios) {
        public static PropertyTesting defaults() {
            return new PropertyTesting(true, 50, true);
        }
    }

    /** Static analysis configuration. */
    public record StaticAnalysis(boolean enabled, List<String> analyzers, boolean lintAll) {
        public static StaticAnalysis defaults() {
            return new StaticAnalysis(true, List.of("javac", "heuristics"), false);
        }
    }

    /** Security analysis configuration. */
    public record Security(boolean enabled, VulnerabilityScan vulnerabilities) {

        public Security {
            vulnerabilities = vulnerabilities == null ? VulnerabilityScan.disabled() : vulnerabilities;
        }

        /** Source analysis only; no advisory source is consulted. */
        public Security(boolean enabled) {
            this(enabled, VulnerabilityScan.disabled());
        }

        public static Security defaults() {
            return new Security(true, VulnerabilityScan.disabled());
        }
    }

    /**
     * Dependency advisory scanning.
     *
     * <p>Disabled by default, and deliberately so: it is the one part of a run that asks a third party
     * for information, so it is opt-in in the same way as the language model. The report always records
     * which service was asked and when - and, when it was not asked, says that the dependencies were
     * <em>not checked</em> rather than reporting zero vulnerabilities.
     *
     * @param enabled         whether to consult an advisory service at all
     * @param source          advisory source to query; {@code osv} is the only one implemented
     * @param endpoint        base URL of the service
     * @param timeout         per-request timeout
     * @param maxDependencies how many coordinates may be queried in one run
     * @param failOn          advisories at this severity or above fail the requirements their module
     *                        ships; {@code null} ({@code fail_on: none}) reports them without failing
     *                        anything, and {@link Severity#UNKNOWN} is never a failure because an
     *                        advisory that states no severity refutes nothing
     */
    public record VulnerabilityScan(
            boolean enabled,
            String source,
            String endpoint,
            Duration timeout,
            int maxDependencies,
            Severity failOn) {

        public VulnerabilityScan {
            source = source == null || source.isBlank() ? "osv" : source;
            endpoint = endpoint == null || endpoint.isBlank() ? "https://api.osv.dev" : endpoint;
            timeout = timeout == null || timeout.isZero() || timeout.isNegative()
                    ? Duration.ofSeconds(30) : timeout;
            maxDependencies = maxDependencies <= 0 ? 500 : maxDependencies;
        }

        /** The default contract: a HIGH or CRITICAL advisory fails the requirement it ships with. */
        public static VulnerabilityScan disabled() {
            return new VulnerabilityScan(false, "osv", "https://api.osv.dev", Duration.ofSeconds(30), 500,
                    Severity.HIGH);
        }
    }

    /** Language model configuration. The model is advisory and disabled by default. */
    public record Llm(
            boolean enabled,
            String provider,
            String model,
            String baseUrl,
            String apiKeyEnvironmentVariable,
            int maxCalls,
            double temperature,
            Duration timeout) {

        public static Llm disabled() {
            return new Llm(false, "none", null, null, "CODEWITNESS_LLM_API_KEY", 0, 0.2, Duration.ofSeconds(60));
        }
    }

    /** Report configuration. */
    public record Report(boolean html, boolean json, Path outputDirectory, boolean keepGeneratedTests) {
        public static Report defaults() {
            return new Report(true, true, null, false);
        }
    }

    /** Sandbox configuration. */
    public record Sandbox(String mode, String image, boolean allowNetwork, String containerWorkdir) {
        public static Sandbox defaults() {
            return new Sandbox("local-process", "eclipse-temurin:25-jdk", false, "/work");
        }
    }

    /** Copies this configuration with a different report output directory. */
    public VerificationConfig withOutputDirectory(Path directory) {
        return new VerificationConfig(project, language, limits, tests, mutation, propertyTesting,
                staticAnalysis, security, llm, new Report(report.html(), report.json(), directory,
                report.keepGeneratedTests()), sandbox, scenarios);
    }

    public VerificationConfig withBudget(Budget budget) {
        Limits updated = new Limits(limits.timeout(), budget.maxDuration(),
                budget.maxTestGenerationIterations(), budget.maxGeneratedTests(), budget.maxMutationTime(),
                limits.failFastOnCounterexample());
        Llm updatedLlm = new Llm(llm.enabled(), llm.provider(), llm.model(), llm.baseUrl(),
                llm.apiKeyEnvironmentVariable(), budget.maxLlmCalls(), llm.temperature(), llm.timeout());
        return new VerificationConfig(project, language, updated, tests, mutation, propertyTesting,
                staticAnalysis, security, updatedLlm, report, sandbox, scenarios);
    }

    public VerificationConfig withScenarios(List<Scenario> newScenarios) {
        return new VerificationConfig(project, language, limits, tests, mutation, propertyTesting,
                staticAnalysis, security, llm, report, sandbox, newScenarios);
    }

    /**
     * Copies this configuration with a different language adapter selection.
     *
     * <p>{@code auto} selects every adapter that claims part of the project, which is what a polyglot tree
     * needs. Naming one narrows the run to that language, which is how a reader insists on how their tree
     * is read.
     */
    public VerificationConfig withLanguage(Language newLanguage) {
        return new VerificationConfig(project, newLanguage, limits, tests, mutation, propertyTesting,
                staticAnalysis, security, llm, report, sandbox, scenarios);
    }

    /** @return a map representation suitable for embedding in a report. */
    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("project", Map.of("name", String.valueOf(project.name())));
        map.put("language", Map.of("adapter", language.adapter()));
        Map<String, Object> verification = new LinkedHashMap<>();
        verification.put("timeoutSeconds", limits.timeout().toSeconds());
        verification.put("maxDurationSeconds", limits.maxDuration().toSeconds());
        verification.put("maxTestGenerationIterations", limits.maxTestGenerationIterations());
        verification.put("maxGeneratedTests", limits.maxGeneratedTests());
        verification.put("maxMutationTimeSeconds", limits.maxMutationTime().toSeconds());
        map.put("verification", verification);
        map.put("tests", Map.of("enabled", tests.enabled()));
        map.put("mutation", Map.of("enabled", mutation.enabled(), "tool", mutation.tool()));
        map.put("propertyTesting", Map.of("enabled", propertyTesting.enabled(),
                "maxScenarios", propertyTesting.maxScenarios()));
        map.put("staticAnalysis", Map.of("enabled", staticAnalysis.enabled(),
                "analyzers", staticAnalysis.analyzers()));
        map.put("security", Map.of("enabled", security.enabled()));
        map.put("llm", Map.of("enabled", llm.enabled(), "provider", llm.provider(),
                "model", String.valueOf(llm.model())));
        map.put("sandbox", Map.of("mode", sandbox.mode(), "image", sandbox.image(),
                "allowNetwork", sandbox.allowNetwork()));
        List<Object> scenarioMaps = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            scenarioMaps.add(scenario.asMap());
        }
        map.put("scenarios", scenarioMaps);
        return map;
    }
}
