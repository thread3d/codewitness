package dev.codewitness.report;

import dev.codewitness.core.evidence.Evidence;
import dev.codewitness.core.evidence.EvidenceConfidence;
import dev.codewitness.core.evidence.EvidenceStatus;
import dev.codewitness.core.evidence.EvidenceType;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.DependencyVulnerability;
import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.ModuleInfo;
import dev.codewitness.core.model.MutationReport;
import dev.codewitness.core.model.MutantStatus;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.model.Severity;
import dev.codewitness.core.model.SurvivingMutant;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.requirements.ObligationKind;
import dev.codewitness.core.requirements.ObligationOrigin;
import dev.codewitness.core.requirements.Requirement;
import dev.codewitness.core.requirements.RequirementSet;
import dev.codewitness.core.requirements.RequirementStatus;
import dev.codewitness.core.requirements.VerificationObligation;
import dev.codewitness.core.result.Budget;
import dev.codewitness.core.result.BudgetUsage;
import dev.codewitness.core.result.Counterexample;
import dev.codewitness.core.result.EnvironmentSnapshot;
import dev.codewitness.core.result.ObligationOutcome;
import dev.codewitness.core.result.PhaseRecord;
import dev.codewitness.core.result.RequirementVerdict;
import dev.codewitness.core.result.RunStatus;
import dev.codewitness.core.result.VerificationReport;
import dev.codewitness.core.result.VerificationRun;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the shared {@link VerificationReport} fixtures used by both report writer test classes.
 *
 * <p>The fixture deliberately contains one VERIFIED requirement (backed by generated-test evidence),
 * one FAILED requirement with an executed counterexample, a surviving mutant, a failing test, a HIGH
 * security finding, a limitation string and a hostile (HTML-injecting) analyzer message.
 */
final class ReportFixtures {

    /** Untrusted analyzer message that must never be rendered verbatim into HTML. */
    static final String HOSTILE_MESSAGE = "<script>alert(1)</script>";

    static final String RUN_ID = "run-2024-05-01T10-15-00Z";
    static final Instant FINISHED_AT = Instant.parse("2024-05-01T10:15:30Z");
    static final Instant STARTED_AT = Instant.parse("2024-05-01T10:00:00Z");
    static final String LIMITATION =
            "Concurrency obligations could not be exercised: no harness drove parallel calls.";
    static final String COUNTEREXAMPLE_QUANTITY = "-5";
    static final String COUNTEREXAMPLE_ITEM = "widget";

    private ReportFixtures() {
    }

    static VerificationReport report() {
        return reportWithFindings(findings());
    }

    /** @return the standard fixture with a different set of analyzer findings. */
    static VerificationReport reportWithFindings(List<Finding> findings) {
        return VerificationReport.builder(RUN_ID, project(), requirements())
                .verdicts(List.of(verifiedVerdict(), failedVerdict()))
                .evidence(evidence())
                .counterexamples(List.of(counterexample()))
                .tests(tests())
                .coverage(coverage())
                .mutation(mutation())
                .findings(findings)
                .dependencies(dependencies())
                .run(run())
                .limitations(List.of(LIMITATION))
                .build();
    }

    /** @return the standard fixture with a single finding carrying the supplied message. */
    static VerificationReport reportWithFindingMessage(String message) {
        Finding finding = new Finding("custom-analyzer", "RULE-1", Severity.HIGH, FindingCategory.BUG,
                message, "src/main/java/dev/example/View.java", 7, "example-app", Map.of());
        return reportWithFindings(List.of(finding));
    }

    static ProjectRef projectRef() {
        return ProjectRef.of(Path.of("codewitness-report", "target", "fixtures", "example-project"));
    }

    private static ProjectInfo project() {
        ModuleInfo core = new ModuleInfo("example-core", Path.of("/tmp/example/example-core"), "jar",
                List.of(Path.of("/tmp/example/example-core/src/main/java")),
                List.of(Path.of("/tmp/example/example-core/src/test/java")), "17");
        ModuleInfo app = new ModuleInfo("example-app", Path.of("/tmp/example/example-app"), "jar",
                List.of(Path.of("/tmp/example/example-app/src/main/java")),
                List.of(Path.of("/tmp/example/example-app/src/test/java")), "17");
        return ProjectInfo.builder(projectRef())
                .language(LanguageId.JAVA)
                .buildSystem(BuildSystemId.MAVEN)
                .javaVersion("17")
                .addTestFramework("junit5")
                .modules(List.of(core, app))
                .build();
    }

    static RequirementSet requirements() {
        VerificationObligation upperBound = VerificationObligation.of("OB-001", "RQ-1",
                        "Cart totals at, just below and just above the limit of 1000 must behave as specified.",
                        ObligationKind.UPPER_BOUND, ObligationOrigin.REQUIREMENT_TEXT)
                .withTarget("dev.example.Cart#total");
        Requirement first = Requirement.builder("RQ-1",
                        "The cart total must never exceed the configured limit of 1000.")
                .title("Cart total upper bound")
                .source("requirements.md", 12)
                .obligations(List.of(upperBound))
                .build();

        VerificationObligation rejection = VerificationObligation.of("OB-002", "RQ-2",
                        "A negative quantity must be rejected with an error.",
                        ObligationKind.REJECTION, ObligationOrigin.REQUIREMENT_TEXT)
                .withTarget("dev.example.Cart#add");
        Requirement second = Requirement.builder("RQ-2",
                        "Adding a negative quantity must be rejected with an error.")
                .title("Negative quantity rejection")
                .source("requirements.md", 18)
                .obligations(List.of(rejection))
                .build();

        return new RequirementSet(List.of(first, second));
    }

    static RequirementVerdict verifiedVerdict() {
        ObligationOutcome outcome = new ObligationOutcome("OB-001", RequirementStatus.VERIFIED,
                "Generated boundary tests at 999.99, 1000 and 1000.01 behaved as required.",
                List.of("EV-001"), List.of(), 3);
        return new RequirementVerdict("RQ-1", RequirementStatus.VERIFIED,
                "A generated boundary test independently confirmed the upper bound.",
                List.of("EV-001", "EV-003"), List.of(outcome),
                List.of("Behaviour under concurrent adds was not exercised."), List.of(), true);
    }

    static RequirementVerdict failedVerdict() {
        ObligationOutcome outcome = new ObligationOutcome("OB-002", RequirementStatus.FAILED,
                "A property search found a negative quantity that was accepted.",
                List.of("EV-002"), List.of("CE-001"), 2);
        return new RequirementVerdict("RQ-2", RequirementStatus.FAILED,
                "Executing the cart with a negative quantity was accepted instead of rejected.",
                List.of("EV-002"), List.of(outcome), List.of(), List.of("CE-001"), true);
    }

    static List<Evidence> evidence() {
        Evidence generated = Evidence.builder("EV-001", EvidenceType.GENERATED_TEST)
                .runId(RUN_ID)
                .status(EvidenceStatus.SUPPORTS)
                .confidence(EvidenceConfidence.DETERMINISTIC)
                .description("Generated boundary test for the cart total limit.")
                .source("codewitness-generated-tests")
                .timestamp(Instant.parse("2024-05-01T10:05:00Z"))
                .command(List.of("mvn", "-q", "-Dtest=CartBoundaryTest", "test"))
                .input("limit", 1000)
                .output("Tests run: 1, Failures: 0, Errors: 0, Skipped: 0")
                .result("passed")
                .metadata("generatedTestId", "GT-001")
                .artifact(Path.of("target/surefire-reports/TEST-CartBoundaryTest.xml"))
                .requirement("RQ-1")
                .obligation("OB-001")
                .generatedByLlm(true)
                .executedIndependently(true)
                .reproducible(true)
                .reproductionCommand("mvn -q -Dtest=CartBoundaryTest test")
                .build();

        Evidence refutation = Evidence.builder("EV-002", EvidenceType.COUNTEREXAMPLE)
                .runId(RUN_ID)
                .status(EvidenceStatus.REFUTES)
                .confidence(EvidenceConfidence.DETERMINISTIC)
                .description("Executed property search accepted a negative quantity.")
                .source("codewitness-property-search")
                .timestamp(Instant.parse("2024-05-01T10:08:00Z"))
                .command(List.of("mvn", "-q", "-Dtest=CartPropertyTest", "test"))
                .output("expected: <rejected> but was: <accepted>")
                .result("failed")
                .requirement("RQ-2")
                .obligation("OB-002")
                .generatedByLlm(false)
                .executedIndependently(true)
                .reproducible(true)
                .build();

        Evidence suite = Evidence.builder("EV-003", EvidenceType.TEST)
                .runId(RUN_ID)
                .status(EvidenceStatus.INCONCLUSIVE)
                .confidence(EvidenceConfidence.DETERMINISTIC)
                .description("Existing test suite execution.")
                .source("surefire")
                .timestamp(Instant.parse("2024-05-01T10:06:00Z"))
                .command(List.of("mvn", "-q", "test"))
                .output("Tests run: 3, Failures: 1, Errors: 0, Skipped: 1")
                .result("failed")
                .requirement("RQ-1")
                .generatedByLlm(false)
                .executedIndependently(true)
                .reproducible(true)
                .build();

        return List.of(generated, refutation, suite);
    }

    static Counterexample counterexample() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("quantity", COUNTEREXAMPLE_QUANTITY);
        inputs.put("item", COUNTEREXAMPLE_ITEM);
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("cw.seed", "42");
        return new Counterexample(
                "CE-001",
                "RQ-2",
                "OB-002",
                "Adding a negative quantity was accepted",
                inputs,
                "the call is rejected with an error",
                "the cart total decreased by 5",
                "total >= 0",
                "dev.example.CartPropertyTest#negativeQuantityRejected",
                "EV-002",
                42L,
                new Counterexample.ReproductionRecipe(
                        "Replay the recorded property test with the same seed.",
                        List.of("mvn", "-q", "-Dtest=CartPropertyTest#negativeQuantityRejected", "test"),
                        Path.of("src/test/java/dev/example/CartPropertyTest.java"),
                        42L,
                        properties),
                Counterexample.DiscoveryMethod.PROPERTY_SEARCH);
    }

    static TestResult tests() {
        List<TestCaseResult> cases = List.of(
                TestCaseResult.passed("dev.example.CartTest", "totalBelowLimit", Duration.ofMillis(15)),
                TestCaseResult.passed("dev.example.CartTest", "totalAtLimit", Duration.ofMillis(11)),
                TestCaseResult.failed("dev.example.CartTest", "negativeQuantityRejected", Duration.ofMillis(12),
                        "org.opentest4j.AssertionFailedError",
                        "expected: <rejected> but was: <accepted>",
                        "at dev.example.CartTest.negativeQuantityRejected(CartTest.java:44)"));
        return TestResult.from(cases, Duration.ofMillis(3450),
                List.of(Path.of("target/surefire-reports")), "surefire");
    }

    static CoverageReport coverage() {
        return new CoverageReport(true, "jacoco", "", 82, 100, 40, 55,
                Map.of("dev.example.Cart", 91.0), List.of(Path.of("target/site/jacoco/jacoco.xml")));
    }

    static MutationReport mutation() {
        SurvivingMutant survivor = new SurvivingMutant("MUT-7", MutantStatus.SURVIVED,
                "ConditionalsBoundaryMutator", "dev.example.Cart", "add", 57,
                "replaced >= with >", null);
        return new MutationReport(true, "pitest", "", 40, 30, 8, 2, 0, List.of(survivor),
                List.of(Path.of("target/pit-reports")));
    }

    static List<Finding> findings() {
        Finding security = new Finding("spotbugs", "SQL_INJECTION", Severity.HIGH, FindingCategory.SECURITY,
                "User input is concatenated into a SQL statement.", "src/main/java/dev/example/Repo.java", 42,
                "example-core", Map.of("cwe", "CWE-89"));
        Finding hostile = new Finding("custom-analyzer", "XSS", Severity.MEDIUM, FindingCategory.BUG,
                HOSTILE_MESSAGE, "src/main/java/dev/example/View.java", 7, "example-app", Map.of());
        return List.of(security, hostile);
    }

    static DependencyReport dependencies() {
        DependencyVulnerability vulnerability = new DependencyVulnerability("com.example:lib", "1.0",
                "CVE-2024-0001", Severity.HIGH, "Prototype pollution in the JSON parser.", "NVD");
        return new DependencyReport(true, "owasp-dependency-check", "", 12,
                List.of("com.example:lib:1.0"), List.of(vulnerability));
    }

    static VerificationRun run() {
        EnvironmentSnapshot environment = new EnvironmentSnapshot("0.1.0", "17.0.10", "Eclipse Adoptium",
                "macOS 14.5", "maven", "3.9.9", "abc1234", "requirements-hash", "config-hash",
                "gpt-4o-mini", "PROCESS", "local process sandbox without network access",
                "/tmp/example", STARTED_AT, FINISHED_AT);
        Budget budget = Budget.defaults();
        BudgetUsage usage = new BudgetUsage(Duration.ofSeconds(12), 2, 34, 5, false, null);
        List<PhaseRecord> phases = List.of(
                new PhaseRecord("build", true, true, Duration.ofSeconds(3), "project compiled", List.of("EV-003"), ""),
                new PhaseRecord("generated-tests", true, true, Duration.ofSeconds(5),
                        "boundary test generated and executed", List.of("EV-001"), ""),
                new PhaseRecord("property-search", true, false, Duration.ofSeconds(4),
                        "counterexample found", List.of("EV-002"), "verification stopped for this requirement"));
        return new VerificationRun(RUN_ID, projectRef(), RunStatus.COMPLETED, phases, environment, budget, usage,
                Path.of("/tmp/codewitness/run"), Path.of("/tmp/codewitness/report.json"),
                Path.of("/tmp/codewitness/report.html"), STARTED_AT, FINISHED_AT, List.of());
    }
}
