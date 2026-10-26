package dev.codewitness.core.config;

import dev.codewitness.core.model.Severity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loader rejects configuration it does not understand and hard-fails on a malformed file: a
 * configuration mistake must never quietly weaken verification.
 */
class ConfigLoaderTest {



    @Test
    @DisplayName("native_test parses on a process scenario, and is rejected on any other mode")
    void parsesNativeTest() {
        // A native test moves the assertion into the project's own suite, so it is a deliberate choice about where the
        // guarantee lives. On a non-process mode it would mean nothing, because those modes already generate a test.
        ConfigLoader.Loaded loaded = load("""
                scenarios:
                  - id: SC-EXIT
                    requirement: R-001
                    mode: process
                    native_test: true
                    command: ["./tool"]
                    expect:
                      exit_code: 3
                """);
        assertTrue(loaded.config().scenarios().getFirst().nativeTest());

        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class, () -> load("""
                scenarios:
                  - id: SC-ROLL
                    requirement: R-001
                    mode: single
                    native_test: true
                    steps:
                      - call: "f(${a})"
                        inputs:
                          - {name: a, type: int, domain: integer, values: ["1"]}
                    observation: "a"
                    invariant: "observation == 1"
                """));
        assertTrue(rejected.getMessage().contains("native_test"), rejected.getMessage());
    }

    @Test
    @DisplayName("findings.suppress parses a rule, an optional path and a reason")
    void parsesSuppressions() {
        // A rule that fires on correct code cannot be argued with in a pull request, so suppression is the
        // narrowing answer to a false positive. It has to be expressible, and it has to be recorded.
        ConfigLoader.Loaded loaded = load("""
                findings:
                  suppress:
                    - rule: CW-CONC-001
                      reason: immutable model class
                    - rule: CW-SEC-004
                      path: src/main/java/dev/example/Sandbox.java
                      reason: the sandbox executes commands by design
                """);

        List<VerificationConfig.Findings.Suppression> suppressed = loaded.config().findings().suppress();
        assertEquals(2, suppressed.size());
        assertEquals("CW-CONC-001", suppressed.get(0).rule());
        assertNull(suppressed.get(0).path(), "no path means every file");
        assertEquals("immutable model class", suppressed.get(0).reason());
        assertEquals("src/main/java/dev/example/Sandbox.java", suppressed.get(1).path());
    }

    @Test
    @DisplayName("a suppression matches a rule everywhere, or only in the paths it names")
    void suppressionsMatchByRuleAndPath() {
        VerificationConfig.Findings.Suppression everywhere =
                new VerificationConfig.Findings.Suppression("CW-CONC-001", null, "reason");
        VerificationConfig.Findings.Suppression narrow = new VerificationConfig.Findings.Suppression(
                "CW-CONC-001", "src/main/java/dev/example/Evidence.java", "reason");

        assertTrue(everywhere.matches("CW-CONC-001", "anything/at/all.java"));
        assertFalse(everywhere.matches("CW-SEC-001", "anything/at/all.java"), "the rule must match exactly");
        assertTrue(narrow.matches("CW-CONC-001", "src/main/java/dev/example/Evidence.java"));
        assertFalse(narrow.matches("CW-CONC-001", "src/main/java/dev/example/Other.java"));
    }

    @Test
    @DisplayName("a suppression with no rule is rejected rather than suppressing everything")
    void aSuppressionWithoutARuleIsRejected() {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class, () ->
                load("""
                        findings:
                          suppress:
                            - reason: I do not like this finding
                        """));

        assertTrue(rejected.getMessage().contains("rule"), rejected.getMessage());
    }

    @Test
    @DisplayName("an unknown key inside a suppression is rejected")
    void anUnknownSuppressionKeyIsRejected() {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class, () ->
                load("""
                        findings:
                          suppress:
                            - rule: CW-CONC-001
                              why: a key that is not understood
                        """));

        assertTrue(rejected.getMessage().contains("findings.suppress"), rejected.getMessage());
    }


    @Test
    @DisplayName("report.keep_runs parses, and defaults to keeping everything")
    void parsesRunRetention() {
        assertEquals(0, load("").config().report().keepRuns(), "retention is off unless asked for");
        assertEquals(5, load("""
                report:
                  keep_runs: 5
                """).config().report().keepRuns());
    }

    private static ConfigLoader.Loaded load(String yaml) {
        return ConfigLoader.parse(yaml, Path.of("codewitness.yaml"));
    }

    @Test
    @DisplayName("defaults disable the language model and deny network access")
    void defaultsAreConservative() {
        VerificationConfig defaults = VerificationConfig.defaults();

        assertFalse(defaults.llm().enabled());
        assertEquals("none", defaults.llm().provider());
        assertFalse(defaults.sandbox().allowNetwork());
        assertFalse(defaults.limits().failFastOnCounterexample());
        assertTrue(defaults.tests().enabled());
        assertTrue(defaults.scenarios().isEmpty());
    }

    @Test
    @DisplayName("a missing configuration file falls back to defaults")
    void missingFileReturnsDefaults(@TempDir Path dir) {
        ConfigLoader.Loaded loaded = ConfigLoader.load(dir.resolve("nope.yaml"));

        assertFalse(loaded.fromFile());
        assertNull(loaded.source());
        assertEquals(VerificationConfig.defaults().fingerprint(), loaded.config().fingerprint());
        assertTrue(loaded.warnings().isEmpty());
    }

    @Test
    @DisplayName("loadFromProject looks for codewitness.yaml in the project root")
    void loadFromProjectFindsTheDefaultFile(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(ConfigLoader.DEFAULT_FILE_NAME),
                "project:\n  name: demo\n", StandardCharsets.UTF_8);

        ConfigLoader.Loaded loaded = ConfigLoader.loadFromProject(dir);

        assertTrue(loaded.fromFile());
        assertEquals("demo", loaded.config().project().name());
    }

    @Test
    @DisplayName("an empty or blank file yields the defaults")
    void blankFileYieldsDefaults() {
        assertEquals(VerificationConfig.defaults().fingerprint(), load("").config().fingerprint());
        assertEquals(VerificationConfig.defaults().fingerprint(), load("   \n").config().fingerprint());
    }

    @Test
    @DisplayName("a YAML file overrides project and language values")
    void yamlOverridesProjectAndLanguage() {
        VerificationConfig config = load("""
                project:
                  name: withdrawal-ledger
                language:
                  adapter: java
                """).config();

        assertEquals("withdrawal-ledger", config.project().name());
        assertEquals("java", config.language().adapter());
    }

    @Test
    @DisplayName("a database section is parsed, and no database is prepared unless it is asked for")
    void databaseSectionIsParsed() {
        VerificationConfig off = load("").config();
        assertFalse(off.database().enabled(), "preparing a database is opt-in");
        assertEquals("sqlite", off.database().engine());
        assertNull(off.database().schema());

        VerificationConfig config = load("""
                database:
                  enabled: true
                  engine: sqlite
                  schema: db/schema.sql
                  seed: db/seed.sql
                  timeout: 45s
                """).config();

        assertTrue(config.database().enabled());
        assertEquals("sqlite", config.database().engine());
        assertEquals(Path.of("db/schema.sql"), config.database().schema());
        assertEquals(Path.of("db/seed.sql"), config.database().seed());
        assertEquals(Duration.ofSeconds(45), config.database().timeout());
    }

    @Test
    @DisplayName("a misspelled key under database is rejected like a misspelled key anywhere else")
    void unknownDatabaseKeyIsRejected() {
        // The whole point of the strict loader: a typo must not silently leave the default in place and
        // produce a run that looks like it used a database it never prepared.
        assertThrows(IllegalArgumentException.class, () -> load("database:\n  schmea: db/schema.sql\n"));
    }

    @Test
    @DisplayName("15m, 90s and 2h durations parse from the verification and mutation sections")
    void yamlParsesHumanDurations() {
        VerificationConfig config = load("""
                verification:
                  timeout: 15m
                  max_duration: 90s
                  max_mutation_time: 2h
                mutation:
                  timeout: 30m
                """).config();

        assertEquals(Duration.ofMinutes(15), config.limits().timeout());
        assertEquals(Duration.ofSeconds(90), config.limits().maxDuration());
        assertEquals(Duration.ofHours(2), config.limits().maxMutationTime());
        assertEquals(Duration.ofMinutes(30), config.mutation().timeout());
    }

    @Test
    @DisplayName("numeric limits and flags override the defaults")
    void yamlOverridesNumericLimits() {
        VerificationConfig config = load("""
                verification:
                  max_test_generation_iterations: 9
                  max_generated_tests: 25
                  fail_fast_on_counterexample: true
                """).config();

        assertEquals(9, config.limits().maxTestGenerationIterations());
        assertEquals(25, config.limits().maxGeneratedTests());
        assertTrue(config.limits().failFastOnCounterexample());
    }

    @Test
    @DisplayName("the llm section is parsed but disabled unless explicitly enabled")
    void llmSectionIsParsed() {
        VerificationConfig disabledByDefault = load("""
                llm:
                  provider: openai
                  model: gpt-4o-mini
                """).config();
        assertFalse(disabledByDefault.llm().enabled());
        assertEquals("openai", disabledByDefault.llm().provider());

        VerificationConfig enabled = load("""
                llm:
                  enabled: true
                  provider: openai
                  model: gpt-4o-mini
                  api_key_env: MY_KEY
                  max_calls: 12
                  temperature: 0.5
                """).config();
        assertTrue(enabled.llm().enabled());
        assertEquals("MY_KEY", enabled.llm().apiKeyEnvironmentVariable());
        assertEquals(12, enabled.llm().maxCalls());
        assertEquals(0.5, enabled.llm().temperature(), 1e-9);
    }

    @Test
    @DisplayName("advisory scanning is parsed but off unless explicitly enabled")
    void advisoryScanSectionIsParsed() {
        VerificationConfig offByDefault = load("""
                security:
                  enabled: true
                  vulnerabilities:
                    endpoint: https://api.osv.dev
                """).config();
        assertFalse(offByDefault.security().vulnerabilities().enabled(),
                "consulting a third party must be opt-in");
        assertEquals("osv", offByDefault.security().vulnerabilities().source());
        assertEquals(Severity.HIGH, offByDefault.security().vulnerabilities().failOn(),
                "the contract is that a HIGH advisory fails a requirement");

        VerificationConfig enabled = load("""
                security:
                  vulnerabilities:
                    enabled: true
                    source: osv
                    endpoint: https://mirror.internal/osv
                    timeout: 5s
                    max_dependencies: 25
                """).config();
        assertTrue(enabled.security().vulnerabilities().enabled());
        assertEquals("https://mirror.internal/osv", enabled.security().vulnerabilities().endpoint());
        assertEquals(Duration.ofSeconds(5), enabled.security().vulnerabilities().timeout());
        assertEquals(25, enabled.security().vulnerabilities().maxDependencies());

        assertNull(load("""
                security:
                  vulnerabilities:
                    fail_on: none
                """).config().security().vulnerabilities().failOn(),
                "fail_on: none reports advisories without failing anything");
        assertEquals(Severity.CRITICAL, load("""
                security:
                  vulnerabilities:
                    fail_on: critical
                """).config().security().vulnerabilities().failOn());
        assertEquals(Severity.HIGH, load("""
                security:
                  vulnerabilities:
                    fail_on: hgih
                """).config().security().vulnerabilities().failOn(),
                "a typo must tighten the gate, never quietly remove it");
    }

    @Test
    @DisplayName("the scenarios block is parsed including steps and inputs")
    void scenariosBlockIsParsed() {
        VerificationConfig config = load("""
                scenarios:
                  - id: SC-ROLLING
                    requirement: R-001
                    description: rolling withdrawal limit
                    mode: sequence
                    setup: "Ledger ledger = new Ledger();"
                    cases: 40
                    seed: 7
                    timeout_seconds: 30
                    observation: "ledger.balance()"
                    invariant: "observation.compareTo(limit) <= 0"
                    steps:
                      - call: "ledger.withdraw(${amount})"
                        description: withdraw an amount
                        inputs:
                          - name: amount
                            type: java.math.BigDecimal
                            values:
                              - "999.99"
                              - "1000"
                              - "1000.01"
                            range:
                              min: "0"
                              max: "5000"
                              scale: 2
                """).config();

        assertEquals(1, config.scenarios().size());
        Scenario scenario = config.scenarios().get(0);
        assertEquals("SC-ROLLING", scenario.id());
        assertEquals("R-001", scenario.requirement());
        assertEquals(Scenario.Mode.SEQUENCE, scenario.mode());
        assertEquals(40, scenario.cases());
        assertEquals(7L, scenario.seed());
        assertEquals(30, scenario.timeoutSeconds());
        assertEquals("ledger.balance()", scenario.observation());
        assertEquals("Ledger ledger = new Ledger();", scenario.setup());
        assertEquals(1, scenario.steps().size());

        Scenario.Step step = scenario.steps().get(0);
        assertEquals("ledger.withdraw(${amount})", step.call());
        assertEquals(1, step.inputs().size());
        Scenario.Input input = step.inputs().get(0);
        assertEquals("amount", input.name());
        assertEquals("java.math.BigDecimal", input.type());
        assertEquals(Scenario.Domain.DECIMAL, input.domain());
        assertEquals(List.of("999.99", "1000", "1000.01"), input.values());
        assertEquals(0, input.minDecimal().compareTo(new java.math.BigDecimal("0")));
        assertEquals(0, input.maxDecimal().compareTo(new java.math.BigDecimal("5000")));
        assertEquals(2, input.scale());
    }

    @Test
    @DisplayName("a scenario's mode defaults to SINGLE and seed/cases defaults are applied")
    void scenarioDefaultsAreApplied() {
        VerificationConfig config = load("""
                scenarios:
                  - id: SC-1
                    requirement: R-001
                    steps:
                      - call: "ledger.run()"
                """).config();

        Scenario scenario = config.scenarios().get(0);
        assertEquals(Scenario.Mode.SINGLE, scenario.mode());
        assertEquals(100, scenario.cases());
        assertEquals(42L, scenario.seed());
        assertEquals(120, scenario.timeoutSeconds());
    }

    @Test
    @DisplayName("an explicitly declared input domain wins over the Java type")
    void explicitInputDomainWins() {
        VerificationConfig config = load("""
                scenarios:
                  - id: SC-1
                    requirement: R-001
                    steps:
                      - call: "ledger.run(${n})"
                        inputs:
                          - name: n
                            type: java.lang.String
                            domain: integer
                            values: ["1", "2"]
                """).config();

        assertEquals(Scenario.Domain.INTEGER, config.scenarios().get(0).steps().get(0).inputs().get(0).domain());
    }

    @Test
    @DisplayName("a scenario without id or requirement is skipped with a warning")
    void incompleteScenarioIsSkippedWithWarning() {
        ConfigLoader.Loaded loaded = load("""
                scenarios:
                  - requirement: R-001
                    steps:
                      - call: "ledger.run()"
                  - id: SC-2
                    steps:
                      - call: "ledger.run()"
                """);

        assertTrue(loaded.config().scenarios().isEmpty());
        assertEquals(2, loaded.warnings().size(), loaded.warnings().toString());
    }

    @Test
    @DisplayName("a scenario entry that is not a mapping produces a warning, not an error")
    void nonMappingScenarioProducesWarning() {
        ConfigLoader.Loaded loaded = load("""
                scenarios:
                  - just a string
                """);

        assertTrue(loaded.config().scenarios().isEmpty());
        assertEquals(1, loaded.warnings().size());
    }

    @Test
    @DisplayName("an unknown key is rejected instead of silently keeping the default")
    void unknownKeysAreRejected() {
        // A typo used to leave the conservative default in place, so the run looked like it did
        // something the user never asked for and the report was quietly weaker than the configuration
        // suggested. A misspelled key is now an error that names it.
        IllegalArgumentException atRoot = assertThrows(IllegalArgumentException.class,
                () -> load("something_new:\n  deeply:\n    nested: true\n"));
        assertTrue(atRoot.getMessage().contains("something_new"), atRoot.getMessage());

        IllegalArgumentException inSection = assertThrows(IllegalArgumentException.class,
                () -> load("project:\n  name: demo\n  future_setting: whatever\n"));
        assertTrue(inSection.getMessage().contains("future_setting"), inSection.getMessage());
        assertTrue(inSection.getMessage().contains("project"), inSection.getMessage());

        IllegalArgumentException inVerification = assertThrows(IllegalArgumentException.class,
                () -> load("verification:\n  another_future_key: 3\n"));
        assertTrue(inVerification.getMessage().contains("another_future_key"), inVerification.getMessage());
    }

    @Test
    @DisplayName("a misspelled key inside a nested section is rejected too")
    void nestedUnknownKeysAreRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> load("""
                        security:
                          enabled: true
                          vulnerabilities:
                            enable: true
                        """));

        assertTrue(error.getMessage().contains("enable"), error.getMessage());
        assertTrue(error.getMessage().contains("security.vulnerabilities"), error.getMessage());
    }

    @Test
    @DisplayName("the documented instantMillis domain parses, as does its underscored spelling")
    void instantMillisDomainParses() {
        // The scenario reference and the init template both teach `instantMillis`, but the enum constant
        // is INSTANT_MILLIS and a bare valueOf rejected it with a stack trace.
        for (String spelling : List.of("instantMillis", "instant_millis", "INSTANT_MILLIS")) {
            VerificationConfig config = load("""
                    scenarios:
                      - id: SC-1
                        requirement: R-001
                        observation: "ledger.total()"
                        invariant: "observation > 0"
                        steps:
                          - call: "ledger.at(${t})"
                            inputs:
                              - name: t
                                type: long
                                domain: %s
                    """.formatted(spelling)).config();

            assertEquals(Scenario.Domain.INSTANT_MILLIS,
                    config.scenarios().get(0).steps().get(0).inputs().get(0).domain(), spelling);
        }
    }

    @Test
    @DisplayName("an unknown input domain names the domain and lists the valid ones")
    void unknownDomainIsRejectedWithTheValidValues() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> load("""
                        scenarios:
                          - id: SC-1
                            requirement: R-001
                            steps:
                              - call: "ledger.run(${n})"
                                inputs:
                                  - name: n
                                    type: long
                                    domain: intger
                        """));

        assertTrue(error.getMessage().contains("intger"), error.getMessage());
        assertTrue(error.getMessage().contains("integer"), error.getMessage());
    }

    @Test
    @DisplayName("an unknown scenario mode is rejected rather than silently becoming single")
    void unknownModeIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> load("""
                        scenarios:
                          - id: SC-1
                            requirement: R-001
                            mode: sequential
                            steps:
                              - call: "ledger.run()"
                        """));

        assertTrue(error.getMessage().contains("sequential"), error.getMessage());
        assertTrue(error.getMessage().contains("concurrent"), error.getMessage());
    }

    @Test
    @DisplayName("a scenario with no oracle warns that it cannot make its requirement verified")
    void scenarioWithoutAnOracleWarns() {
        ConfigLoader.Loaded loaded = load("""
                scenarios:
                  - id: SC-1
                    requirement: R-001
                    steps:
                      - call: "ledger.run()"
                  - id: SC-2
                    requirement: R-002
                    observation: "ledger.total()"
                    steps:
                      - call: "ledger.run()"
                  - id: SC-3
                    requirement: R-003
                    observation: "ledger.total()"
                    invariant: "observation >= 0"
                    steps:
                      - call: "ledger.run()"
                """);

        assertEquals(2, loaded.warnings().size(), loaded.warnings().toString());
        assertTrue(loaded.warnings().get(0).contains("SC-1"), loaded.warnings().toString());
        assertTrue(loaded.warnings().get(0).contains("VERIFIED"), loaded.warnings().toString());
        assertTrue(loaded.warnings().get(1).contains("SC-2"), loaded.warnings().toString());
        assertEquals(3, loaded.config().scenarios().size(),
                "the scenarios still load: they execute their steps, they just assert nothing");
    }

    @Test
    @DisplayName("malformed YAML raises a clear exception naming the source")
    void malformedYamlRaisesClearException() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> load("verification:\n  timeout: [oops\n"));

        assertTrue(error.getMessage().contains("invalid YAML"), error.getMessage());
        assertTrue(error.getMessage().contains("codewitness.yaml"), error.getMessage());
    }

    @Test
    @DisplayName("a non-mapping root is rejected with a clear message")
    void nonMappingRootIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> load("- just\n- a list\n"));

        assertTrue(error.getMessage().contains("root must be a mapping"), error.getMessage());
    }

    @Test
    @DisplayName("static analysis analyzers are overridden as a list")
    void staticAnalyzersAreOverridden() {
        VerificationConfig config = load("""
                static_analysis:
                  enabled: false
                  analyzers: ["spotbugs", "errorprone"]
                  lint_all: true
                """).config();

        assertFalse(config.staticAnalysis().enabled());
        assertEquals(List.of("spotbugs", "errorprone"), config.staticAnalysis().analyzers());
        assertTrue(config.staticAnalysis().lintAll());
    }

    @Test
    @DisplayName("an empty static analyzer list keeps the defaults")
    void emptyAnalyzerListKeepsDefaults() {
        VerificationConfig config = load("""
                static_analysis:
                  analyzers: []
                """).config();

        assertEquals(VerificationConfig.StaticAnalysis.defaults().analyzers(), config.staticAnalysis().analyzers());
    }

    @Test
    @DisplayName("the sandbox section is parsed; network stays denied unless allowed explicitly")
    void sandboxSectionIsParsed() {
        VerificationConfig config = load("""
                sandbox:
                  mode: docker
                  image: eclipse-temurin:21-jdk
                  allow_network: true
                  container_workdir: /workspace
                """).config();

        assertEquals("docker", config.sandbox().mode());
        assertEquals("eclipse-temurin:21-jdk", config.sandbox().image());
        assertTrue(config.sandbox().allowNetwork());
        assertEquals("/workspace", config.sandbox().containerWorkdir());
    }

    @Test
    @DisplayName("the report section is parsed including an output directory")
    void reportSectionIsParsed() {
        VerificationConfig config = load("""
                report:
                  html: false
                  json: true
                  output_directory: reports/out
                  keep_generated_tests: true
                """).config();

        assertFalse(config.report().html());
        assertTrue(config.report().json());
        assertEquals(Path.of("reports/out"), config.report().outputDirectory());
        assertTrue(config.report().keepGeneratedTests());
    }
}
